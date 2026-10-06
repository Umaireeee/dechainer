package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.ActiveBlock
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockPlan
import io.github.warleysr.dechainer.lock.LockPlanner
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber

/**
 * Applies a [LockPlan] to the device. The decisions (what is locked, until when) are made by the
 * pure [LockPlanner]; [LockEngine.sync] gathers the state, plans, and hands the plan to [applyPlan].
 * Every [applyPlan] is idempotent and safe to repeat, so any wake-up may run it: the exact alarm at each
 * boundary, boot, clock changes, app installs, and every edit made in the UI. Nothing polls.
 *
 * Ownership: a package or restriction is only released at the end of a hold if this engine is the
 * one that applied it. Anything that was already suspended/restricted before the hold started (a
 * manual suspension, a permanent restriction) is left untouched when it ends.
 */
object ScheduleEnforcer : AppBlockEngine() {
    private const val KEY_OWNED_RESTRICTIONS = "owned_restrictions"
    private const val KEY_ACTIVE_SITES = "active_sites"

    const val ACTION_BOUNDARY = "io.github.warleysr.dechainer.SCHEDULE_BOUNDARY"

    // Ownership and the boundary alarm live in AppBlockEngine.
    override val statePrefsName = "schedule_state"
    override val alarmRequestCode = 0
    override val alarmAction = ACTION_BOUNDARY
    override val logName = "Schedule"

    /** What [applyPlan] needs besides the plan: facts that are not decisions. */
    internal class ApplyExtras(
        val protectedPackages: Set<String>,
        val limitNextCheckDelayMs: Long?,
        /** The label shown on the Apps screen for a hold that has no name of its own. */
        val labelFor: (LockMode) -> String
    )

    // Held here so it lives as long as the process.
    private var suspensionReceiver: BroadcastReceiver? = null

    /**
     * Reacts the moment Android announces that apps were suspended or unsuspended, by anyone: if
     * something outside the engine lifts a suspension the engine holds, the next pass puts it back
     * (the engine's own changes cost one more pass that finds nothing to do). Idempotent; call once at startup.
     */
    fun watchForChanges(context: Context) {
        val ctx = context.applicationContext
        if (suspensionReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = LockEngine.requestSync(ctx)
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

    /** Brings the device in line with [plan]. Called by [LockEngine] only; one pass at a time. */
    internal fun applyPlan(context: Context, plan: LockPlan, extras: ApplyExtras) {
        val ctx = context.applicationContext
        synchronized(lock) {
            try {
                applyLocked(ctx, plan, extras)
            } catch (e: Exception) {
                Timber.e(e, "Lock apply failed")
                // Retry on the next sync instead of trusting a half-applied state.
                cacheValidUntil = 0L
            }
        }
    }

    private fun applyLocked(ctx: Context, plan: LockPlan, extras: ApplyExtras) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
        val wallNow = System.currentTimeMillis()
        // Alarms fire on wall time; the plan is on the trusted clock. They differ only while the wall clock is behind.
        val nextWake = plan.nextWakeAt?.let { TrustedClock.toWall(it, ctx) }

        if (!plan.deviceOwner) {
            activeBlocks = emptyMap()
            cacheValidUntil = wallNow + ENGINE_MAX_CACHE_MS
            armAlarmFor(ctx, nextWake)
            return
        }

        // Each stage on its own: one that fails must not stop the others (a refused restriction
        // must not leave apps unsuspended, and the other way round). A failed stage makes the next
        // sync run in full instead of trusting the cache.
        var anyFailed = false
        fun stage(name: String, work: () -> Unit) {
            try {
                work()
            } catch (e: Exception) {
                Timber.e(e, "Lock apply: $name failed")
                anyFailed = true
            }
        }
        stage("brick home") { if (!applyBrickHome(ctx, dpm, admin, plan.brick)) anyFailed = true }
        stage("apps") { applyApps(ctx, dpm, admin, plan.desiredApps) { emptySet() } }
        stage("restrictions") { applyRestrictions(ctx, dpm, admin, plan.desiredRestrictions) }
        stage("sites") { applySites(ctx, plan.desiredSites) }
        stage("alarms") {
            // A non-wakeup alarm for the earliest moment a limit could run out (or midnight).
            TimeLimits.armCheck(ctx, extras.limitNextCheckDelayMs)
        }
        // Write down the running time of a forced removal, so a reboot can't lose what came after the last write.
        stage("forced removal clock") { SecurityManager.getForcedRemovalRemainingTime(ctx) }

        activeBlocks = LockPlanner.blockedBy(plan.holds, extras.protectedPackages)
            .mapValues { (_, hold) -> ActiveBlock(hold.name ?: extras.labelFor(hold.mode), hold.endsAt) }

        // The next boundary, brick end or limit reset: the backup wake-up that does not rely on
        // any other alarm having fired.
        val next = armAlarmFor(ctx, nextWake)
        val cap = wallNow + ENGINE_MAX_CACHE_MS
        cacheValidUntil = if (anyFailed) 0L else if (next != null) minOf(next, cap) else cap
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
     * The hold (a brick, a schedule, a limit or a focus session) keeping [pkg] suspended right now, if any. With [fresh]
     * true an expired cache is refreshed first (what the Apps tab uses before lifting an app);
     * with it false, the refresh happens in the background and the cached answer is returned.
     */
    fun activeBlockFor(context: Context, pkg: String, fresh: Boolean = true): ActiveBlock? {
        if (System.currentTimeMillis() >= cacheValidUntil) {
            if (fresh) LockEngine.sync(context) else LockEngine.requestSync(context)
        }
        return activeBlocks[pkg]
    }

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

    /** Debug builds only: forget the boundary alarm, as if it had been lost (blueprint 14A). */
    internal fun debugCancelAlarm(context: Context) {
        if (io.github.warleysr.dechainer.BuildConfig.DEBUG) cancelAlarm(context.applicationContext)
    }

    /** Puts the home screen back and switches the brick's own off. For [LockEngine.abortBrick]. */
    internal fun releaseBrickHome(context: Context): Boolean {
        val ctx = context.applicationContext
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return true
        return synchronized(lock) {
            applyBrickHome(ctx, dpm, ComponentName(ctx, DechainerDeviceAdminReceiver::class.java), false)
        }
    }

    /**
     * Apps that must never be blocked: Dechainer itself, the launcher (the "go home" fallback would
     * loop), System UI, the phone app so emergency calls always work, and enabled keyboards.
     */
    fun protectedPackages(context: Context): Set<String> {
        val stable = cachedProtected?.takeIf { System.currentTimeMillis() < protectedValidUntil }
            ?: readProtectedPackages(context).also {
                cachedProtected = it
                protectedValidUntil = System.currentTimeMillis() + PROTECTED_CACHE_MS
            }
        // Home selection can change without a package broadcast; resolve it on every pass.
        return stable + homePackages(context)
    }

    /** Called when a package is installed or removed: a new launcher or keyboard changes this list. */
    fun invalidateProtectedPackages() {
        launcherValidUntil = 0L
        protectedValidUntil = 0L
    }

    @Volatile private var lastKnownHomes: Set<String> = emptySet()

    private fun homePackages(context: Context): Set<String> = try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
        val selected = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        LockSafety.protectedHomes(candidates, selected).also { lastKnownHomes = it }
    } catch (_: Exception) { lastKnownHomes }

    private fun readProtectedPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val result = LockSafety.neverBlocked(context.packageName).toMutableSet()
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

    // ---- "Allow only" schedules ----

    @Volatile private var cachedLauncherApps: Set<String>? = null
    @Volatile private var launcherValidUntil = 0L

    /** Every app with an icon. Cached; dropped when apps are (un)installed. */
    internal fun launcherApps(ctx: Context): Set<String> {
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
    internal fun alwaysAllowed(ctx: Context): Set<String> {
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
    private fun applyBrickHome(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, on: Boolean): Boolean {
        val alias = ComponentName(ctx, "io.github.warleysr.dechainer.activities.BrickHome")
        val pm = ctx.packageManager
        return try {
            val enabled = pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (on && !enabled) {
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                val home = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                try {
                    dpm.addPersistentPreferredActivity(admin, home, alias)
                } catch (e: Exception) {
                    // Half done is worse than not done: an enabled alias with no preference set would
                    // look finished on the next sync and never be retried. Undo it so it is.
                    pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                    throw e
                }
            } else if (!on && enabled) {
                dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Brick home screen not changed")
            false
        }
    }

    /** Alarm clock apps: left alone even by the brick, so a morning alarm still rings. */
    internal fun alarmApps(ctx: Context): Set<String> {
        val result = mutableSetOf<String>()
        for (action in listOf(android.provider.AlarmClock.ACTION_SHOW_ALARMS, android.provider.AlarmClock.ACTION_SET_ALARM)) {
            try {
                ctx.packageManager.queryIntentActivities(Intent(action), 0).forEach { result += it.activityInfo.packageName }
            } catch (_: Exception) { }
        }
        return result
    }
}
