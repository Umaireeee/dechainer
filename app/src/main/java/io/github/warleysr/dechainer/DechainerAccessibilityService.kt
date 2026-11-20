package io.github.warleysr.dechainer

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.warleysr.dechainer.activities.AccessibilityRequestActivity
import io.github.warleysr.dechainer.activities.OutsideTimeWindowActivity
import io.github.warleysr.dechainer.activities.ReopeningLimitActivity
import io.github.warleysr.dechainer.activities.TimeUpActivity
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.AppGroupRepository
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.AppTimeLimits
import io.github.warleysr.dechainer.data.AppTimeWindows
import io.github.warleysr.dechainer.data.BlockSuspensionManager
import io.github.warleysr.dechainer.data.DailyUsageStore
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.DowntimeRepository
import io.github.warleysr.dechainer.data.MusicLimitTracker
import io.github.warleysr.dechainer.data.SuspensionCoordinator
import io.github.warleysr.dechainer.data.SuspensionLogRepository
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.Span
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.utils.HomeScreenDetector
import timber.log.Timber
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Listens to window-state changes only (an app or screen coming to the front), never to content
 * changes, so it stays idle while an app is in use. Everything it does hangs off that one event:
 * per-app/group time limits, allowed time windows, the reopen cooldown, blocked screens and the
 * impulse-lock suspensions.
 */
@SuppressLint("AccessibilityPolicy")
class DechainerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage: String? = null
    private var sessionStartTime: Long = 0
    private var sessionDate: LocalDate = LocalDate.now()
    private val lastClosedTimes = HashMap<String, Long>()

    // Apps with a reopen interval that lost the screen, keyed to when. Leaving only counts as closing once
    // the home screen shows; coming back first (a popup, recents, an app opened from a notification) cancels it.
    private val pendingLeaves = HashMap<String, Long>()
    private val homeScreenDetector by lazy { HomeScreenDetector(this) }

    private lateinit var limitPrefs: SharedPreferences
    private lateinit var weeklyLimitPrefs: SharedPreferences
    private lateinit var reopenPrefs: SharedPreferences
    private lateinit var timeWindowPrefs: SharedPreferences
    private lateinit var groupsPrefs: SharedPreferences
    private lateinit var securityPrefs: SharedPreferences

    private var receiversRegistered = false
    private var debugReceiver: BroadcastReceiver? = null

    // The impulse block suspends its whole app list as one unit.
    private var impulseReleaseRunnable: Runnable? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
            val packageName = intent.data?.encodedSchemeSpecificPart ?: return
            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                AppRepository.reapplyHiddenIfNeeded(packageName)
            }
        }
    }

    private var lastForegroundPackage: String? = null

    private fun restoreForegroundAfterUnlock() {
        if (currentPackage != null) return
        val restored = lastForegroundPackage ?: return
        lastForegroundPackage = null
        currentPackage = restored
        foregroundPackage = restored
        sessionStartTime = SystemClock.elapsedRealtime()
        sessionDate = LocalDate.now()
        MusicLimitTracker.onForegroundChanged(applicationContext)
        checkDateReset()
        startTracking(restored)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (currentPackage != null) lastForegroundPackage = currentPackage
                    stopTrackingAndSave(screenOff = true)
                    currentPackage = null
                    foregroundPackage = null
                    MusicLimitTracker.onForegroundChanged(applicationContext)
                }

                Intent.ACTION_USER_PRESENT -> restoreForegroundAfterUnlock()

                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(KeyguardManager::class.java)
                    if (km == null || !km.isKeyguardLocked) {
                        restoreForegroundAfterUnlock()
                    }
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (prefs) {
            limitPrefs, weeklyLimitPrefs -> {
                BlockSuspensionManager.sync(applicationContext)
                MusicLimitTracker.onLimitsChanged(applicationContext)
                if (key == currentPackage) retrackCurrent()
            }

            timeWindowPrefs -> {
                BlockSuspensionManager.sync(applicationContext)
                MusicLimitTracker.onLimitsChanged(applicationContext)
                if (key == currentPackage) retrackCurrent()
            }

            groupsPrefs -> {
                BlockSuspensionManager.sync(applicationContext)
                MusicLimitTracker.onLimitsChanged(applicationContext)
                retrackCurrent()
            }

            securityPrefs -> {
                if (key == "impulse_block_active" ||
                    key == "impulse_block_start_elapsed" ||
                    key == "impulse_suspended_apps" ||
                    key == "impulse_action"
                ) {
                    syncImpulseSuspension()
                }
            }
        }
    }

    // Re-checks rather than blocking outright: the day may have changed, and usage must be saved first.
    private val blockRunnable = Runnable { recheckWindows() }

    // The allowed span ending doesn't always mean "blocked": the next one may start right away
    // (midnight, or two windows that touch), so the whole check runs again.
    private val windowEndRunnable = Runnable { recheckWindows() }

    // Saves the running session every few minutes so a killed service loses at most that much usage.
    // Only scheduled while a limited app is open, so it adds no wake-ups the rest of the time.
    private val checkpointRunnable = object : Runnable {
        override fun run() {
            val rolledOver = saveSessionUsage()
            // A session can run past midnight: the day's usage starts again, so re-arm the limit timer.
            if (rolledOver || checkDateReset()) currentPackage?.let { pkg ->
                handler.removeCallbacks(blockRunnable)
                handler.removeCallbacks(windowEndRunnable)
                startTracking(pkg)
            }
            // Removing first leaves exactly one pending checkpoint, even if startTracking just scheduled one.
            handler.removeCallbacks(this)
            if (currentPackage != null) handler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    companion object {
        const val ACTION_IMPULSE_RELEASE = "io.github.warleysr.dechainer.IMPULSE_RELEASE"
        private const val IMPULSE_ALARM_REQUEST = 7309
        private const val KEY_SERVICE_ADDED_DISALLOW_INSTALL = "service_added_disallow_install_apps"

        /** The app in front right now, or null while the screen is off. Read by [MusicLimitTracker]. */
        @Volatile
        var foregroundPackage: String? = null

        private val CHECKPOINT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

        var isRunning by mutableStateOf(false)
            private set

        var disablingService = false
            private set

        fun prepareServiceDisable() { disablingService = true }

        val accessedActivities = mutableStateListOf<ActivityLog>()

        data class ActivityLog(
            val packageName: String,
            val className: String,
            val timestamp: Long = System.currentTimeMillis()
        )

        private fun addLog(packageName: String, className: String) {
            if (accessedActivities.any { it.packageName == packageName && it.className == className }) {
                accessedActivities.removeIf { it.packageName == packageName && it.className == className }
            }
            accessedActivities.add(0, ActivityLog(packageName, className))
            if (accessedActivities.size > 100) accessedActivities.removeAt(accessedActivities.lastIndex)
        }

        private fun impulseAlarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            IMPULSE_ALARM_REQUEST,
            Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_IMPULSE_RELEASE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun cancelImpulseAlarm(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarms.cancel(impulseAlarmIntent(context))
        }

        private fun scheduleImpulseAlarm(context: Context, remainingMillis: Long) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + remainingMillis.coerceAtLeast(1000L)
            val pending = impulseAlarmIntent(context)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
        }

        fun releaseImpulseSuspensionIfExpired(context: Context) {
            val ctx = context.applicationContext
            if (SecurityManager.getImpulseBlockRemainingTime(ctx) > 0L) return
            synchronized(SuspensionCoordinator.lock) {
                cancelImpulseAlarm(ctx)
                val prefs = ctx.getSharedPreferences("security_prefs", MODE_PRIVATE)
                val suspended = prefs.getStringSet("impulse_active_suspension", emptySet()) ?: emptySet()
                if (prefs.getBoolean("impulse_block_active", false)) {
                    prefs.edit { putBoolean("impulse_block_active", false) }
                }
                if (suspended.isEmpty()) return

                val downtimeActive = DowntimeManager.activePackages(ctx)
                val keepForDowntime = suspended intersect downtimeActive
                DowntimeRepository.addHeld(ctx, keepForDowntime)

                val releasable = suspended -
                    DowntimeManager.heldPackages(ctx) -
                    downtimeActive -
                    BlockSuspensionManager.heldPackages(ctx) -
                    SuspensionCoordinator.manualSuspensions(ctx)

                if (releasable.isNotEmpty()) {
                    val dpm = ctx.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                        runCatching { dpm.setPackagesSuspended(admin, releasable.toTypedArray(), false) }
                        AppRepository.invalidateCache()
                    }
                }
                SecurityManager.clearActiveImpulseSuspension(ctx)
                BlockSuspensionManager.sync(ctx)
                Timber.d("Impulse block: released ${releasable.size} app(s)")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        disablingService = false
        // The service is on and the user wants it on: from here on the watchdog keeps it that way.
        AccessibilityWatchdog.setWanted(applicationContext, true)
        AccessibilityWatchdog.schedulePeriodic(applicationContext)

        limitPrefs = getSharedPreferences(AppTimeLimits.PREFS_NAME, MODE_PRIVATE)
        weeklyLimitPrefs = getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, MODE_PRIVATE)
        reopenPrefs = getSharedPreferences("reopen_times", MODE_PRIVATE)
        timeWindowPrefs = getSharedPreferences(AppTimeWindows.PREFS_NAME, MODE_PRIVATE)
        groupsPrefs = getSharedPreferences(AppGroupRepository.PREFS_NAME, MODE_PRIVATE)
        securityPrefs = getSharedPreferences("security_prefs", MODE_PRIVATE)

        limitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        weeklyLimitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        timeWindowPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        groupsPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        securityPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        synchronized(SuspensionCoordinator.lock) {
            val blockHeld = BlockSuspensionManager.heldPackages(applicationContext)
            val manual = SuspensionCoordinator.manualSuspensions(applicationContext)
            val downtimeHeld = DowntimeManager.heldPackages(applicationContext)
            val downtimeActive = DowntimeManager.activePackages(applicationContext)
            val impulseActive = SecurityManager.getActiveImpulseSuspension(applicationContext)
            val toRelease = getControlledPackages().filterNot {
                it in blockHeld || it in manual || it in downtimeHeld || it in downtimeActive || it in impulseActive
            }.toTypedArray()
            if (toRelease.isNotEmpty()) suspendPackages(toRelease, false)
        }

        // Must run after the un-suspend above, which would otherwise release a package that is
        // still serving an impulse block.
        syncImpulseSuspension()
        // DowntimeManager.sync also reconciles BlockSuspensionManager.
        DowntimeManager.sync(applicationContext)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName) &&
            securityPrefs.getBoolean(KEY_SERVICE_ADDED_DISALLOW_INSTALL, false)
        ) {
            runCatching {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                securityPrefs.edit { remove(KEY_SERVICE_ADDED_DISALLOW_INSTALL) }
            }.onFailure { Timber.e(it, "Could not clear service-added DISALLOW_INSTALL_APPS") }
        }
        ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(applicationContext)

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        })
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        receiversRegistered = true

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("io.github.warleysr.dechainer.DEBUG_INSTALL")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    disablingService = true
                    AccessibilityWatchdog.setWanted(context.applicationContext, false)
                    context.getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
                        putBoolean(SecurityManager.DEBUG_AUTO_START_SESSION_KEY, true)
                    }
                    SecurityManager.suspendUnknownSourcesRestrictionForDebugInstall(context)
                    disableSelf()
                }
            }
            debugReceiver = receiver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (receiversRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            runCatching { unregisterReceiver(screenReceiver) }
            receiversRegistered = false
        }
        debugReceiver?.let {
            runCatching { unregisterReceiver(it) }
            debugReceiver = null
        }
        if (::limitPrefs.isInitialized) limitPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::weeklyLimitPrefs.isInitialized) weeklyLimitPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::timeWindowPrefs.isInitialized) timeWindowPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::groupsPrefs.isInitialized) groupsPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::securityPrefs.isInitialized) securityPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null
        stopTrackingAndSave()
        currentPackage = null
        foregroundPackage = null
        MusicLimitTracker.onForegroundChanged(applicationContext)
        isRunning = false

        if (!disablingService) {
            if (::limitPrefs.isInitialized && ::weeklyLimitPrefs.isInitialized &&
                ::reopenPrefs.isInitialized && ::timeWindowPrefs.isInitialized
            ) {
                suspendPackages(getControlledPackages(), reason = SuspensionReason.ACCESSIBILITY_DISABLED)
            }

            // Switched off outside the app: ask for it to be brought back.
            AccessibilityWatchdog.scheduleSoon(applicationContext)

            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                runCatching {
                    val alreadyRestricted = dpm.getUserRestrictions(admin)
                        .getBoolean(UserManager.DISALLOW_INSTALL_APPS)
                    if (!alreadyRestricted) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                        if (::securityPrefs.isInitialized) {
                            securityPrefs.edit { putBoolean(KEY_SERVICE_ADDED_DISALLOW_INSTALL, true) }
                        }
                    }
                }.onFailure { Timber.e(it, "Could not add DISALLOW_INSTALL_APPS on unbind") }
            }

            startActivity(
                Intent(this@DechainerAccessibilityService, AccessibilityRequestActivity::class.java)
                    .apply { flags = FLAG_ACTIVITY_NEW_TASK }
            )
        }

        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        stopTrackingAndSave()
        currentPackage = null
        foregroundPackage = null
        MusicLimitTracker.onForegroundChanged(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName == packageName) {
            if (currentPackage != null) {
                stopTrackingAndSave()
                currentPackage = null
                foregroundPackage = null
                MusicLimitTracker.onForegroundChanged(applicationContext)
            }
            return
        }

        val source = event.source
        val windowType = try {
            source?.window?.type
        } finally {
            @Suppress("DEPRECATION")
            runCatching { source?.recycle() }
        }
        if (windowType != null && windowType != AccessibilityWindowInfo.TYPE_APPLICATION) return

        val newPackage = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (newPackage == "com.android.systemui") return
        if (className.contains("InputMethodService", ignoreCase = true)
            || className.contains("SoftInputWindow", ignoreCase = true)
        ) return

        if (newPackage != currentPackage) {
            stopTrackingAndSave()
            pendingLeaves.remove(newPackage)
            currentPackage = newPackage
            foregroundPackage = newPackage
            MusicLimitTracker.onForegroundChanged(applicationContext)
            sessionStartTime = SystemClock.elapsedRealtime()
            sessionDate = LocalDate.now()
            checkDateReset()
            startTracking(newPackage)
            enforceSplitScreenWindows()
        }

        // Checked on every launcher event, not just on a package change: going from recents to home stays in the launcher.
        if (pendingLeaves.isNotEmpty() && homeScreenDetector.isHomeScreen(event, rootInActiveWindow)) {
            pendingLeaves.forEach { (pkg, leftAt) -> lastClosedTimes[pkg] = leftAt }
            pendingLeaves.clear()
        }

        // Every screen is logged, not only classes named "...Activity": Settings pages are often
        // "...SubSettings", so the blocker has to see them too.
        addLog(newPackage, className)
        val blockedActivities = getSharedPreferences("activity_blocker_prefs", MODE_PRIVATE)
            .getStringSet("blocked_activities", emptySet()) ?: emptySet()

        if (blockedActivities.contains(className))
            performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Enforces schedules/exhausted limits on secondary visible windows (split-screen / PiP). */
    private fun enforceSplitScreenWindows() {
        val visible = runCatching {
            windows.orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { win ->
                    val root = win.root ?: return@mapNotNull null
                    try {
                        root.packageName?.toString()
                    } finally {
                        @Suppress("DEPRECATION")
                        runCatching { root.recycle() }
                    }
                }
                .toSet()
        }.getOrDefault(emptySet())

        for (pkg in visible) {
            if (pkg == currentPackage || pkg == packageName || pkg == "com.android.systemui") continue
            val config = trackingConfigFor(pkg)
            if (config.allowed != null) {
                val nowSecond = currentSecondOfDay()
                val activeSpan = config.allowed.firstOrNull { nowSecond in it.start * 60 until it.end * 60 }
                if (activeSpan == null) {
                    executeBlockingFor(pkg, outsideWindow = true)
                    continue
                }
            }
            val appExhausted = config.appLimitMinutes > 0 &&
                DailyUsageStore.getAppUsageMillis(applicationContext, pkg) >=
                TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong())
            val groupExhausted = config.group != null && config.groupLimitMinutes > 0 &&
                DailyUsageStore.getGroupUsageMillis(applicationContext, config.group.id) >=
                TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong())
            if (appExhausted || groupExhausted) {
                executeBlockingFor(pkg, outsideWindow = false)
            }
        }
    }

    /**
     * Brings the app suspensions in line with the current state of the impulse block: suspends the
     * configured list while a block is running with [SecurityManager.ImpulseAction.TIMER_AND_SUSPEND]
     * selected, and releases whatever was suspended once it isn't.
     *
     * Called both from the prefs listener (the panic button, and the block expiring) and from
     * [onServiceConnected], which is what re-applies a block that outlived this service.
     */
    private fun syncImpulseSuspension() {
        synchronized(SuspensionCoordinator.lock) {
            val ctx = applicationContext
            val remaining = SecurityManager.getImpulseBlockRemainingTime(ctx)
            val targets = SecurityManager.getImpulseSuspendedApps(ctx)
            val shouldSuspend = remaining > 0 &&
                SecurityManager.getImpulseAction(ctx) == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND &&
                targets.isNotEmpty()

            if (!shouldSuspend) {
                releaseImpulseSuspension()
                return
            }

            // Leave apps held by downtime, blocks, or manual suspension untouched when dropping items.
            val alreadySuspended = SecurityManager.getActiveImpulseSuspension(ctx)
            val dropped = (alreadySuspended - targets) -
                DowntimeManager.heldPackages(ctx) -
                DowntimeManager.activePackages(ctx) -
                BlockSuspensionManager.heldPackages(ctx) -
                SuspensionCoordinator.manualSuspensions(ctx)
            if (dropped.isNotEmpty()) suspendPackages(dropped.toTypedArray(), false)

            suspendPackages(targets.toTypedArray(), true, reason = SuspensionReason.IMPULSE_LOCK)
            SecurityManager.setActiveImpulseSuspension(ctx, targets)

            impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
            val release = Runnable {
                impulseReleaseRunnable = null
                releaseImpulseSuspension()
            }
            impulseReleaseRunnable = release
            handler.postDelayed(release, remaining)
            scheduleImpulseAlarm(ctx, remaining)
            Timber.d("Impulse block: suspended ${targets.size} app(s) for ${remaining}ms")
        }
    }

    private fun releaseImpulseSuspension() {
        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null
        releaseImpulseSuspensionIfExpired(applicationContext)
    }

    private fun suspendPackages(
        packages: Array<String>,
        suspend: Boolean = true,
        reason: SuspensionReason? = null,
        details: String? = null
    ) {
        if (packages.isEmpty()) return
        val dpm =
            applicationContext.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(applicationContext, DechainerDeviceAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val failed = runCatching { dpm.setPackagesSuspended(admin, packages, suspend).toSet() }
            .onFailure { Timber.e(it, "setPackagesSuspended failed") }
            .getOrElse { return }
        val succeeded = packages.filterNot { it in failed }
        if (succeeded.isNotEmpty()) {
            AppRepository.invalidateCache()
        }
        if (suspend && reason != null) {
            succeeded.forEach { pkg ->
                SuspensionLogRepository.log(applicationContext, pkg, reason, details)
            }
        }
    }

    private fun suspendPackage(
        packageName: String,
        suspend: Boolean = true,
        reason: SuspensionReason? = null,
        details: String? = null
    ) = suspendPackages(
        arrayOf(packageName), suspend, reason, details
    )

    private fun getControlledPackages(): Array<String> {
        val restrictedGroupPackages = AppGroupRepository.getGroups()
            .filter { it.timeLimit.isSet || it.timeWindows.isNotEmpty() }
            .flatMap { it.packageNames }

        return limitPrefs.all.keys
            .union(weeklyLimitPrefs.all.keys)
            .union(reopenPrefs.all.keys)
            .union(timeWindowPrefs.all.keys)
            .union(restrictedGroupPackages)
            .toTypedArray()
    }

    /**
     * The app's own settings plus its group's, when it belongs to one. A group does not replace
     * the app's individual limit/windows — it adds another cap alongside them, and whichever of
     * the two is more restrictive is the one that actually applies (see [startTracking]).
     * Limits that vary by weekday are resolved to today's value here.
     */
    private data class TrackingConfig(
        val group: AppGroup?,
        val appLimitMinutes: Int,
        val groupLimitMinutes: Int,
        val hasAnyVaryingOrSetLimit: Boolean,
        /** What is allowed today, or null when neither the app nor its group has windows. */
        val allowed: List<Span>?
    )

    private fun trackingConfigFor(pkg: String): TrackingConfig {
        val group = AppGroupRepository.getGroupForPackage(pkg)
        val appWindows = AppTimeWindows.decode(timeWindowPrefs.getString(pkg, null))
        val today = LocalDate.now().dayOfWeek.value
        val appLimit = AppTimeLimits.read(limitPrefs, weeklyLimitPrefs, pkg)
        val appLimitMinutes = appLimit.todayMinutes()
        val hasAnyLimit = appLimit.isSet || (group?.timeLimit?.isSet == true)
        return if (group != null) {
            TrackingConfig(
                group = group,
                appLimitMinutes = appLimitMinutes,
                groupLimitMinutes = group.timeLimit.todayMinutes(),
                hasAnyVaryingOrSetLimit = hasAnyLimit,
                allowed = TimeWindow.allowedSpans(listOf(group.timeWindows, appWindows), today)
            )
        } else {
            TrackingConfig(
                group = null,
                appLimitMinutes = appLimitMinutes,
                groupLimitMinutes = 0,
                hasAnyVaryingOrSetLimit = hasAnyLimit,
                allowed = TimeWindow.allowedSpans(listOf(appWindows), today)
            )
        }
    }

    private fun millisUntilNextMidnight(): Long {
        val now = ZonedDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, midnight).toMillis().coerceAtLeast(1000L)
    }

    private fun startTracking(pkg: String) {
        val config = trackingConfigFor(pkg)

        if (config.allowed != null) {
            val nowSecond = currentSecondOfDay()
            val activeSpan = config.allowed.firstOrNull { nowSecond in it.start * 60 until it.end * 60 }
            if (activeSpan == null) {
                executeBlocking(outsideWindow = true)
                return
            }
            handler.postDelayed(
                windowEndRunnable,
                ((activeSpan.end * 60 - nowSecond) * 1000L).coerceAtLeast(1000L)
            )
        }

        val remainingSecondsReopening = getRemainingSecondsToReopen(pkg)
        if (config.appLimitMinutes <= 0 && config.groupLimitMinutes <= 0) {
            if (config.hasAnyVaryingOrSetLimit) {
                // Keep watching across midnight so a weekday-varying limit activates at 00:00.
                handler.postDelayed(windowEndRunnable, millisUntilNextMidnight())
            }
            if (remainingSecondsReopening > 0) {
                executeBlocking(reopening = true, remainingSeconds = remainingSecondsReopening)
            }
            return
        }

        var appRemaining: Long? = null
        var groupRemaining: Long? = null
        var limitReached = false

        if (config.appLimitMinutes > 0) {
            val remaining = TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong()) -
                DailyUsageStore.getAppUsageMillis(applicationContext, pkg)
            if (remaining <= 0) limitReached = true else appRemaining = remaining
        }

        if (config.group != null && config.groupLimitMinutes > 0) {
            val remaining = TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong()) -
                DailyUsageStore.getGroupUsageMillis(applicationContext, config.group.id)
            if (remaining <= 0) limitReached = true else groupRemaining = remaining
        }

        if (limitReached) {
            executeBlocking()
            return
        }
        // One timer per open app: no polling while the app is in use.
        val remainingMillis = listOfNotNull(appRemaining, groupRemaining).minOrNull()
        if (remainingMillis != null) {
            handler.postDelayed(blockRunnable, remainingMillis)
            handler.postDelayed(checkpointRunnable, CHECKPOINT_INTERVAL_MS)
        }

        if (remainingSecondsReopening > 0)
            executeBlocking(reopening = true, remainingSeconds = remainingSecondsReopening)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun stopTrackingAndSave(screenOff: Boolean = false) {
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
        handler.removeCallbacks(checkpointRunnable)
        val pkg = currentPackage ?: return
        if (!screenOff && reopenPrefs.getInt(pkg, 0) > 0 && getRemainingSecondsToReopen(pkg) == 0)
            pendingLeaves[pkg] = SystemClock.elapsedRealtime()

        saveSessionUsage()
        sessionStartTime = 0
    }

    /** Adds the time since the session (or the last checkpoint) started to the app's and its group's usage. */
    private fun saveSessionUsage(): Boolean {
        val pkg = currentPackage ?: return false
        if (sessionStartTime == 0L) return false

        val config = trackingConfigFor(pkg)
        val now = SystemClock.elapsedRealtime()
        val today = LocalDate.now()
        var rolledOver = false
        if (today.isAfter(sessionDate)) {
            // Roll over the day cleanly before accruing post-midnight usage.
            val millisSinceMidnight = LocalTime.now().toSecondOfDay() * 1000L
            val midnightElapsed = (now - millisSinceMidnight).coerceIn(sessionStartTime, now)
            DailyUsageStore.ensureCurrentDate(applicationContext, today)
            sessionStartTime = midnightElapsed
            sessionDate = today
            rolledOver = true
        }

        val currentSessionMillis = (now - sessionStartTime).coerceAtLeast(0L)
        if (config.appLimitMinutes > 0) {
            DailyUsageStore.addAppUsageMillis(applicationContext, pkg, currentSessionMillis)
        }
        if (config.group != null && config.groupLimitMinutes > 0) {
            DailyUsageStore.addGroupUsageMillis(applicationContext, config.group.id, currentSessionMillis)
        }

        sessionStartTime = now
        return rolledOver
    }

    private fun executeBlocking(reopening: Boolean = false, remainingSeconds: Int = 0, outsideWindow: Boolean = false) {
        val pkg = currentPackage ?: return
        stopTrackingAndSave()
        currentPackage = null
        foregroundPackage = null
        MusicLimitTracker.onForegroundChanged(applicationContext)
        executeBlockingFor(pkg, reopening, remainingSeconds, outsideWindow)
    }

    private fun executeBlockingFor(
        pkg: String,
        reopening: Boolean = false,
        remainingSeconds: Int = 0,
        outsideWindow: Boolean = false
    ) {
        val config = trackingConfigFor(pkg)
        val individualAppName = appLabel(pkg)

        // For a plain limit block, name and blame whichever cap actually ran out: the app's own,
        // or (when both are exhausted) the shared group cap, since that's the one still binding.
        var displayName = individualAppName
        var displayLimit = config.appLimitMinutes
        if (!reopening && !outsideWindow && config.group != null && config.groupLimitMinutes > 0) {
            val groupReached = DailyUsageStore.getGroupUsageMillis(applicationContext, config.group.id) >=
                TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong())
            val appReached = config.appLimitMinutes > 0 &&
                DailyUsageStore.getAppUsageMillis(applicationContext, pkg) >=
                TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong())
            if (groupReached && !appReached) {
                displayName = config.group.name
                displayLimit = config.groupLimitMinutes
            }
        }

        // Schedule and time-limit blocks suspend the app silently unless suspension fails/non-DO.
        if (!reopening) {
            val suspended = BlockSuspensionManager.suspend(applicationContext, pkg, outsideWindow)
            if (suspended) return
        }

        val activityClass = when {
            outsideWindow -> OutsideTimeWindowActivity::class.java
            reopening -> ReopeningLimitActivity::class.java
            else -> TimeUpActivity::class.java
        }

        val intent = Intent(this, activityClass).apply {
            flags = FLAG_ACTIVITY_NEW_TASK
            putExtra("appName", displayName)
        }

        if (outsideWindow) {
            // Empty when nothing is allowed today.
            intent.putStringArrayListExtra("windows", ArrayList(config.allowed.orEmpty().map { it.formatted() }))
        } else {
            intent.putExtra("limit", if (reopening) remainingSeconds else displayLimit)
        }

        startActivity(intent)
    }

    private fun currentSecondOfDay(): Int = LocalTime.now().toSecondOfDay()

    private fun recheckWindows() {
        val pkg = currentPackage ?: return
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
        handler.removeCallbacks(checkpointRunnable)
        saveSessionUsage()
        checkDateReset()
        startTracking(pkg)
    }

    /** Clears the day's usage when the date has changed. Returns true when it did. */
    private fun checkDateReset(): Boolean = DailyUsageStore.ensureCurrentDate(applicationContext)

    /**
     * Re-applies the open app's limits after their settings change. Time already used in this
     * stretch is saved first, so the new timer counts from the right place.
     */
    private fun retrackCurrent() {
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
        handler.removeCallbacks(checkpointRunnable)
        saveSessionUsage()
        checkDateReset()
        currentPackage?.let { startTracking(it) }
    }

    private fun getRemainingSecondsToReopen(pkg: String): Int {
        val reopenSeconds = reopenPrefs.getInt(pkg, 0)
        val lastClosedTime = lastClosedTimes.getOrDefault(pkg, 0L)
        if (reopenSeconds > 0 && lastClosedTime > 0) {
            val elapsed = SystemClock.elapsedRealtime() - lastClosedTime
            val elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
            if (elapsedSeconds <= reopenSeconds)
                return max(reopenSeconds - elapsedSeconds.toInt(), 0)
        }
        return 0
    }

}
