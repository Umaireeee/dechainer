package io.github.warleysr.dechainer.lock

import android.content.Context
import android.os.SystemClock
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.RideLock
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.security.SecurityManager
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
            var gathered = gather(ctx, now)
            var plan = plan(now, zone, gathered.state)
            if (plan.expiredFocusBlock) {
                // The end alarm never closed this block (R1). Close it from the time, then plan again
                // with it gone, so one pass both ends the block and releases what it held.
                Pomodoro.closeExpiredBlock(ctx, now)
                gathered = gather(ctx, now)
                plan = plan(now, zone, gathered.state)
            }
            ScheduleEnforcer.applyPlan(ctx, plan, gathered.extras)
            SystemGuard.apply(ctx)
            DnsGuard.enforce(ctx)
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
                LockMode.IMPULSE_LOCK -> R.string.impulse_lock
                LockMode.RIDE_LOCK -> R.string.ride_lock_source
            }
        )

        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            val none = PhoneFacts(emptySet(), emptySet(), emptySet(), emptySet())
            Pomodoro.ensureLoaded(ctx)
            val st = Pomodoro.state.value
            return Gathered(
                LockState(
                    deviceOwner = false, schedules = schedules, phone = none,
                    // Only so an ended block gets closed; without Device Owner nothing is applied.
                    focus = FocusInput(brickEndsAt = if (st.inBlock) st.blockEndsAt else 0L)
                ),
                ScheduleEnforcer.ApplyExtras(emptySet(), -1L, 0L, null, ::label)
            )
        }

        val protectedPkgs = ScheduleEnforcer.protectedPackages(ctx)
        val phone = PhoneFacts(
            launcherApps = ScheduleEnforcer.launcherApps(ctx),
            protectedApps = protectedPkgs,
            alarmApps = ScheduleEnforcer.alarmApps(ctx),
            alwaysAllowed = ScheduleEnforcer.alwaysAllowed(ctx)
        )

        Pomodoro.ensureLoaded(ctx)
        val st = Pomodoro.state.value
        val focus = FocusInput(
            brickEndsAt = if (st.inBlock) st.blockEndsAt else 0L,
            sessionLockEndsAt = if (Pomodoro.sessionLockActive()) (if (st.isRunning) st.endsAt else Long.MAX_VALUE) else 0L,
            allowedApps = Pomodoro.allowedApps.value
        )

        // Daily time limits, measured from Android's own usage log (see TimeLimits).
        val limitStatus = TimeLimits.evaluate(ctx, now)
        val limits = LimitInput(limitStatus.reached, TimeLimits.midnightMillis(now))

        // Until Phase 2 merges them into the urge lock, the impulse and ride locks are still holds.
        // An older version kept the impulse suspensions in a separate record; adopting them lets
        // this engine release them on time.
        SecurityManager.getActiveImpulseSuspension(ctx).takeIf { it.isNotEmpty() }?.let { legacy ->
            legacy.forEach { ScheduleEnforcer.adopt(ctx, it) }  // same lock, re-entered
            SecurityManager.clearActiveImpulseSuspension(ctx)
        }
        val extraHolds = mutableListOf<Hold>()
        val impulseRemaining = SecurityManager.getImpulseBlockRemainingTime(ctx)
        if (impulseRemaining > 0 && SecurityManager.getImpulseAction(ctx) == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND) {
            extraHolds += Hold(LockMode.IMPULSE_LOCK, null, now + impulseRemaining, SecurityManager.getImpulseSuspendedApps(ctx))
        }
        // The ride lock: everything with an icon except calls, text messages (reaching a person is
        // what a hard moment may need most), emergency apps, the alarm clock and the journal.
        val rideRemaining = RideLock.remainingMillis(ctx, now)
        if (rideRemaining > 0) {
            val sms = try { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
            extraHolds += Hold(
                LockMode.RIDE_LOCK, null, now + rideRemaining,
                phone.launcherApps - phone.alarmApps - RideLock.ALWAYS_OPEN - setOfNotNull(sms)
            )
        }

        return Gathered(
            LockState(true, schedules, phone, focus, limits, extraHolds),
            ScheduleEnforcer.ApplyExtras(protectedPkgs, impulseRemaining, rideRemaining, limitStatus.nextCheckDelayMs, ::label)
        )
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
        // First, so no pass that runs meanwhile sees the block as running.
        try {
            Pomodoro.abortBlock(ctx)
        } catch (e: Throwable) {
            Timber.e(e, "Abort: block state")
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
