package io.github.warleysr.dechainer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
import io.github.warleysr.dechainer.data.BrowserHardeningConfig
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.ContentBlockerEngine
import io.github.warleysr.dechainer.data.DailyUsageStore
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.DowntimeRepository
import io.github.warleysr.dechainer.data.InstallRestrictionCoordinator
import io.github.warleysr.dechainer.data.MusicLimitTracker
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
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
 * Monitors window-state changes for usage limits, time windows, reopen cooldowns, blocked screens,
 * and impulse-lock suspensions, and dynamically enables window-content / scroll / click inspection
 * only when browser DNS lock, URL blocking, or social short-form rules are active.
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
    private lateinit var activityBlockerPrefs: SharedPreferences
    private lateinit var browserHardeningPrefs: SharedPreferences

    @Volatile
    private var cachedBlockedActivities: Set<String> = emptySet()

    @Volatile
    private var cachedBrowserConfig: BrowserHardeningConfig = BrowserHardeningConfig()

    @Volatile
    private var cachedBrowserPackages: Set<String> = ContentBlockerEngine.KNOWN_BROWSER_PACKAGES

    @Volatile
    private var cachedWatchedPackages: Set<String> = emptySet()

    private var lastContentCheckElapsed: Long = 0L
    private var pendingContentCheckPkg: String? = null
    private var pendingContentCheckRunnable: Runnable? = null
    private var postBackVerifyRunnable: Runnable? = null

    private var lastEnforcementElapsed: Long = 0L
    private var lastEnforcedRuleKey: String? = null
    private var consecutiveRuleHits: Int = 0
    private val lastClassNameByPackage = HashMap<String, String>()

    private var receiversRegistered = false
    private var debugReceiver: BroadcastReceiver? = null

    // The impulse block suspends its whole app list as one unit.
    private var impulseReleaseRunnable: Runnable? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    homeScreenDetector.invalidateCache()
                    cachedBrowserPackages = BrowserHardeningManager.getInstalledBrowserPackages(applicationContext)
                    refreshContentBlockerState()
                }
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
                    lastForegroundPackage = currentPackage
                    stopTrackingAndSave(screenOff = true)
                    currentPackage = null
                    foregroundPackage = null
                    MusicLimitTracker.onForegroundChanged(applicationContext)
                }

                Intent.ACTION_USER_PRESENT -> {
                    restoreForegroundAfterUnlock()
                }

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
                handler.post {
                    BlockSuspensionManager.sync(applicationContext)
                    MusicLimitTracker.onLimitsChanged(applicationContext)
                    if (key == currentPackage) retrackCurrent()
                }
            }

            timeWindowPrefs -> {
                handler.post {
                    BlockSuspensionManager.sync(applicationContext)
                    MusicLimitTracker.onLimitsChanged(applicationContext)
                    if (key == currentPackage) retrackCurrent()
                }
            }

            groupsPrefs -> {
                // Post onto handler so we never acquire SuspensionCoordinator.lock while holding AppGroupRepository.lock.
                handler.post {
                    BlockSuspensionManager.sync(applicationContext)
                    MusicLimitTracker.onLimitsChanged(applicationContext)
                    retrackCurrent()
                }
            }

            securityPrefs -> {
                if (key == "impulse_block_active" ||
                    key == "impulse_suspended_apps" ||
                    key == "impulse_action"
                ) {
                    handler.post { syncImpulseSuspension() }
                }
            }

            activityBlockerPrefs -> {
                cachedBlockedActivities = activityBlockerPrefs.getStringSet("blocked_activities", emptySet())
                    ?.toSet().orEmpty()
            }

            browserHardeningPrefs -> {
                refreshContentBlockerState()
            }
        }
    }

    private val blockRunnable = Runnable { recheckWindows() }
    private val windowEndRunnable = Runnable { recheckWindows() }

    private val checkpointRunnable = object : Runnable {
        override fun run() {
            val rolledOver = saveSessionUsage()
            if (rolledOver || checkDateReset()) currentPackage?.let { pkg ->
                handler.removeCallbacks(blockRunnable)
                handler.removeCallbacks(windowEndRunnable)
                startTracking(pkg)
            }
            handler.removeCallbacks(this)
            if (currentPackage != null) handler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    companion object {
        const val ACTION_IMPULSE_RELEASE = "io.github.warleysr.dechainer.IMPULSE_RELEASE"
        private const val IMPULSE_ALARM_REQUEST = 7309

        /** The app in front right now, or null while the screen is off. Read by [MusicLimitTracker]. */
        @Volatile
        var foregroundPackage: String? = null

        private val CHECKPOINT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
        private const val CONTENT_CHECK_THROTTLE_MS = 250L
        private const val ENFORCEMENT_COOLDOWN_MS = 550L
        private const val POST_BACK_VERIFY_DELAY_MS = ENFORCEMENT_COOLDOWN_MS + 120L
        private const val RULE_ESCALATION_WINDOW_MS = 3_000L
        private const val MAX_WALK_NODES = 350
        private const val MAX_WALK_DEPTH = 18

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

        fun releaseImpulseSuspensionIfExpired(context: Context, force: Boolean = false) {
            val ctx = context.applicationContext
            val remaining = SecurityManager.getImpulseBlockRemainingTime(ctx)
            if (!force && remaining > 0L) {
                val suspended = ctx.getSharedPreferences("security_prefs", MODE_PRIVATE)
                    .getStringSet("impulse_active_suspension", emptySet()).orEmpty()
                if (suspended.isNotEmpty()) {
                    scheduleImpulseAlarm(ctx, remaining)
                }
                return
            }
            synchronized(SuspensionCoordinator.lock) {
                cancelImpulseAlarm(ctx)
                val prefs = ctx.getSharedPreferences("security_prefs", MODE_PRIVATE)
                val suspended = prefs.getStringSet("impulse_active_suspension", emptySet()) ?: emptySet()
                if (!force && prefs.getBoolean("impulse_block_active", false)) {
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
        if (!AccessibilityWatchdog.isIntentionalDisableActive(applicationContext)) {
            AccessibilityWatchdog.setWanted(applicationContext, true)
        }
        AccessibilityWatchdog.schedulePeriodic(applicationContext)

        limitPrefs = getSharedPreferences(AppTimeLimits.PREFS_NAME, MODE_PRIVATE)
        weeklyLimitPrefs = getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, MODE_PRIVATE)
        reopenPrefs = getSharedPreferences("reopen_times", MODE_PRIVATE)
        timeWindowPrefs = getSharedPreferences(AppTimeWindows.PREFS_NAME, MODE_PRIVATE)
        groupsPrefs = getSharedPreferences(AppGroupRepository.PREFS_NAME, MODE_PRIVATE)
        securityPrefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        activityBlockerPrefs = getSharedPreferences("activity_blocker_prefs", MODE_PRIVATE)
        cachedBlockedActivities = activityBlockerPrefs.getStringSet("blocked_activities", emptySet())
            ?.toSet().orEmpty()
        browserHardeningPrefs = getSharedPreferences(BrowserHardeningManager.PREFS, MODE_PRIVATE)
        cachedBrowserPackages = BrowserHardeningManager.getInstalledBrowserPackages(applicationContext)
        refreshContentBlockerState()

        limitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        weeklyLimitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        timeWindowPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        groupsPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        securityPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        activityBlockerPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        browserHardeningPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        synchronized(SuspensionCoordinator.lock) {
            val blockHeld = BlockSuspensionManager.heldPackages(applicationContext)
            val manual = SuspensionCoordinator.manualSuspensions(applicationContext)
            val downtimeHeld = DowntimeManager.heldPackages(applicationContext)
            val downtimeActive = DowntimeManager.activePackages(applicationContext)
            val impulseActive = SecurityManager.getActiveImpulseSuspension(applicationContext)
            val controlled = getControlledPackages()
            // Hand off any controlled app that entered Downtime while suspended by onUnbind.
            val handoffToDowntime = controlled.filter {
                it in downtimeActive &&
                    it !in downtimeHeld &&
                    it !in blockHeld &&
                    it !in manual &&
                    it !in impulseActive
            }.toSet()
            if (handoffToDowntime.isNotEmpty()) {
                DowntimeRepository.addHeld(applicationContext, handoffToDowntime)
            }
            val toRelease = controlled.filterNot {
                it in blockHeld || it in manual || it in downtimeHeld || it in downtimeActive || it in impulseActive
            }.toTypedArray()
            if (toRelease.isNotEmpty()) suspendPackages(toRelease, false)
        }

        syncImpulseSuspension()
        DowntimeManager.sync(applicationContext)
        PlayStoreUpdateManager.sync(applicationContext)

        InstallRestrictionCoordinator.releaseEnforceLease(
            applicationContext,
            InstallRestrictionCoordinator.LEASE_ACCESSIBILITY_UNBIND
        )
        ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(applicationContext)

        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiversRegistered = true

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("io.github.warleysr.dechainer.DEBUG_INSTALL")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    disablingService = true
                    AccessibilityWatchdog.setWanted(context.applicationContext, false)
                    SecurityManager.suspendUnknownSourcesRestrictionForDebugInstall(context)
                    disableSelf()
                }
            }
            debugReceiver = receiver
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
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
        if (::activityBlockerPrefs.isInitialized) activityBlockerPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::browserHardeningPrefs.isInitialized) browserHardeningPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

        pendingContentCheckRunnable?.let { handler.removeCallbacks(it) }
        pendingContentCheckRunnable = null
        pendingContentCheckPkg = null
        postBackVerifyRunnable?.let { handler.removeCallbacks(it) }
        postBackVerifyRunnable = null
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
                synchronized(SuspensionCoordinator.lock) {
                    suspendPackages(getControlledPackages(), reason = SuspensionReason.ACCESSIBILITY_DISABLED)
                }
            }

            if (AccessibilityWatchdog.hasPermission(applicationContext)) {
                handler.postDelayed({
                    AccessibilityWatchdog.ensureEnabled(applicationContext)
                }, 300L)
                AccessibilityWatchdog.scheduleSoon(applicationContext)
            } else {
                AccessibilityWatchdog.scheduleSoon(applicationContext)
            }

            InstallRestrictionCoordinator.acquireEnforceLease(
                applicationContext,
                InstallRestrictionCoordinator.LEASE_ACCESSIBILITY_UNBIND
            )

            if (!AccessibilityWatchdog.hasPermission(applicationContext)) {
                startActivity(
                    Intent(this@DechainerAccessibilityService, AccessibilityRequestActivity::class.java)
                        .apply { flags = FLAG_ACTIVITY_NEW_TASK }
                )
            }
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
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg !in cachedWatchedPackages) return
            scheduleTrailingContentCheck(pkg)
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        lastForegroundPackage = null

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
            val win = source?.window
            try {
                win?.type
            } finally {
                @Suppress("DEPRECATION")
                runCatching { win?.recycle() }
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { source?.recycle() }
        }
        if (windowType != null && windowType != AccessibilityWindowInfo.TYPE_APPLICATION) return

        val newPackage = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (newPackage == "com.android.systemui") return
        if (className.contains("InputMethodService", ignoreCase = true) ||
            className.contains("SoftInputWindow", ignoreCase = true)
        ) return

        if (PlayStoreUpdateManager.shouldBlockUiForPackage(applicationContext, newPackage)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

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

        if (pendingLeaves.isNotEmpty()) {
            val rootForHome = runCatching { rootInActiveWindow }.getOrNull()
            try {
                if (homeScreenDetector.isHomeScreen(event, rootForHome)) {
                    pendingLeaves.forEach { (pkg, leftAt) -> lastClosedTimes[pkg] = leftAt }
                    pendingLeaves.clear()
                }
            } finally {
                @Suppress("DEPRECATION")
                runCatching { rootForHome?.recycle() }
            }
        }

        addLog(newPackage, className)
        val scopedRule = "$newPackage/$className"
        if (cachedBlockedActivities.contains(scopedRule) || cachedBlockedActivities.contains(className)) {
            applyContentEnforcement(
                ContentBlockerEngine.BlockMatch(
                    rule = "blocked_activity:$scopedRule",
                    action = ContentBlockerEngine.EnforcementAction.PRESS_BACK
                ),
                verifyPkg = newPackage
            )
            return
        }

        if (!ContentBlockerEngine.isTransientViewClassName(className)) {
            lastClassNameByPackage[newPackage] = className
        }
        if (newPackage in cachedWatchedPackages) {
            pendingContentCheckRunnable?.let { handler.removeCallbacks(it) }
            pendingContentCheckRunnable = null
            lastContentCheckElapsed = SystemClock.elapsedRealtime()
            evaluateAndEnforceContentBlock(newPackage, lastClassNameByPackage[newPackage].orEmpty())
        }
    }

    private fun scheduleTrailingContentCheck(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val elapsedSince = now - lastContentCheckElapsed
        if (elapsedSince >= CONTENT_CHECK_THROTTLE_MS && pendingContentCheckRunnable == null) {
            lastContentCheckElapsed = now
            evaluateAndEnforceContentBlock(pkg, lastClassNameByPackage[pkg].orEmpty())
            return
        }
        // Trailing-edge debounce: ensure the final settled frame is always evaluated.
        pendingContentCheckPkg = pkg
        if (pendingContentCheckRunnable == null) {
            val delay = (CONTENT_CHECK_THROTTLE_MS - elapsedSince).coerceIn(30L, CONTENT_CHECK_THROTTLE_MS)
            val runnable = Runnable {
                pendingContentCheckRunnable = null
                val targetPkg = pendingContentCheckPkg ?: return@Runnable
                pendingContentCheckPkg = null
                lastContentCheckElapsed = SystemClock.elapsedRealtime()
                if (targetPkg in cachedWatchedPackages) {
                    evaluateAndEnforceContentBlock(targetPkg, lastClassNameByPackage[targetPkg].orEmpty())
                }
            }
            pendingContentCheckRunnable = runnable
            handler.postDelayed(runnable, delay)
        }
    }

    private fun refreshContentBlockerState() {
        val cfg = BrowserHardeningManager.getConfig(applicationContext)
        cachedBrowserConfig = cfg
        val watched = ContentBlockerEngine.activeWatchedPackages(cfg, cachedBrowserPackages)
        cachedWatchedPackages = watched
        runCatching {
            val info = serviceInfo ?: return
            val wantedEvents = if (watched.isEmpty()) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            } else {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            }
            var wantedFlags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            wantedFlags = if (watched.isEmpty()) {
                wantedFlags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            } else {
                wantedFlags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            if (info.eventTypes != wantedEvents || info.flags != wantedFlags) {
                info.eventTypes = wantedEvents
                info.flags = wantedFlags
                serviceInfo = info
            }
        }
    }

    private fun findWindowRootForPackage(pkg: String): AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val activePkg = active.packageName?.toString()
            if (activePkg == null || activePkg == pkg) return active
            @Suppress("DEPRECATION")
            runCatching { active.recycle() }
        }
        val appWindows = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        for (win in appWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == pkg) {
                return root
            }
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
        return null
    }

    private fun evaluateAndEnforceContentBlock(pkg: String, className: String) {
        val cfg = cachedBrowserConfig
        if (!cfg.enabled) return
        if (cfg.blockTikTok && pkg in ContentBlockerEngine.TIKTOK_PACKAGES) {
            applyContentEnforcement(
                ContentBlockerEngine.BlockMatch(
                    rule = "tiktok_app",
                    action = ContentBlockerEngine.EnforcementAction.GO_HOME
                ),
                verifyPkg = pkg
            )
            return
        }

        val root = findWindowRootForPackage(pkg) ?: return
        try {
            evaluateRootForContentBlock(root, pkg, className, cfg)
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    private fun evaluateRootForContentBlock(
        root: AccessibilityNodeInfo,
        pkg: String,
        className: String,
        cfg: BrowserHardeningConfig
    ): Boolean {
        val isBrowser = pkg in cachedBrowserPackages || pkg in ContentBlockerEngine.KNOWN_BROWSER_PACKAGES
        val urlBarText = if (isBrowser) readBrowserUrlBar(root, pkg) else null
        if (isBrowser && ContentBlockerEngine.isWebPageUrl(urlBarText) &&
            ContentBlockerEngine.isSettingsClassName(className)
        ) {
            lastClassNameByPackage.remove(pkg)
        }

        val matchedViewIds = findMatchedViewIds(
            root,
            ContentBlockerEngine.viewIdsToProbeFor(pkg, cfg, cachedBrowserPackages)
        )

        val isSocialTarget = (cfg.blockYouTubeShorts && pkg in ContentBlockerEngine.YOUTUBE_PACKAGES) ||
            ((cfg.blockInstagramReels || cfg.blockInstagramExplore) && pkg in ContentBlockerEngine.INSTAGRAM_PACKAGES) ||
            (cfg.blockFacebookReels && pkg in ContentBlockerEngine.FACEBOOK_PACKAGES) ||
            (cfg.blockSnapchatSpotlight && pkg in ContentBlockerEngine.SNAPCHAT_PACKAGES)
        val needsBrowserTextWalk = isBrowser &&
            cfg.lockBrowserDnsSettings &&
            !ContentBlockerEngine.isWebPageUrl(urlBarText)

        val selectedTabs = LinkedHashSet<String>()
        val visibleTexts = ArrayList<String>()
        val contentDescriptions = ArrayList<String>()
        if (matchedViewIds.isEmpty() && (isSocialTarget || needsBrowserTextWalk)) {
            collectShallowTextAndTabs(root, selectedTabs, visibleTexts, contentDescriptions)
        }

        val snapshot = ContentBlockerEngine.ScreenSnapshot(
            packageName = pkg,
            className = className,
            urlBarText = urlBarText,
            matchedViewIds = matchedViewIds,
            visibleTexts = visibleTexts,
            selectedTabLabels = selectedTabs,
            contentDescriptions = contentDescriptions
        )
        val match = ContentBlockerEngine.evaluate(snapshot, cfg, cachedBrowserPackages) ?: return false
        applyContentEnforcement(match, verifyPkg = pkg)
        return true
    }

    private fun readBrowserUrlBar(root: AccessibilityNodeInfo, pkg: String): String? {
        for (viewId in ContentBlockerEngine.browserUrlBarViewIds(pkg)) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull().orEmpty()
            var found: String? = null
            for (node in nodes) {
                if (found == null && node.isVisibleToUser) {
                    val candidate = node.text?.toString()?.trim()
                    val hint = node.hintText?.toString()?.trim()
                    if (!candidate.isNullOrEmpty() && candidate != hint) {
                        found = candidate
                    }
                }
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
            if (found != null) return found
        }
        return null
    }

    private fun hasNonEmptyScreenBounds(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return !rect.isEmpty && rect.width() > 4 && rect.height() > 4
    }

    private fun findMatchedViewIds(root: AccessibilityNodeInfo, viewIds: List<String>): Set<String> {
        if (viewIds.isEmpty()) return emptySet()
        val matched = LinkedHashSet<String>()
        for (viewId in viewIds) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull().orEmpty()
            var anyVisible = false
            for (node in nodes) {
                if (!anyVisible && (node.isVisibleToUser || hasNonEmptyScreenBounds(node))) {
                    anyVisible = true
                }
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
            if (anyVisible) matched.add(viewId)
        }
        return matched
    }

    private fun collectShallowTextAndTabs(
        root: AccessibilityNodeInfo,
        selectedTabs: MutableSet<String>,
        visibleTexts: MutableList<String>,
        contentDescriptions: MutableList<String>
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        var visited = 0

        fun recordNode(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
            if (text != null) visibleTexts.add(text)
            if (desc != null) contentDescriptions.add(desc)
            if (node.isSelected) {
                if (text != null) selectedTabs.add(text)
                if (desc != null) selectedTabs.add(desc)
            }
        }

        recordNode(root)
        visited = 1
        // Enqueue root's direct children from last to first so bottom nav bars are scanned first.
        val rootCount = root.childCount
        for (i in rootCount - 1 downTo 0) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            if (child.isVisibleToUser) {
                queue.addLast(child to 1)
            } else {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            try {
                if (visited >= MAX_WALK_NODES) continue
                visited += 1
                recordNode(node)

                if (depth < MAX_WALK_DEPTH && visited < MAX_WALK_NODES) {
                    val count = node.childCount
                    for (i in 0 until count) {
                        if (visited + queue.size >= MAX_WALK_NODES) break
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        if (child.isVisibleToUser) {
                            queue.addLast(child to (depth + 1))
                        } else {
                            @Suppress("DEPRECATION")
                            runCatching { child.recycle() }
                        }
                    }
                }
            } finally {
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
        }
    }

    private fun applyContentEnforcement(match: ContentBlockerEngine.BlockMatch, verifyPkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (match.action == ContentBlockerEngine.EnforcementAction.GO_HOME) {
            if (now - lastEnforcementElapsed < ENFORCEMENT_COOLDOWN_MS) return
            lastEnforcementElapsed = now
            lastEnforcedRuleKey = match.rule
            consecutiveRuleHits = 1
            Timber.d("Content blocker GO_HOME for rule=%s", match.rule)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (now - lastEnforcementElapsed < ENFORCEMENT_COOLDOWN_MS) return
        if (lastEnforcedRuleKey == match.rule && now - lastEnforcementElapsed <= RULE_ESCALATION_WINDOW_MS) {
            consecutiveRuleHits += 1
        } else {
            lastEnforcedRuleKey = match.rule
            consecutiveRuleHits = 1
        }
        lastEnforcementElapsed = now

        if (consecutiveRuleHits >= 3) {
            consecutiveRuleHits = 0
            postBackVerifyRunnable?.let { handler.removeCallbacks(it) }
            postBackVerifyRunnable = null
            Timber.d("Content blocker escalating to GO_HOME for sticky rule=%s", match.rule)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            Timber.d("Content blocker PRESS_BACK for rule=%s (hit=%d)", match.rule, consecutiveRuleHits)
            performGlobalAction(GLOBAL_ACTION_BACK)
            schedulePostBackVerification(verifyPkg)
        }
    }

    private fun schedulePostBackVerification(pkg: String) {
        postBackVerifyRunnable?.let { handler.removeCallbacks(it) }
        val verify = Runnable {
            postBackVerifyRunnable = null
            if (!isRunning) return@Runnable
            if (pkg in cachedWatchedPackages) {
                evaluateAndEnforceContentBlock(pkg, lastClassNameByPackage[pkg].orEmpty())
            }
        }
        postBackVerifyRunnable = verify
        handler.postDelayed(verify, POST_BACK_VERIFY_DELAY_MS)
    }

    /** Enforces schedules, exhausted limits, and content/TikTok rules on secondary visible windows. */
    private fun enforceSplitScreenWindows() {
        if (!PlayStoreUpdateManager.isBlockUiEnabledCached(applicationContext) &&
            getControlledPackages().isEmpty() &&
            cachedWatchedPackages.isEmpty()
        ) {
            return
        }
        val appWindows = runCatching {
            windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        }.getOrDefault(emptyList())

        val visiblePackages = LinkedHashSet<String>()
        val cfg = cachedBrowserConfig
        for (win in appWindows) {
            val root = runCatching { win.root }.getOrNull() ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                visiblePackages.add(pkg)
                if (pkg != currentPackage && pkg in cachedWatchedPackages && cfg.enabled) {
                    if (cfg.blockTikTok && pkg in ContentBlockerEngine.TIKTOK_PACKAGES) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return
                    }
                    if (evaluateRootForContentBlock(root, pkg, lastClassNameByPackage[pkg].orEmpty(), cfg)) {
                        return
                    }
                }
            } finally {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
        }

        for (pkg in visiblePackages) {
            if (PlayStoreUpdateManager.shouldBlockUiForPackage(applicationContext, pkg)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
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

    private fun syncImpulseSuspension() {
        synchronized(SuspensionCoordinator.lock) {
            val ctx = applicationContext
            val remaining = SecurityManager.getImpulseBlockRemainingTime(ctx)
            val targets = SecurityManager.getImpulseSuspendedApps(ctx)
            val shouldSuspend = remaining > 0 &&
                SecurityManager.getImpulseAction(ctx) == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND &&
                targets.isNotEmpty()

            if (!shouldSuspend) {
                releaseImpulseSuspension(force = true)
                return
            }

            val alreadySuspended = SecurityManager.getActiveImpulseSuspension(ctx)
            val dropped = (alreadySuspended - targets) -
                DowntimeManager.heldPackages(ctx) -
                DowntimeManager.activePackages(ctx) -
                BlockSuspensionManager.heldPackages(ctx) -
                SuspensionCoordinator.manualSuspensions(ctx)
            if (dropped.isNotEmpty()) suspendPackages(dropped.toTypedArray(), false)

            val toSuspend = (targets - alreadySuspended).toTypedArray()
            if (toSuspend.isNotEmpty()) {
                suspendPackages(toSuspend, true, reason = SuspensionReason.IMPULSE_LOCK)
            }
            SecurityManager.setActiveImpulseSuspension(ctx, targets)

            impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
            val release = Runnable {
                impulseReleaseRunnable = null
                releaseImpulseSuspension(force = false)
            }
            impulseReleaseRunnable = release
            handler.postDelayed(release, remaining)
            scheduleImpulseAlarm(ctx, remaining)
            Timber.d("Impulse block: suspended ${targets.size} app(s) for ${remaining}ms")
        }
    }

    private fun releaseImpulseSuspension(force: Boolean = false) {
        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null
        releaseImpulseSuspensionIfExpired(applicationContext, force = force)
    }

    private fun suspendPackages(
        packages: Array<String>,
        suspend: Boolean = true,
        reason: SuspensionReason? = null,
        details: String? = null
    ) {
        if (packages.isEmpty()) return
        val dpm = applicationContext.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
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

    private fun getControlledPackages(): Array<String> {
        val restrictedGroupPackages = AppGroupRepository.getGroups()
            .filter { it.timeLimit.isSet || it.timeWindows.any { w -> w.enabled } }
            .flatMap { it.packageNames }

        return limitPrefs.all.keys
            .union(weeklyLimitPrefs.all.keys)
            .union(reopenPrefs.all.keys)
            .union(timeWindowPrefs.all.keys)
            .union(restrictedGroupPackages)
            .toTypedArray()
    }

    private data class TrackingConfig(
        val group: AppGroup?,
        val appLimitMinutes: Int,
        val groupLimitMinutes: Int,
        val hasAnyVaryingOrSetLimit: Boolean,
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
            val nowZoned = ZonedDateTime.now()
            val nowSecond = nowZoned.hour * 3600 + nowZoned.minute * 60 + nowZoned.second
            val activeSpan = config.allowed.firstOrNull { nowSecond in it.start * 60 until it.end * 60 }
            if (activeSpan == null) {
                executeBlocking(outsideWindow = true)
                return
            }
            val endTime = if (activeSpan.end >= 1440) {
                nowZoned.toLocalDate().plusDays(1).atStartOfDay(nowZoned.zone)
            } else {
                nowZoned.toLocalDate().atTime(activeSpan.end / 60, activeSpan.end % 60).atZone(nowZoned.zone)
            }
            val delayMs = Duration.between(nowZoned.toInstant(), endTime.toInstant()).toMillis().coerceAtLeast(1000L)
            handler.postDelayed(windowEndRunnable, delayMs)
        }

        val remainingSecondsReopening = getRemainingSecondsToReopen(pkg)
        if (config.appLimitMinutes <= 0 && config.groupLimitMinutes <= 0) {
            if (config.hasAnyVaryingOrSetLimit) {
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
    } catch (_: Exception) {
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

    private fun saveSessionUsage(): Boolean {
        val pkg = currentPackage ?: return false
        if (sessionStartTime == 0L) return false

        val config = trackingConfigFor(pkg)
        val now = SystemClock.elapsedRealtime()
        val today = LocalDate.now()
        var rolledOver = false
        if (today.isAfter(sessionDate)) {
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
        lastForegroundPackage = null
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

    private fun checkDateReset(): Boolean = DailyUsageStore.ensureCurrentDate(applicationContext)

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
