package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.UserManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import io.github.warleysr.dechainer.models.BlockSchedule
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.focus.Pomodoro
import timber.log.Timber
import java.time.ZonedDateTime

/**
 * Brings the device in line with what should be blocked *right now*: open schedule windows and a
 * running impulse lock. Every call to [sync] is idempotent and computes the full desired state
 * from scratch, so it is safe to call from anywhere: the exact alarm at each boundary, boot,
 * clock/time-zone changes, app installs, and every edit made in the UI. Nothing polls.
 *
 * Ownership: a package or restriction is only released at the end of a window if the schedule is
 * the one that applied it. Anything that was already suspended/restricted before the window opened
 * (a manual suspension, a permanent restriction) is left untouched when it closes.
 */
object ScheduleEnforcer : AppBlockEngine() {
    private const val KEY_OWNED_RESTRICTIONS = "owned_restrictions"
    private const val KEY_ACTIVE_SITES = "active_sites"

    const val ACTION_BOUNDARY = "io.github.warleysr.dechainer.SCHEDULE_BOUNDARY"
    const val ACTION_IMPULSE_END = "io.github.warleysr.dechainer.IMPULSE_END"
    private const val IMPULSE_ALARM_REQUEST_CODE = 2
    private const val RIDE_ALARM_REQUEST_CODE = 3

    // Ownership and the boundary alarm live in AppBlockEngine.
    override val statePrefsName = "schedule_state"
    override val alarmRequestCode = 0
    override val alarmAction = ACTION_BOUNDARY
    override val logName = "Schedule"

    data class ActiveBlock(val scheduleName: String, val endsAtMillis: Long)

    private class BlockSource(val name: String, val endsAt: Long, val apps: Set<String>)

    // One background thread for every sync, so the UI and receivers never wait on it.
    private val worker = Executors.newSingleThreadExecutor()
    private val syncQueued = AtomicBoolean(false)

    /**
     * Runs the schedule and DNS engines off the main thread. Calls that arrive while one is
     * already queued merge into it, so a burst of events costs a single pass.
     */
    fun requestSyncAll(context: Context) {
        val ctx = context.applicationContext
        if (!syncQueued.compareAndSet(false, true)) return
        worker.execute {
            syncQueued.set(false)
            sync(ctx)
            DnsGuard.enforce(ctx)
        }
    }

    // Held here so they live as long as the process (listeners are only weakly referenced).
    private var securityListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var suspensionReceiver: BroadcastReceiver? = null

    /**
     * Reacts the moment an impulse lock starts or ends, so its apps are blocked and released
     * straight away. Two signals, either one enough: the security settings changing
     * (where impulse lock keeps its state), and Android announcing that apps were suspended or
     * unsuspended (which impulse lock does to its apps). Idempotent; call once at startup.
     */
    fun watchForChanges(context: Context) {
        val ctx = context.applicationContext
        if (securityListener == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestSyncAll(ctx) }
            securityListener = listener
            ctx.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
        }
        if (suspensionReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = requestSyncAll(ctx)
            }
            suspensionReceiver = receiver
            try {
                ContextCompat.registerReceiver(
                    ctx, receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGES_SUSPENDED)
                        addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (e: Exception) {
                Timber.w(e, "Could not watch suspensions")
            }
        }
    }

    @Volatile
    private var activeBlocks: Map<String, ActiveBlock> = emptyMap()

    @Volatile
    private var cacheValidUntil = 0L

    // The protected list costs several system lookups (launchers, dialers, keyboards) and changes
    // only when an app is installed or removed, so both engines share it for a few minutes.
    private const val PROTECTED_CACHE_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedProtected: Set<String>? = null

    @Volatile
    private var protectedValidUntil = 0L

    fun sync(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            try {
                syncLocked(ctx)
            } catch (e: Exception) {
                Timber.e(e, "Schedule sync failed")
                // Retry on the next sync instead of trusting a half-applied state.
                cacheValidUntil = 0L
            }
        }
    }

    private fun syncLocked(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
        val schedules = ScheduleRepository.getSchedules(ctx)
        val now = ZonedDateTime.now()
        val nowLocal = now.toLocalDateTime()

        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            activeBlocks = emptyMap()
            cacheValidUntil = System.currentTimeMillis() + ENGINE_MAX_CACHE_MS
            armNextBoundary(ctx, schedules, now)
            return
        }

        val active = schedules.filter { it.isActiveAt(nowLocal) }
        val protectedPkgs = protectedPackages(ctx)

        // What each open window takes away: its own list — or, with "allow only" on, every app
        // with an icon that isn't on its list. A running impulse lock counts as one more window.
        val sources = mutableListOf<BlockSource>()
        active.forEach { schedule ->
            val end = schedule.currentWindowEnd(nowLocal)?.atZone(now.zone)?.toInstant()?.toEpochMilli()
                ?: Long.MAX_VALUE
            val apps = if (schedule.allowOnly) allowOnlyBlocked(ctx, schedule.packages, protectedPkgs)
            else schedule.packages
            sources += BlockSource(schedule.name, end, apps)
        }

        // Impulse lock. An older version kept its suspensions in a separate record, applied by
        // the accessibility service; adopting them lets this engine release them on time.
        SecurityManager.getActiveImpulseSuspension(ctx).takeIf { it.isNotEmpty() }?.let { legacy ->
            legacy.forEach { adopt(ctx, it) }  // same lock, re-entered
            SecurityManager.clearActiveImpulseSuspension(ctx)
        }
        val impulseRemaining = SecurityManager.getImpulseBlockRemainingTime(ctx)
        if (impulseRemaining > 0 &&
            SecurityManager.getImpulseAction(ctx) == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND
        ) {
            sources += BlockSource(
                ctx.getString(io.github.warleysr.dechainer.R.string.impulse_lock),
                System.currentTimeMillis() + impulseRemaining,
                SecurityManager.getImpulseSuspendedApps(ctx)
            )
        }

        // The ride lock: for the few minutes of a ride, everything with an icon is suspended except
        // calls, emergency apps, the alarm clock and the journal the ride happens in.
        val rideRemaining = RideLock.remainingMillis(ctx)
        if (rideRemaining > 0) {
            sources += BlockSource(
                ctx.getString(io.github.warleysr.dechainer.R.string.ride_lock_source),
                System.currentTimeMillis() + rideRemaining,
                launcherApps(ctx) - alarmApps(ctx) - RideLock.ALWAYS_OPEN
            )
        }

        // A locked focus session: only your allowed apps (and the essentials) work until it ends.
        // Its end comes from the Pomodoro's own alarm, which asks for a sync when it fires. The
        // timer's state is read on every sync (cheap after the first load) because any sync may
        // be the one that has to apply or lift this lock.
        Pomodoro.ensureLoaded(ctx)
        if (Pomodoro.focusLockActive()) {
            val st = Pomodoro.state.value
            sources += if (Pomodoro.brickActive()) BlockSource(
                // The brick: everything with an icon, to the end of the block, except the apps you
                // allowed (camera, SMS and Settings stay off unless you allowed them).
                ctx.getString(io.github.warleysr.dechainer.R.string.focus_brick_source),
                st.blockEndsAt,
                brickBlocked(ctx, protectedPkgs, Pomodoro.allowedApps.value)
            ) else BlockSource(
                ctx.getString(io.github.warleysr.dechainer.R.string.focus_lock_source),
                if (st.isRunning) st.endsAt else Long.MAX_VALUE,
                allowOnlyBlocked(ctx, Pomodoro.allowedApps.value, protectedPkgs)
            )
        }

        // Daily time limits: an app that has used its time is paused until midnight. Measured
        // from Android's own usage log, so nothing runs to watch it (see TimeLimits).
        val limitStatus = TimeLimits.evaluate(ctx, System.currentTimeMillis())
        if (limitStatus.reached.isNotEmpty()) {
            sources += BlockSource(
                ctx.getString(io.github.warleysr.dechainer.R.string.limit_source),
                TimeLimits.midnightMillis(System.currentTimeMillis()),
                limitStatus.reached
            )
        }

        val desiredApps = sources.flatMap { it.apps }.toSet() - protectedPkgs
        val desiredRestrictions = active.flatMap { it.restrictions }.toMutableSet()
        val desiredSites = active.flatMap { it.websites }.toSet()

        // The clock is locked while schedules are on (if you chose that), and always during a locked
        // focus session or a focus block: moving the time would end either one early.
        if ((ScheduleRepository.isAntiTamperEnabled(ctx) && schedules.any { it.enabled }) ||
            Pomodoro.holdsClock() || rideRemaining > 0
        ) {
            desiredRestrictions += UserManager.DISALLOW_CONFIG_DATE_TIME
            try {
                // Read first: these are settings writes, and sync runs on every tick and alarm.
                if (!dpm.getAutoTimeEnabled(admin)) dpm.setAutoTimeEnabled(admin, true)
                if (!dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin, true)
            } catch (e: Exception) {
                Timber.w(e, "Could not force automatic time")
            }
        }

        // A brick closes every way around it, for exactly as long as the block runs: safe mode
        // (boots without Déchaîner), USB debugging (ADB can lift the pin), a factory reset, and
        // other users (a guest user has no brick). Released at the end like any other hold;
        // anything you'd switched on yourself in System rules stays on.
        val brick = Pomodoro.brickActive()
        if (brick) {
            desiredRestrictions += listOf(
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_USER_SWITCH
            )
        }
        applyBrickHome(ctx, dpm, admin, brick)

        applyApps(ctx, dpm, admin, desiredApps) { emptySet() }
        applyRestrictions(ctx, dpm, admin, desiredRestrictions)
        applySites(ctx, desiredSites)

        // An exact alarm at the moment the impulse lock ends, so its apps come back on time
        // even with the screen off.
        armImpulseEnd(ctx, impulseRemaining)
        armRideEnd(ctx, rideRemaining)
        // And a non-wakeup alarm for the earliest moment a limit could run out (or midnight).
        TimeLimits.armCheck(ctx, limitStatus.nextCheckDelayMs)

        activeBlocks = buildMap<String, ActiveBlock> {
            sources.forEach { source ->
                source.apps.forEach { pkg ->
                    val existing = get(pkg)
                    // With overlapping windows the app stays blocked until the last one ends.
                    if (existing == null || source.endsAt > existing.endsAtMillis)
                        put(pkg, ActiveBlock(source.name, source.endsAt))
                }
            }
        }.filterKeys { it !in protectedPkgs }

        val next = armNextBoundary(ctx, schedules, now)
        val cap = System.currentTimeMillis() + ENGINE_MAX_CACHE_MS
        cacheValidUntil = if (next != null) minOf(next, cap) else cap
    }

    /**
     * An alarm at the moment the impulse lock ends, which fires even with the screen off, so the
     * apps come back on time rather than at the next screen-on.
     */
    @Volatile
    private var impulseAlarmAt = 0L

    private fun armImpulseEnd(ctx: Context, remainingMillis: Long) {
        // Runs on every sync, so only touch AlarmManager when the target actually changes.
        val target = if (remainingMillis > 0) System.currentTimeMillis() + remainingMillis + 1000L else 0L
        if (target == 0L && impulseAlarmAt == 0L) return
        if (target != 0L && kotlin.math.abs(target - impulseAlarmAt) < 2000L) return
        impulseAlarmAt = target
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            ctx,
            IMPULSE_ALARM_REQUEST_CODE,
            Intent(ctx, io.github.warleysr.dechainer.ScheduleReceiver::class.java).setAction(ACTION_IMPULSE_END),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (remainingMillis <= 0) {
            am.cancel(pi)
            return
        }
        val triggerAt = target
        try {
            val canExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()
            if (canExact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Same idea as [armImpulseEnd], for the ride lock: an exact alarm so its apps come back on time. */
    @Volatile
    private var rideAlarmAt = 0L

    private fun armRideEnd(ctx: Context, remainingMillis: Long) {
        val target = if (remainingMillis > 0) System.currentTimeMillis() + remainingMillis + 1000L else 0L
        if (target == 0L && rideAlarmAt == 0L) return
        if (target != 0L && kotlin.math.abs(target - rideAlarmAt) < 2000L) return
        rideAlarmAt = target
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            ctx,
            RIDE_ALARM_REQUEST_CODE,
            Intent(ctx, io.github.warleysr.dechainer.ScheduleReceiver::class.java).setAction(ACTION_IMPULSE_END),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (remainingMillis <= 0) {
            am.cancel(pi)
            return
        }
        try {
            val canExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()
            if (canExact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target, pi)
        }
    }

    private fun applyRestrictions(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        desired: Set<String>
    ) {
        val prefs = state(ctx)
        val initiallyOwned = prefs.getStringSet(KEY_OWNED_RESTRICTIONS, emptySet())?.toSet() ?: emptySet()
        val owned = initiallyOwned.toMutableSet()
        val current = dpm.getUserRestrictions(admin)

        desired.forEach { key ->
            // Re-applied even when already owned: other code paths (e.g. the accessibility
            // service clearing DISALLOW_INSTALL_APPS on connect) may have lifted it mid-window.
            if (!current.getBoolean(key)) {
                try {
                    dpm.addUserRestriction(admin, key)
                    owned += key
                } catch (e: Exception) {
                    Timber.w(e, "Schedule: could not add restriction $key")
                }
            }
        }

        (owned - desired).forEach { key ->
            try {
                dpm.clearUserRestriction(admin, key)
            } catch (e: Exception) {
                Timber.w(e, "Schedule: could not clear restriction $key")
            }
            owned -= key
        }

        if (owned != initiallyOwned) prefs.edit(commit = true) { putStringSet(KEY_OWNED_RESTRICTIONS, owned) }
    }

    private fun applySites(ctx: Context, desired: Set<String>) {
        val prefs = state(ctx)
        val previous = prefs.getStringSet(KEY_ACTIVE_SITES, emptySet()) ?: emptySet()
        if (previous == desired) return
        prefs.edit(commit = true) { putStringSet(KEY_ACTIVE_SITES, desired) }
        try {
            BrowserRestrictionsManager(ctx).applyRestrictions()
        } catch (e: Exception) {
            Timber.w(e, "Schedule: could not update browser blocklist")
        }
    }

    /** Websites blocked by schedules that are open right now — merged into the browser blocklist. */
    fun getActiveScheduledSites(context: Context): Set<String> =
        state(context).getStringSet(KEY_ACTIVE_SITES, emptySet()) ?: emptySet()

    /**
     * The schedule, impulse lock or focus session holding [pkg] right now, if any. With [fresh]
     * true an expired cache is refreshed first (what the Apps tab uses before lifting an app);
     * with it false, the refresh happens in the background and the cached answer is returned.
     */
    fun activeBlockFor(context: Context, pkg: String, fresh: Boolean = true): ActiveBlock? {
        if (System.currentTimeMillis() >= cacheValidUntil) {
            if (fresh) sync(context) else requestSyncAll(context)
        }
        return activeBlocks[pkg]
    }

    fun isHeldBySchedule(context: Context, pkg: String): Boolean = activeBlockFor(context, pkg) != null

    /** True while any schedule is inside a window it was told to lock. */
    fun isAnyScheduleLocked(context: Context): Boolean =
        ScheduleRepository.getSchedules(context).any { ScheduleRepository.isLockedNow(it) }

    /**
     * Gives up ownership of restrictions the person has just switched on by hand, without lifting
     * them. Otherwise a schedule that also uses one would switch it back off when its window ended.
     */
    fun disownRestrictions(context: Context, keys: Set<String>) = synchronized(lock) {
        if (keys.isEmpty()) return@synchronized
        val prefs = state(context)
        val owned = prefs.getStringSet(KEY_OWNED_RESTRICTIONS, emptySet())?.toSet() ?: emptySet()
        if (owned.any { it in keys }) {
            prefs.edit(commit = true) { putStringSet(KEY_OWNED_RESTRICTIONS, owned - keys) }
        }
    }

    /** Lifts everything schedules applied. Used right before Device Owner is removed. */
    fun releaseAll(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                    applyApps(ctx, dpm, admin, emptySet()) { emptySet() }
                    applyRestrictions(ctx, dpm, admin, emptySet())
                }
                applySites(ctx, emptySet())
                activeBlocks = emptyMap()
                cancelAlarm(ctx)
            } catch (e: Exception) {
                Timber.e(e, "Schedule releaseAll failed")
            }
        }
    }

    /**
     * Apps that must never be blocked: Dechainer itself, the launcher (the "go home" fallback would
     * loop), System UI, the phone app so emergency calls always work, and enabled keyboards.
     */
    fun protectedPackages(context: Context): Set<String> {
        cachedProtected?.let { if (System.currentTimeMillis() < protectedValidUntil) return it }
        val fresh = readProtectedPackages(context)
        cachedProtected = fresh
        protectedValidUntil = System.currentTimeMillis() + PROTECTED_CACHE_MS
        return fresh
    }

    /** Called when a package is installed or removed: a new launcher or keyboard changes this list. */
    fun invalidateProtectedPackages() {
        launcherValidUntil = 0L
        protectedValidUntil = 0L
    }

    private fun readProtectedPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val result = mutableSetOf(context.packageName, "com.android.systemui", "com.android.phone")
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                .forEach { result += it.activityInfo.packageName }
        } catch (_: Exception) { }
        // minSdk is 30, so getSystemDialerPackage() (API 29) is always present and the
        // NoSuchMethodError some reviewers warn about cannot occur here. Kept null-safe and
        // Throwable-safe anyway: this runs on the boot path, where dying is expensive.
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { result += it }
            telecom?.systemDialerPackage?.let { result += it }
        } catch (_: Throwable) { }
        // Keyboards: a hidden or suspended keyboard means nothing can be typed anywhere, including
        // the recovery code.
        try {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.enabledInputMethodList?.forEach { result += it.packageName }
        } catch (_: Throwable) { }
        return result
    }

    /** Arms the alarm at the next window start or end. Returns that alarm time, if any. */
    private fun armNextBoundary(ctx: Context, schedules: List<BlockSchedule>, now: ZonedDateTime): Long? {
        val nextWindow = schedules.flatMap { it.boundariesAfter(now) }.minOrNull()?.toInstant()?.toEpochMilli()
        return armAlarmFor(ctx, nextWindow)
    }

    // ---- "Allow only" schedules ----

    @Volatile private var cachedLauncherApps: Set<String>? = null
    @Volatile private var launcherValidUntil = 0L

    /** Every app with an icon. Cached; dropped when apps are (un)installed. */
    private fun launcherApps(ctx: Context): Set<String> {
        cachedLauncherApps?.let { if (System.currentTimeMillis() < launcherValidUntil) return it }
        val fresh = try {
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        cachedLauncherApps = fresh
        launcherValidUntil = System.currentTimeMillis() + PROTECTED_CACHE_MS
        return fresh
    }

    /**
     * Never taken away by an "allow only" window, whatever its list says: alarm clocks so a
     * morning alarm still rings, the SMS app so one-time codes arrive, the camera, Settings, and a few
     * system pieces other apps lean on (file picker, SIM menu, emergency info, and Xiaomi's
     * security app, which shows permission prompts on MIUI/HyperOS).
     */
    private fun alwaysAllowed(ctx: Context): Set<String> {
        val result = mutableSetOf(
            "com.android.settings",
            "com.android.documentsui", "com.google.android.documentsui",
            "com.android.stk", "com.android.emergency", "com.google.android.apps.safetyhub",
            "com.miui.securitycenter"
        )
        for (action in listOf(android.provider.AlarmClock.ACTION_SHOW_ALARMS, android.provider.AlarmClock.ACTION_SET_ALARM)) {
            try {
                ctx.packageManager.queryIntentActivities(Intent(action), 0).forEach { result += it.activityInfo.packageName }
            } catch (_: Exception) { }
        }
        try { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)?.let { result += it } } catch (_: Exception) { }
        // The camera, so notes and whiteboards can be photographed mid-session: whatever apps
        // answer "take a photo" or "open the camera".
        for (action in listOf(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
            android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        )) {
            try {
                ctx.packageManager.queryIntentActivities(Intent(action), 0).forEach { result += it.activityInfo.packageName }
            } catch (_: Exception) { }
        }
        return result
    }

    /**
     * During a brick, Déchaîner is the home screen: after a reboot, a crash or a Home press, the
     * phone opens the timer (which pins itself again) instead of the launcher. Switched off, and
     * your own launcher restored, when the block ends.
     */
    private fun applyBrickHome(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, on: Boolean) {
        val alias = ComponentName(ctx, "io.github.warleysr.dechainer.activities.BrickHome")
        val pm = ctx.packageManager
        try {
            val enabled = pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (on && !enabled) {
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                val home = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, home, alias)
            } else if (!on && enabled) {
                dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            Timber.w(e, "Brick home screen not changed")
        }
    }

    /** Alarm clock apps: left alone even by the brick, so a morning alarm still rings. */
    private fun alarmApps(ctx: Context): Set<String> {
        val result = mutableSetOf<String>()
        for (action in listOf(android.provider.AlarmClock.ACTION_SHOW_ALARMS, android.provider.AlarmClock.ACTION_SET_ALARM)) {
            try {
                ctx.packageManager.queryIntentActivities(Intent(action), 0).forEach { result += it.activityInfo.packageName }
            } catch (_: Exception) { }
        }
        return result
    }

    /**
     * What the brick suspends: every app with an icon except the ones the phone can't work
     * without (home screen, dialer, keyboards, Déchaîner), the alarm clock, and [allowed].
     */
    private fun brickBlocked(ctx: Context, protectedPkgs: Set<String>, allowed: Set<String>): Set<String> =
        launcherApps(ctx) - protectedPkgs - alarmApps(ctx) - allowed

    /** What an "allow only" window suspends: every app with an icon except [allowed] and the essentials. */
    private fun allowOnlyBlocked(ctx: Context, allowed: Set<String>, protectedPkgs: Set<String>): Set<String> =
        launcherApps(ctx) - allowed - protectedPkgs - alwaysAllowed(ctx)
}
