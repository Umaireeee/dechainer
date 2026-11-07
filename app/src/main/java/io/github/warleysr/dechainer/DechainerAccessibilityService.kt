package io.github.warleysr.dechainer

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import io.github.warleysr.dechainer.activities.AccessibilityRequestActivity
import io.github.warleysr.dechainer.activities.OutsideTimeWindowActivity
import io.github.warleysr.dechainer.activities.ReopeningLimitActivity
import io.github.warleysr.dechainer.activities.TimeUpActivity
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.AppGroupRepository
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.AppTimeLimits
import io.github.warleysr.dechainer.data.AppTimeWindows
import io.github.warleysr.dechainer.data.BrowserRestrictionsManager
import io.github.warleysr.dechainer.data.ColorFilterController
import io.github.warleysr.dechainer.data.ColorFilterSettings
import io.github.warleysr.dechainer.data.FocusMode
import io.github.warleysr.dechainer.data.SuspensionLogRepository
import io.github.warleysr.dechainer.models.RegionRule
import io.github.warleysr.dechainer.utils.RegionOverlay
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.data.PlayStoreRatingFetcher
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.RegionRuleRepository
import io.github.warleysr.dechainer.data.UsageWarningSettings
import io.github.warleysr.dechainer.data.VisualBlockingSuspensionTracker
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.ColorFilterMode
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.models.UsageAlertStage
import io.github.warleysr.dechainer.notifications.FocusService
import io.github.warleysr.dechainer.notifications.UsageWarningNotifier
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.utils.HomeScreenDetector
import io.github.warleysr.dechainer.utils.NightLightOverlay
import org.jsoup.HttpStatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max

@SuppressLint("AccessibilityPolicy")
class DechainerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage: String? = null

    // Region blocking: rules cached per package, so events from apps without rules cost nothing.
    private lateinit var regionPrefs: SharedPreferences
    private var regionRules: Map<String, List<RegionRule>> = emptyMap()
    private val regionOverlay by lazy {
        RegionOverlay(this, getString(R.string.region_blocked_message))
    }
    private var regionScanPending = false
    private val regionScanRunnable = Runnable {
        regionScanPending = false
        scanRegions()
    }
    private var sessionStartTime: Long = 0
    private var lastCheckDate: String = LocalDate.now().toString()
    private val lastClosedTimes = HashMap<String, Long>();

    // Apps with a reopen interval that lost the screen, keyed to when. Leaving only counts as closing once
    // the home screen shows; coming back first (a popup, recents, an app opened from a notification) cancels it.
    private val pendingLeaves = HashMap<String, Long>()
    private val homeScreenDetector by lazy { HomeScreenDetector(this) }

    private lateinit var limitPrefs: SharedPreferences
    private lateinit var weeklyLimitPrefs: SharedPreferences
    private lateinit var usagePrefs: SharedPreferences
    private lateinit var reopenPrefs: SharedPreferences
    private lateinit var timeWindowPrefs: SharedPreferences
    private lateinit var groupsPrefs: SharedPreferences
    private lateinit var groupUsagePrefs: SharedPreferences
    private lateinit var securityPrefs: SharedPreferences
    private lateinit var ratingPrefs: SharedPreferences
    private lateinit var usageWarningPrefs: SharedPreferences
    private lateinit var colorFilterPrefs: SharedPreferences



    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var usageWarningEnabled: Boolean = UsageWarningSettings.DEFAULT_ENABLED
    private var usageWarningStages: List<UsageAlertStage> = emptyList()

    private var currentWarnKey: String? = null

    private var activeStageId: String? = null

    // Same idea for the impulse block, which suspends its whole app list as one unit.
    private var impulseReleaseRunnable: Runnable? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
            val packageName = intent.data?.encodedSchemeSpecificPart ?: return

            val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (!isUpdate) {
                AppRepository.reapplyHiddenIfNeeded(packageName)
            }

            val manager = BrowserRestrictionsManager(applicationContext)
            val isBrowser = manager.isBrowser(packageName)

            if (isBrowser) {
                val supportsRestrictions = manager.supportsRestrictions(packageName)
                if (supportsRestrictions) {
                    manager.applyRestrictions(installed = true)
                    return
                }

                suspendPackage(packageName, reason = SuspensionReason.UNSUPPORTED_BROWSER)
            }
            else {
                val isTorrentApp = manager.isTorrentApp(packageName)
                if (isTorrentApp && SecurityManager.isBlockTorrentsEnabled(applicationContext))
                    suspendPackage(packageName, reason = SuspensionReason.TORRENT_APP)
            }

            if (!isUpdate) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val info = PlayStoreRatingFetcher.fetch(packageName)

                        if (info.hasUnrestrictedInternet) {
                            val supportsRestrictions = manager.supportsRestrictions(packageName)
                            if (supportsRestrictions) {
                                manager.applyRestrictions(installed = true)
                            } else {
                                withContext(Dispatchers.Main) {
                                    suspendPackage(packageName, reason = SuspensionReason.UNSUPPORTED_BROWSER)
                                }
                                ratingPrefs.edit { putBoolean(packageName, true) }
                                return@launch
                            }
                        }

                        if (info.hasExplicitContent && info.contentRating == "Rated 18+")
                            withContext(Dispatchers.Main) {
                                suspendPackage(packageName, reason = SuspensionReason.RATED_18_PLUS)
                            }

                        ratingPrefs.edit { putBoolean(packageName, info.hasExplicitContent) }

                    } catch (e: HttpStatusException) {
                        e.printStackTrace()
                        if (e.statusCode == 404) {
                            ratingPrefs.edit { putBoolean(packageName, false) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ratingPrefs.edit { putBoolean(packageName, false) }
                    }
                }
            }
        }
    }

    private var lastForegroundPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastForegroundPackage = currentPackage
                    stopTrackingAndSave(screenOff = true)
                    currentPackage = null
                }

                Intent.ACTION_SCREEN_ON -> {
                    syncColorFilters()
                    lastForegroundPackage?.let {
                        currentPackage = it
                        sessionStartTime = SystemClock.elapsedRealtime()
                        checkDateReset()
                        startTracking(it)
                    }
                }

                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> syncColorFilters()
            }
        }
    }

    private val colorFilterObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            syncColorFilters()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (prefs) {
            limitPrefs, weeklyLimitPrefs -> {
                if (key == currentPackage) {
                    handler.removeCallbacks(blockRunnable)
                    currentPackage?.let { startTracking(it) }
                }
            }

            timeWindowPrefs -> {
                if (key == currentPackage) {
                    handler.removeCallbacks(blockRunnable)
                    handler.removeCallbacks(windowEndRunnable)
                    currentPackage?.let { startTracking(it) }
                }
            }

            groupsPrefs -> {
                handler.removeCallbacks(blockRunnable)
                handler.removeCallbacks(windowEndRunnable)
                currentPackage?.let { startTracking(it) }
            }

            regionPrefs -> {
                updateRegionRules()
                if (regionRules.isEmpty()) regionOverlay.hide() else scheduleRegionScan()
            }

            colorFilterPrefs -> {
                syncColorFilters()
            }

            usageWarningPrefs -> {
                updateUsageWarningSettings()
                if (!usageWarningEnabled) {
                    cancelActiveUsageWarning()
                }
                currentPackage?.let { startTracking(it) }
            }

            securityPrefs -> {
                // Written by SecurityManager both when the panic button starts a block and when
                // the block is found to have run out.
                if (key == "impulse_block_active") syncImpulseSuspension()
            }
        }
    }

    private fun updateUsageWarningSettings() {
        usageWarningEnabled = usageWarningPrefs.getBoolean(
            UsageWarningSettings.KEY_ENABLED, UsageWarningSettings.DEFAULT_ENABLED
        )
        usageWarningStages = UsageWarningSettings.loadOrSeedStages(usageWarningPrefs)
            .sortedByDescending { it.minutesBefore }
    }

    private fun cancelActiveUsageWarning() {
        val key = currentWarnKey ?: return
        val stageId = activeStageId ?: return
        UsageWarningNotifier.cancel(applicationContext, key, stageId)
    }

    private val blockRunnable = Runnable {
        executeBlocking()
    }

    private val windowEndRunnable = Runnable {
        executeBlocking(outsideWindow = true)
    }

    private val warnTickRunnable = Runnable {
        tickUsageWarning()
    }

    private val colorFilterSyncRunnable = Runnable {
        syncColorFilters()
    }

    private val nightLightOverlay by lazy { NightLightOverlay(this) }

    private var colorFilterForegroundPackage: String? = null
    private var colorFilterAppliedModes: Set<ColorFilterMode> = emptySet()

    companion object {
        private const val USAGE_WARNING_TICK_INTERVAL_MS = 2000L

        @Volatile
        private var instance: DechainerAccessibilityService? = null

        /** A node of the screen last captured for the region picker. */
        data class CapturedNode(
            val viewId: String?,
            val text: String?,
            val className: String,
            val width: Int,
            val height: Int,
            val depth: Int
        )

        var capturedPackage by mutableStateOf<String?>(null)
            private set
        var capturedNodes by mutableStateOf<List<CapturedNode>>(emptyList())
            private set
        var captureCountdown by mutableStateOf(false)
            private set

        private const val REGION_SCAN_DELAY_MS = 100L
        private const val CAPTURE_MAX_NODES = 600

        /**
         * Sends the user home and, after [delayMs], snapshots whatever app is then on screen
         * for the region picker, then brings Déchaîner back. Returns false if the service is off.
         */
        fun scheduleCapture(delayMs: Long): Boolean {
            val service = instance ?: return false
            service.handler.removeCallbacks(service.captureRunnable)
            captureCountdown = true
            service.performGlobalAction(GLOBAL_ACTION_HOME)
            service.handler.postDelayed(service.captureRunnable, delayMs)
            return true
        }

        fun clearCapture() {
            capturedPackage = null
            capturedNodes = emptyList()
        }

        var isRunning by mutableStateOf(false)
            private set

        var disablingService = false
            private set

        var checkingRating = false
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        disablingService = false

        limitPrefs = getSharedPreferences(AppTimeLimits.PREFS_NAME, MODE_PRIVATE)
        weeklyLimitPrefs = getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, MODE_PRIVATE)
        usagePrefs = getSharedPreferences("internal_usage_stats", MODE_PRIVATE)
        reopenPrefs = getSharedPreferences("reopen_times", MODE_PRIVATE)
        timeWindowPrefs = getSharedPreferences(AppTimeWindows.PREFS_NAME, MODE_PRIVATE)
        groupsPrefs = getSharedPreferences(AppGroupRepository.PREFS_NAME, MODE_PRIVATE)
        groupUsagePrefs = getSharedPreferences("group_usage_stats", MODE_PRIVATE)
        securityPrefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        ratingPrefs = getSharedPreferences("app_ratings", MODE_PRIVATE)
        usageWarningPrefs = getSharedPreferences(UsageWarningSettings.PREFS_NAME, MODE_PRIVATE)
        colorFilterPrefs = getSharedPreferences(ColorFilterSettings.PREFS_NAME, MODE_PRIVATE)
        regionPrefs = RegionRuleRepository.prefs(this)
        ColorFilterSettings.migrate(colorFilterPrefs)

        limitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        weeklyLimitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        timeWindowPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        groupsPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        usageWarningPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        colorFilterPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        regionPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        securityPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        updateUsageWarningSettings()
        updateRegionRules()

        ColorFilterController.observedUris.forEach {
            contentResolver.registerContentObserver(it, false, colorFilterObserver)
        }
        syncColorFilters()

        val blockedPackages = getControlledPackages()
        suspendPackages(blockedPackages, false)

        // Must run after the un-suspend above, which would otherwise release a package that is
        // still serving a visual-blocking suspension, an impulse block or a focus period.
        liftLegacyVisualSuspensions()
        syncImpulseSuspension()
        FocusMode.sync(applicationContext)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(applicationContext)

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, packageFilter)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(screenReceiver, screenFilter)

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("io.github.warleysr.dechainer.DEBUG_INSTALL")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    disablingService = true
                    context.getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
                        putBoolean(SecurityManager.DEBUG_AUTO_START_SESSION_KEY, true)
                    }
                    SecurityManager.suspendUnknownSourcesRestrictionForDebugInstall(context)
                    FocusService.stopForDebugInstall(context)
                    disableSelf()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(screenReceiver)
        limitPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        weeklyLimitPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        timeWindowPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        groupsPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        usageWarningPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        colorFilterPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        regionPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        securityPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        contentResolver.unregisterContentObserver(colorFilterObserver)
        handler.removeCallbacks(colorFilterSyncRunnable)
        // Must run after unregistering the observer, or restoring would re-enforce the modes.
        ColorFilterController.release(applicationContext)
        nightLightOverlay.hide()
        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null
        handler.removeCallbacks(regionScanRunnable)
        handler.removeCallbacks(captureRunnable)
        regionOverlay.hide()
        instance = null
        stopTrackingAndSave()
        isRunning = false

        if (!disablingService) {
            val blockedPackages = getControlledPackages()
            suspendPackages(blockedPackages, reason = SuspensionReason.ACCESSIBILITY_DISABLED)

            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }

            val intent = Intent(
                this@DechainerAccessibilityService, AccessibilityRequestActivity::class.java
            ).apply { flags = FLAG_ACTIVITY_NEW_TASK }
            startActivity(intent)
        }

        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
        handler.removeCallbacks(warnTickRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onColorFilterForegroundChanged(event)
        if (event.packageName == packageName) return
        if (regionRules.isNotEmpty()) onRegionEvent(event)

        // App tracking to control time limits and time between re-openings
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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
                sessionStartTime = SystemClock.elapsedRealtime()
                checkDateReset()
                startTracking(newPackage)
            }

            // Checked on every launcher event, not just on a package change: going from recents to home stays in the launcher.
            if (pendingLeaves.isNotEmpty() && homeScreenDetector.isHomeScreen(event, rootInActiveWindow)) {
                pendingLeaves.forEach { (pkg, leftAt) -> lastClosedTimes[pkg] = leftAt }
                pendingLeaves.clear()
            }

            if (className.contains("Activity", ignoreCase = true)) {
                addLog(newPackage, className)
                val blockerPrefs =
                    getSharedPreferences("activity_blocker_prefs", MODE_PRIVATE)
                val blockedActivities =
                    blockerPrefs.getStringSet("blocked_activities", emptySet()) ?: emptySet()

                if (blockedActivities.contains(className))
                    performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        // Unknown app: look up its Play Store rating / browser status once
        else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val pkg = currentPackage ?: return

            if (!ratingPrefs.contains(pkg) && !checkingRating) {
                checkingRating = true
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val info = PlayStoreRatingFetcher.fetch(pkg)

                        if (info.hasUnrestrictedInternet) {
                            val manager = BrowserRestrictionsManager(applicationContext)
                            val supportsRestrictions = manager.supportsRestrictions(pkg)
                            if (supportsRestrictions) {
                                manager.applyRestrictions()
                            } else {
                                withContext(Dispatchers.Main) {
                                    suspendPackage(pkg, reason = SuspensionReason.UNSUPPORTED_BROWSER)
                                }
                                ratingPrefs.edit { putBoolean(pkg, true) }
                                return@launch
                            }
                        }

                        if (info.hasExplicitContent && info.contentRating == "Rated 18+")
                            withContext(Dispatchers.Main) {
                                suspendPackage(pkg, reason = SuspensionReason.RATED_18_PLUS)
                            }

                        ratingPrefs.edit { putBoolean(pkg, info.hasExplicitContent) }
                    } catch (e: HttpStatusException) {
                        e.printStackTrace()
                        if (e.statusCode == 404) {
                            ratingPrefs.edit { putBoolean(pkg, false) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ratingPrefs.edit { putBoolean(pkg, false) }
                    } finally {
                        checkingRating = false
                    }
                }
            }
        }
    }

    private val captureRunnable = Runnable { captureNow() }

    private fun updateRegionRules() {
        regionRules = RegionRuleRepository.load(regionPrefs).groupBy { it.packageName }
    }

    private fun onRegionEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in regionRules) {
            scheduleRegionScan()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != "com.android.systemui"
        ) {
            // Another app came to the front (the keyboard and system UI are ignored above).
            regionOverlay.hide()
        }
    }

    /** Coalesces bursts of events into a single tree query. */
    private fun scheduleRegionScan() {
        if (regionScanPending) return
        regionScanPending = true
        handler.postDelayed(regionScanRunnable, REGION_SCAN_DELAY_MS)
    }

    private fun scanRegions() {
        val root = rootInActiveWindow
        val rules = root?.packageName?.toString()?.let { regionRules[it] }
        if (root == null || rules == null) {
            regionOverlay.hide()
            root?.recycle()
            return
        }

        val rects = ArrayList<Rect>()
        for (rule in rules) {
            val nodes = when {
                rule.viewId != null -> root.findAccessibilityNodeInfosByViewId(rule.viewId)
                rule.text != null -> root.findAccessibilityNodeInfosByText(rule.text)
                else -> emptyList()
            }
            for (node in nodes.orEmpty()) {
                val matchesText = rule.text == null ||
                    node.text?.toString() == rule.text ||
                    node.contentDescription?.toString() == rule.text
                if (matchesText) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rule.extendDown) {
                        regionBelow(node, rect)?.let { rects.add(it) }
                    } else if (node.isVisibleToUser && !rect.isEmpty) {
                        rects.add(rect)
                    }
                }
                node.recycle()
            }
        }
        root.recycle()
        if (rects.isEmpty()) regionOverlay.hide() else regionOverlay.show(rects)
    }

    /**
     * The area from [nodeBounds] down to the end of the node's scrolling container (or the window
     * when it has none), so the bottom navigation bar stays usable. If the node has scrolled off
     * the top, the whole container is covered, since everything left in it is below the node.
     */
    private fun regionBelow(node: AccessibilityNodeInfo, nodeBounds: Rect): Rect? {
        var container = node.parent
        while (container != null && !container.isScrollable) container = container.parent
        if (container == null) {
            return if (node.isVisibleToUser && !nodeBounds.isEmpty) Rect(nodeBounds) else null
        }
        val area = Rect()
        container.getBoundsInScreen(area)
        container.recycle()

        return when {
            nodeBounds.bottom <= area.top -> area
            nodeBounds.top >= area.bottom -> null
            else -> Rect(area.left, maxOf(nodeBounds.top, area.top), area.right, area.bottom)
        }.takeIf { it == null || !it.isEmpty }
    }

    private fun captureNow() {
        captureCountdown = false
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root != null && pkg != null && pkg != packageName) {
            val nodes = ArrayList<CapturedNode>()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                node ?: return
                if (nodes.size >= CAPTURE_MAX_NODES) return
                if (node.isVisibleToUser) {
                    val id = node.viewIdResourceName
                    val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    if ((id != null || label != null) && !rect.isEmpty) {
                        nodes.add(
                            CapturedNode(
                                id, label, node.className?.toString().orEmpty(),
                                rect.width(), rect.height(), depth
                            )
                        )
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    walk(child, depth + 1)
                    child?.recycle()
                }
            }
            walk(root, 0)
            capturedPackage = pkg
            capturedNodes = nodes
        } else {
            clearCapture()
        }
        root?.recycle()

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

    /** Visual blocking was removed: lift any timed suspension it left persisted by an older version. */
    private fun liftLegacyVisualSuspensions() {
        val tracker = VisualBlockingSuspensionTracker(applicationContext)
        tracker.activeSuspensions().keys.forEach { pkg ->
            tracker.endSuspension(pkg)
            if (!isHeldByOtherBlock(pkg)) suspendPackage(pkg, suspend = false)
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

        // The configured list may have changed since the block started; let go of anything that
        // is no longer part of it before applying the current one.
        val alreadySuspended = SecurityManager.getActiveImpulseSuspension(ctx)
        val dropped = (alreadySuspended - targets).filterNot { FocusMode.isHolding(ctx, it) }
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
        Timber.d("Impulse block: suspended ${targets.size} app(s) for ${remaining}ms")
    }

    private fun releaseImpulseSuspension() {
        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null

        val ctx = applicationContext
        val suspended = SecurityManager.getActiveImpulseSuspension(ctx)
        if (suspended.isEmpty()) return

        // A package can be serving a visual-blocking suspension or a focus period at the same time;
        // releasing it here would cut that one short, so those are left alone for their own timer to lift.
        val releasable = suspended.filterNot { FocusMode.isHolding(ctx, it) }
        if (releasable.isNotEmpty()) suspendPackages(releasable.toTypedArray(), false)

        SecurityManager.clearActiveImpulseSuspension(ctx)
        Timber.d("Impulse block: released ${releasable.size} app(s)")
    }

    private fun isHeldByOtherBlock(pkg: String): Boolean {
        val ctx = applicationContext
        return pkg in SecurityManager.getActiveImpulseSuspension(ctx) || FocusMode.isHolding(ctx, pkg)
    }

    private fun syncColorFilters() {
        handler.removeCallbacks(colorFilterSyncRunnable)

        val modes = ColorFilterSettings.modesToApply(colorFilterPrefs, colorFilterForegroundPackage)
        colorFilterAppliedModes = modes
        if (modes.isNotEmpty()) {
            ColorFilterController.enforce(applicationContext, ColorFilterController.targetsFor(colorFilterPrefs, modes))
        } else {
            ColorFilterController.release(applicationContext)
        }

        val overlayNightLight = !ColorFilterController.platformNightLight && ColorFilterMode.NIGHT_LIGHT in modes
        if (overlayNightLight) {
            nightLightOverlay.show(
                ColorFilterSettings.nightLightTemperature(colorFilterPrefs),
                ColorFilterSettings.nightLightIntensity(colorFilterPrefs)
            )
        } else {
            nightLightOverlay.hide()
        }

        if (!ColorFilterSettings.isEnabled(colorFilterPrefs)) return
        val delay = ColorFilterSettings.millisUntilNextBoundary(ColorFilterSettings.scheduledWindows(colorFilterPrefs))
            ?: return
        handler.postDelayed(colorFilterSyncRunnable, delay)
    }

    // Kept apart from currentPackage, which skips Déchaîner's own screens and is cleared when an app gets blocked.
    private fun onColorFilterForegroundChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (pkg == "com.android.systemui") return
        if (className.contains("InputMethodService", ignoreCase = true) ||
            className.contains("SoftInputWindow", ignoreCase = true)
        ) return
        // Our own overlays (e.g. night light) aren't a screen the user switched to.
        if (pkg == packageName && !className.startsWith(packageName)) return
        if (pkg == colorFilterForegroundPackage) return

        colorFilterForegroundPackage = pkg
        if (!ColorFilterSettings.dependsOnForegroundApp(colorFilterPrefs)) return
        val modes = ColorFilterSettings.modesToApply(colorFilterPrefs, pkg)
        if (modes != colorFilterAppliedModes) syncColorFilters()
    }

    private fun suspendPackages(
        packages: Array<String>,
        suspend: Boolean = true,
        reason: SuspensionReason? = null,
        details: String? = null
    ) {
        val dpm =
            applicationContext.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(applicationContext, DechainerDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return
        dpm.setPackagesSuspended(admin, packages, suspend)
        if (suspend && reason != null) {
            packages.forEach { pkg ->
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
        val windows: List<TimeWindow>
    )

    private fun trackingConfigFor(pkg: String): TrackingConfig {
        val group = AppGroupRepository.getGroupForPackage(pkg)
        val appWindows = AppTimeWindows.decode(timeWindowPrefs.getString(pkg, null))
        val appLimitMinutes = AppTimeLimits.read(limitPrefs, weeklyLimitPrefs, pkg).todayMinutes()
        return if (group != null) {
            TrackingConfig(
                group = group,
                appLimitMinutes = appLimitMinutes,
                groupLimitMinutes = group.timeLimit.todayMinutes(),
                windows = TimeWindow.intersect(group.timeWindows, appWindows)
            )
        } else {
            TrackingConfig(null, appLimitMinutes, 0, appWindows)
        }
    }

    private fun startTracking(pkg: String) {
        handler.removeCallbacks(warnTickRunnable)

        val config = trackingConfigFor(pkg)

        if (config.windows.isNotEmpty()) {
            val nowMinute = currentMinuteOfDay()
            val activeWindow = config.windows.firstOrNull { it.contains(nowMinute) }
            if (activeWindow == null) {
                executeBlocking(outsideWindow = true)
                return
            }
            handler.postDelayed(
                windowEndRunnable,
                TimeUnit.MINUTES.toMillis(activeWindow.minutesUntilEnd(nowMinute).toLong())
            )
        }

        val remainingSecondsReopening = getRemainingSecondsToReopen(pkg)
        if (config.appLimitMinutes <= 0 && config.groupLimitMinutes <= 0 && remainingSecondsReopening <= 0) return

        var appRemaining: Long? = null
        var groupRemaining: Long? = null
        var limitReached = false

        if (config.appLimitMinutes > 0) {
            val remaining = TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong()) - usagePrefs.getLong(pkg, 0L)
            if (remaining <= 0) limitReached = true else appRemaining = remaining
        }

        if (config.group != null && config.groupLimitMinutes > 0) {
            val remaining = TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong()) -
                groupUsagePrefs.getLong(config.group.id, 0L)
            if (remaining <= 0) limitReached = true else groupRemaining = remaining
        }

        if (limitReached) {
            executeBlocking()
            return
        }
        val remainingMillis = listOfNotNull(appRemaining, groupRemaining).minOrNull()
        if (remainingMillis != null) {
            handler.postDelayed(blockRunnable, remainingMillis)
        }

        scheduleUsageWarning(pkg, config, appRemaining, groupRemaining)

        if (remainingSecondsReopening > 0)
            executeBlocking(reopening = true, remainingSeconds = remainingSecondsReopening)
    }

    private fun scheduleUsageWarning(pkg: String, config: TrackingConfig, appRemaining: Long?, groupRemaining: Long?) {
        currentWarnKey = null
        activeStageId = null
        if (!usageWarningEnabled || usageWarningStages.isEmpty()) return

        val useGroup = groupRemaining != null && config.group != null &&
            (appRemaining == null || groupRemaining < appRemaining)
        val key = when {
            useGroup -> config.group!!.id
            appRemaining != null -> pkg
            else -> return
        }
        val remaining = if (useGroup) groupRemaining!! else appRemaining!!

        currentWarnKey = key

        val farthestMillis = stageMillis(usageWarningStages.first())
        if (remaining > farthestMillis) {
            Timber.d("UsageWarning: schedule($key) - outside every stage, entering farthest in ${remaining - farthestMillis}ms")
            handler.postDelayed(warnTickRunnable, remaining - farthestMillis)
        } else {
            tickUsageWarning()
        }
    }

    private fun stageMillis(stage: UsageAlertStage) = (stage.minutesBefore * 60_000).toLong()

    private fun tickUsageWarning() {
        handler.removeCallbacks(warnTickRunnable)
        val pkg = currentPackage ?: return
        val key = currentWarnKey ?: return
        if (usageWarningStages.isEmpty()) return

        val config = trackingConfigFor(pkg)
        val elapsed = SystemClock.elapsedRealtime() - sessionStartTime
        val isGroupKey = config.group?.id == key

        val remaining = when {
            isGroupKey && config.groupLimitMinutes > 0 ->
                TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong()) - groupUsagePrefs.getLong(key, 0L) - elapsed
            !isGroupKey && config.appLimitMinutes > 0 ->
                TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong()) - usagePrefs.getLong(pkg, 0L) - elapsed
            else -> return
        }
        Timber.d("UsageWarning: tick($key) pkg=$pkg elapsed=${elapsed}ms remaining=${remaining}ms")

        if (remaining <= 0) {
            activeStageId?.let { UsageWarningNotifier.cancel(applicationContext, key, it) }
            activeStageId = null
            return
        }

        val enteredStage = usageWarningStages.lastOrNull { remaining <= stageMillis(it) }

        if (enteredStage == null) {
            activeStageId?.let { staleId ->
                UsageWarningNotifier.cancel(applicationContext, key, staleId)
                UsageWarningNotifier.reset(applicationContext, key, staleId)
            }
            activeStageId = null
            val farthestMillis = stageMillis(usageWarningStages.first())
            handler.postDelayed(warnTickRunnable, (remaining - farthestMillis).coerceAtLeast(500L))
            return
        }

        if (enteredStage.id != activeStageId) {
            activeStageId?.let { UsageWarningNotifier.cancel(applicationContext, key, it) }
            activeStageId = enteredStage.id
        }

        if (!UsageWarningNotifier.isDismissed(applicationContext, key, enteredStage.id)) {
            if (!UsageWarningNotifier.hasBeenShown(applicationContext, key, enteredStage.id)) {
                UsageWarningNotifier.markShown(applicationContext, key, enteredStage.id)
            }
            val displayName = if (isGroupKey) config.group?.name ?: key else appLabel(pkg)
            val barMillis = stageMillis(usageWarningStages.first())
            UsageWarningNotifier.showOrUpdate(
                applicationContext, key, enteredStage, displayName, remaining, barMillis
            )
        }

        handler.postDelayed(warnTickRunnable, USAGE_WARNING_TICK_INTERVAL_MS)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun stopTrackingAndSave(screenOff: Boolean = false) {
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
        handler.removeCallbacks(warnTickRunnable)
        val pkg = currentPackage ?: return

        if (!screenOff && reopenPrefs.getInt(pkg, 0) > 0 && getRemainingSecondsToReopen(pkg) == 0)
            pendingLeaves[pkg] = SystemClock.elapsedRealtime()

        if (sessionStartTime == 0L) return

        val config = trackingConfigFor(pkg)
        val currentSessionMillis = SystemClock.elapsedRealtime() - sessionStartTime

        if (config.appLimitMinutes > 0) {
            usagePrefs.edit { putLong(pkg, usagePrefs.getLong(pkg, 0L) + currentSessionMillis) }
        }
        if (config.group != null && config.groupLimitMinutes > 0) {
            val groupId = config.group.id
            groupUsagePrefs.edit { putLong(groupId, groupUsagePrefs.getLong(groupId, 0L) + currentSessionMillis) }
        }

        sessionStartTime = 0
    }

    private fun executeBlocking(reopening: Boolean = false, remainingSeconds: Int = 0, outsideWindow: Boolean = false) {
        val pkg = currentPackage ?: return
        val config = trackingConfigFor(pkg)

        stopTrackingAndSave()
        currentPackage = null

        if (!reopening && !outsideWindow) {
            usageWarningStages.forEach { stage ->
                UsageWarningNotifier.cancel(applicationContext, pkg, stage.id)
                config.group?.let { UsageWarningNotifier.cancel(applicationContext, it.id, stage.id) }
            }
        }

        val individualAppName = appLabel(pkg)

        // For a plain limit block, name and blame whichever cap actually ran out: the app's own,
        // or (when both are exhausted) the shared group cap, since that's the one still binding.
        var displayName = individualAppName
        var displayLimit = config.appLimitMinutes
        if (!reopening && !outsideWindow && config.group != null && config.groupLimitMinutes > 0) {
            val groupReached = groupUsagePrefs.getLong(config.group.id, 0L) >=
                TimeUnit.MINUTES.toMillis(config.groupLimitMinutes.toLong())
            val appReached = config.appLimitMinutes > 0 &&
                usagePrefs.getLong(pkg, 0L) >= TimeUnit.MINUTES.toMillis(config.appLimitMinutes.toLong())
            if (groupReached && !appReached) {
                displayName = config.group.name
                displayLimit = config.groupLimitMinutes
            }
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
            intent.putStringArrayListExtra("windows", ArrayList(config.windows.map { it.formatted() }))
        } else {
            intent.putExtra("limit", if (reopening) remainingSeconds else displayLimit)
        }

        startActivity(intent)
    }

    private fun currentMinuteOfDay(): Int {
        val now = LocalTime.now()
        return now.hour * 60 + now.minute
    }
    
    
    private fun checkDateReset() {
        val today = LocalDate.now().toString()
        if (today != lastCheckDate) {
            usagePrefs.edit { clear() }
            groupUsagePrefs.edit { clear() }
            cancelActiveUsageWarning()
            activeStageId = null
            UsageWarningNotifier.resetAll(applicationContext)
            lastCheckDate = today
        }
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
