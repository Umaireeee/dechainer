package io.github.warleysr.dechainer.lock

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.LockSafety
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.focus.FocusRunner
import io.github.warleysr.dechainer.focus.Pomodoro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one lock engine (blueprint 5.1). [plan] is the pure decision ([LockPlanner]); [sync] reads
 * the stored state, plans on the trusted clock, and applies the result through [ScheduleEnforcer]
 * and the existing blocker code. Truth comes from stored data and the clock, never from an alarm
 * firing: alarms are only wake-ups, and every wake-up (boot, unlock, app open, alarm, receiver)
 * calls [sync] or [requestSync], which recompute everything.
 *
 * One pass at a time on one thread. Requests that arrive while a pass is waiting to start are
 * served by that one pass ([SyncCoalescer]), so a wake-up costs one sync, not several.
 */
object LockEngine {
    /** The pure plan, for callers that already hold a [LockState]. */
    fun plan(now: Long, zone: ZoneId, state: LockState): LockPlan = LockPlanner.plan(now, zone, state)

    private val coalescer = SyncCoalescer()
    private val gate = ReentrantLock()
    private val served = gate.newCondition()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lock-engine").apply { isDaemon = true } }
    private val draining = AtomicBoolean(false)
    private val inPass = ThreadLocal<Boolean>()
    private val startLock = Any()

    private val _status = MutableStateFlow<BrickStatus?>(null)

    /**
     * What holds the phone right now (an urge lock, a focus block, a punishment day), for the lock
     * screen and the pin; null when nothing does. Every pass publishes the plan's answer, and
     * [refreshStatus] fills it in at once, before any pass has run.
     */
    val status: StateFlow<BrickStatus?> = _status.asStateFlow()

    /** Elapsed-realtime when the latest pass began; lets the process-start request skip a pass a receiver already ran. */
    @Volatile private var lastPassStartedElapsed = Long.MIN_VALUE

    /** Longest a blocking [sync] waits for its pass, so a stuck pass can never hang a receiver for good. */
    private const val SYNC_WAIT_MS = 60_000L

    /** Runs a pass and returns when one that began after this call has finished. For receivers and background threads. */
    fun sync(context: Context) {
        // A pass already running on this thread (a callback during apply) is the sync being asked for.
        if (inPass.get() == true) return
        val ctx = context.applicationContext
        val ticket = coalescer.request()
        kick(ctx)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SYNC_WAIT_MS)
        gate.withLock {
            while (!coalescer.isServed(ticket)) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) {
                    Timber.w("Lock sync did not finish within %d ms", SYNC_WAIT_MS)
                    return
                }
                served.awaitNanos(left)
            }
        }
    }

    /**
     * Asks for a pass without waiting for it. Merges with any pass that has not started yet.
     * With [unlessStartedSince] (an elapsed-realtime) the request is dropped if a pass has begun at
     * or after that moment: the process-start request uses it so a receiver's pass is not repeated.
     */
    fun requestSync(context: Context, unlessStartedSince: Long? = null) {
        if (unlessStartedSince != null && lastPassStartedElapsed >= unlessStartedSince) return
        coalescer.request()
        kick(context.applicationContext)
    }

    private fun kick(ctx: Context) {
        if (!draining.compareAndSet(false, true)) return
        worker.execute {
            try {
                drain(ctx)
            } finally {
                draining.set(false)
                // A request that came in just as the drain ended would otherwise wait for the next one.
                if (coalescer.hasWaiting()) kick(ctx)
            }
        }
    }

    private fun drain(ctx: Context) {
        while (true) {
            val claim = coalescer.beginPass() ?: break
            lastPassStartedElapsed = SystemClock.elapsedRealtime()
            try {
                runPass(ctx)
            } catch (e: Throwable) {
                Timber.e(e, "Lock pass failed")
            } finally {
                coalescer.endPass(claim)
                gate.withLock { served.signalAll() }
            }
        }
    }

    private fun runPass(ctx: Context) {
        inPass.set(true)
        try {
            // The wake-up reading is written down before anything is decided on it.
            val now = TrustedClock.checkpoint(ctx)
            val zone = TrustedClock.zone()
            // The focus session's own clock first (prompt and check-in timeouts, the reset, a FOCUS
            // timetable entry that is due): it can start or end a block, which the plan below must see.
            try {
                // Closing out only: a FOCUS entry is started below, after the day has been judged.
                FocusRunner.advance(ctx, now, startEntries = false)
            } catch (e: Throwable) {
                Timber.e(e, "Focus flow not advanced; the lock is planned without it")
            }
            // The daily checklist: close yesterday, judge the new day, record a punishment day, arm the evening
            // wake-ups. Before the plan, so a punishment day that begins now is in it. It never throws.
            io.github.warleysr.dechainer.day.DayEngine.runPass(ctx, now)
            try {
                FocusRunner.startDue(ctx, now)
            } catch (e: Throwable) {
                Timber.e(e, "Due focus entry not started; the lock is planned without it")
            }
            var gathered = gather(ctx, now)
            var plan = plan(now, zone, gathered.state)
            if (plan.expiredFocusBlock) {
                // The end alarm never closed this block (R1). Close it from the time, then plan again
                // with it gone, so one pass both ends the block and releases what it held. If closing
                // the record fails, the plan still drops the expired hold, so the apps are released anyway.
                try {
                    Pomodoro.closeExpiredBlock(ctx, now)
                } catch (e: Throwable) {
                    Timber.e(e, "Expired block record not closed; releasing what it held anyway")
                }
                gathered = gather(ctx, now)
                plan = plan(now, zone, gathered.state)
            }
            _status.value = plan.brickStatus
            try { ScheduleEnforcer.applyPlan(ctx, plan, gathered.extras) } catch (e: Throwable) { Timber.e(e, "Plan not applied") }
            // Each guard on its own: one failing must not skip the others.
            try { SystemGuard.apply(ctx) } catch (e: Throwable) { Timber.e(e, "System guard failed") }
            try { DnsGuard.enforce(ctx) } catch (e: Throwable) { Timber.e(e, "DNS guard failed") }
        } finally {
            inPass.set(false)
        }
    }

    private class Gathered(val state: LockState, val extras: ScheduleEnforcer.ApplyExtras)

    /** Reads everything [LockPlanner] decides from. The only place the plan's inputs come from. */
    private fun gather(ctx: Context, now: Long): Gathered {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val schedules = ScheduleRepository.getSchedules(ctx)
        fun label(mode: LockMode): String = ctx.getString(
            when (mode) {
                LockMode.SCHEDULE -> R.string.schedules
                LockMode.DAILY_LIMIT -> R.string.limit_source
                LockMode.FOCUS_BLOCK -> R.string.focus_brick_source
                LockMode.FOCUS_SESSION -> R.string.focus_lock_source
                LockMode.URGE_LOCK -> R.string.urge_lock_source
                LockMode.PUNISHMENT_DAY -> R.string.punishment_day_source
            }
        )
        // The urge lock and the punishment day come from `app_state`; they are known on any phone, so
        // the settings freeze and the status never depend on Device Owner being asked.
        val urge = LockStateStore.urge(ctx)
        val punishment = LockStateStore.punishment(ctx)

        Pomodoro.ensureLoaded(ctx)
        val st = Pomodoro.state.value

        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            val none = PhoneFacts(emptySet(), emptySet(), emptySet(), emptySet())
            return Gathered(
                LockState(
                    deviceOwner = false, schedules = schedules, phone = none,
                    // Only so an ended block gets closed; without Device Owner nothing is applied.
                    focus = FocusInput(brickEndsAt = if (st.inBlock) st.blockEndsAt else 0L, wakeAt = FocusRunner.nextWake(ctx)),
                    urge = urge, punishment = punishment
                ),
                ScheduleEnforcer.ApplyExtras(emptySet(), null, ::label)
            )
        }

        val protectedPkgs = ScheduleEnforcer.protectedPackages(ctx)
        val sms = try { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
        val phone = PhoneFacts(
            launcherApps = ScheduleEnforcer.launcherApps(ctx),
            protectedApps = protectedPkgs,
            alarmApps = ScheduleEnforcer.alarmApps(ctx),
            alwaysAllowed = ScheduleEnforcer.alwaysAllowed(ctx),
            emergencyApps = LockSafety.EMERGENCY_APPS,
            smsApps = setOfNotNull(sms)
        )

        val focus = FocusInput(
            brickEndsAt = if (st.inBlock) st.blockEndsAt else 0L,
            sessionLockEndsAt = if (Pomodoro.sessionLockActive()) (if (st.isRunning) st.endsAt else Long.MAX_VALUE) else 0L,
            allowedApps = Pomodoro.allowedApps.value,
            wakeAt = FocusRunner.nextWake(ctx)
        )

        // Daily time limits, measured from Android's own usage log (see TimeLimits).
        val limitStatus = TimeLimits.evaluate(ctx, now)
        val limits = LimitInput(limitStatus.reached, TimeLimits.midnightMillis(now))

        return Gathered(
            LockState(true, schedules, phone, focus, limits, urge, punishment),
            ScheduleEnforcer.ApplyExtras(protectedPkgs, limitStatus.nextCheckDelayMs, ::label)
        )
    }

    private fun isDeviceOwner(ctx: Context): Boolean =
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).isDeviceOwnerApp(ctx.packageName)

    /**
     * The lock status read straight from the stored state, without a pass: what the lock screen
     * shows on its first frame, and what answers "is a brick running" for a caller that cannot wait.
     * It uses the same rule as the plan ([LockPlanner.quickStatus]). Without Device Owner nothing is locked.
     */
    fun refreshStatus(context: Context): BrickStatus? {
        val ctx = context.applicationContext
        val status = try {
            if (!isDeviceOwner(ctx)) null else {
                Pomodoro.ensureLoaded(ctx)
                val st = Pomodoro.state.value
                LockPlanner.quickStatus(
                    TrustedClock.now(ctx),
                    FocusInput(brickEndsAt = if (st.inBlock) st.blockEndsAt else 0L, allowedApps = Pomodoro.allowedApps.value),
                    LockStateStore.urge(ctx),
                    LockStateStore.punishment(ctx)
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Lock status not readable; keeping the last one")
            _status.value
        }
        _status.value = status
        return status
    }

    /** True while an urge lock, a focus block or a punishment day holds the phone. */
    fun brickRunning(context: Context): Boolean = refreshStatus(context) != null

    /**
     * The owner asked for an urge lock (the panic button, the Quick Settings tile, the door; blueprint
     * 5.2). The rule is [UrgeLockRule]: ten minutes, never extended by a second tap, no second lock
     * inside a focus block or a punishment day. The lock is stored and shown before this returns and
     * before the caller does anything else: it never waits on the network. Returns what happened.
     */
    fun startUrgeLock(context: Context): UrgeStart = startUrgeLock(context, Rules.URGE_LOCK_MS)

    private fun startUrgeLock(context: Context, lockMs: Long): UrgeStart {
        val ctx = context.applicationContext
        val decision = synchronized(startLock) {
            val now = TrustedClock.checkpoint(ctx)
            Pomodoro.ensureLoaded(ctx)
            val decision = UrgeLockRule.decide(
                now = now,
                runningUrgeEndsAt = LockStateStore.urge(ctx).endsAt,
                focusBlockActive = Pomodoro.brickActive(),
                punishmentActive = LockStateStore.punishment(ctx).activeAt(now),
                deviceOwner = isDeviceOwner(ctx),
                lockMs = lockMs
            )
            if (decision is UrgeStart.Started) LockStateStore.setUrge(ctx, now, decision.endsAt)
            decision
        }
        refreshStatus(ctx)
        requestSync(ctx)
        return decision
    }

    /**
     * After a reboot or an update the pin is gone: if any brick is running, open this app, which pins
     * itself again. Suspension holds meanwhile (it is the layer under the pin).
     */
    fun reopenIfBrick(context: Context) {
        val ctx = context.applicationContext
        if (!brickRunning(ctx)) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Timber.w(e, "Couldn't reopen the brick screen")
        }
    }

    /**
     * The last resort against a bug that traps the phone (blueprint 5.4, R2): ends the focus block,
     * gives the home screen back, and lifts what the engine had applied. Reached only from the
     * crash-loop breaker (and the debug build's broadcast); there is no way to it from the UI, and it
     * never throws. A normal [sync] re-applies any schedule that is still open afterwards.
     *
     * It runs inside an uncaught-exception handler, so each release step has a time limit: a step
     * stuck behind another thread cannot keep the process from dying.
     */
    fun abortBrick(context: Context) {
        val ctx = context.applicationContext
        Timber.e("Aborting the brick")
        // First, so no pass that runs meanwhile sees a brick as running.
        try {
            Pomodoro.abortBlock(ctx)
        } catch (e: Throwable) {
            Timber.e(e, "Abort: block state")
        }
        _status.value = null
        // The session is closed on record as ended by the system, so it is not taken for the owner's doing.
        bounded("focus session record") { FocusRunner.abort(ctx) }
        // The urge lock is dropped and a punishment day is ended on record (by the system, so it is not
        // taken for the owner's doing). Without that the next pass would put the brick straight back.
        bounded("urge lock and punishment records") {
            LockStateStore.clearUrge(ctx)
            LockStateStore.endPunishmentBySystem(ctx, TrustedClock.now(ctx))
        }
        bounded("home screen") { ScheduleEnforcer.releaseBrickHome(ctx) }
        bounded("restrictions and apps") { ScheduleEnforcer.releaseAll(ctx) }
    }

    private const val ABORT_STEP_LIMIT_MS = 4_000L

    private fun bounded(name: String, work: () -> Unit) {
        val t = Thread({
            try {
                work()
            } catch (e: Throwable) {
                Timber.e(e, "Abort: $name")
            }
        }, "lock-abort").apply { isDaemon = true }
        t.start()
        t.join(ABORT_STEP_LIMIT_MS)
        if (t.isAlive) Timber.e("Abort: $name did not finish in %d ms", ABORT_STEP_LIMIT_MS)
    }

    /** Debug builds only (blueprint 14A): an urge lock of [minutes], through the same rule as the real one. A release build refuses. */
    fun debugStartUrgeLock(context: Context, minutes: Int): UrgeStart? {
        if (!BuildConfig.DEBUG) return null
        return startUrgeLock(context, minutes * 60_000L)
    }

    /** Debug builds only: a punishment day of [minutes] starting now, recorded like a real one. A release build refuses. */
    fun debugStartPunishment(context: Context, minutes: Int) {
        if (!BuildConfig.DEBUG) return
        val ctx = context.applicationContext
        val now = TrustedClock.checkpoint(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now, now + minutes * 60_000L), "debug")
        refreshStatus(ctx)
        requestSync(ctx)
    }

    /**
     * Debug builds only (blueprint 14A): drops the alarms that would end a block, to prove that the
     * next wake-up ends it without them. A release build refuses.
     */
    fun debugDropAlarms(context: Context) {
        if (!BuildConfig.DEBUG) return
        val ctx = context.applicationContext
        Pomodoro.debugCancelAlarm(ctx)
        ScheduleEnforcer.debugCancelAlarm(ctx)
    }
}
