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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.github.warleysr.dechainer.data.SuspensionLogRepository
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.utils.HomeScreenDetector
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime
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
    private var lastCheckDate: String = LocalDate.now().toString()
    private val lastClosedTimes = HashMap<String, Long>()

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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastForegroundPackage = currentPackage
                    stopTrackingAndSave(screenOff = true)
                    currentPackage = null
                }

                Intent.ACTION_SCREEN_ON -> lastForegroundPackage?.let {
                    currentPackage = it
                    sessionStartTime = SystemClock.elapsedRealtime()
                    checkDateReset()
                    startTracking(it)
                }
            }
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

            securityPrefs -> {
                // Written by SecurityManager both when the panic button starts a block and when
                // the block is found to have run out.
                if (key == "impulse_block_active") syncImpulseSuspension()
            }
        }
    }

    private val blockRunnable = Runnable { executeBlocking() }

    private val windowEndRunnable = Runnable { executeBlocking(outsideWindow = true) }

    companion object {
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        disablingService = false

        limitPrefs = getSharedPreferences(AppTimeLimits.PREFS_NAME, MODE_PRIVATE)
        weeklyLimitPrefs = getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, MODE_PRIVATE)
        usagePrefs = getSharedPreferences("internal_usage_stats", MODE_PRIVATE)
        reopenPrefs = getSharedPreferences("reopen_times", MODE_PRIVATE)
        timeWindowPrefs = getSharedPreferences(AppTimeWindows.PREFS_NAME, MODE_PRIVATE)
        groupsPrefs = getSharedPreferences(AppGroupRepository.PREFS_NAME, MODE_PRIVATE)
        groupUsagePrefs = getSharedPreferences("group_usage_stats", MODE_PRIVATE)
        securityPrefs = getSharedPreferences("security_prefs", MODE_PRIVATE)

        limitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        weeklyLimitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        timeWindowPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        groupsPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        securityPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        suspendPackages(getControlledPackages(), false)

        // Must run after the un-suspend above, which would otherwise release a package that is
        // still serving an impulse block.
        syncImpulseSuspension()

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(applicationContext)

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        })
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("io.github.warleysr.dechainer.DEBUG_INSTALL")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    disablingService = true
                    context.getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
                        putBoolean(SecurityManager.DEBUG_AUTO_START_SESSION_KEY, true)
                    }
                    SecurityManager.suspendUnknownSourcesRestrictionForDebugInstall(context)
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
        securityPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        impulseReleaseRunnable?.let { handler.removeCallbacks(it) }
        impulseReleaseRunnable = null
        stopTrackingAndSave()
        isRunning = false

        if (!disablingService) {
            suspendPackages(getControlledPackages(), reason = SuspensionReason.ACCESSIBILITY_DISABLED)

            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }

            startActivity(
                Intent(this@DechainerAccessibilityService, AccessibilityRequestActivity::class.java)
                    .apply { flags = FLAG_ACTIVITY_NEW_TASK }
            )
        }

        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(blockRunnable)
        handler.removeCallbacks(windowEndRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName == packageName) return

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
            val blockedActivities = getSharedPreferences("activity_blocker_prefs", MODE_PRIVATE)
                .getStringSet("blocked_activities", emptySet()) ?: emptySet()

            if (blockedActivities.contains(className))
                performGlobalAction(GLOBAL_ACTION_BACK)
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
        val dropped = alreadySuspended - targets
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

        suspendPackages(suspended.toTypedArray(), false)
        SecurityManager.clearActiveImpulseSuspension(ctx)
        Timber.d("Impulse block: released ${suspended.size} app(s)")
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
        // One timer per open app: no polling while the app is in use.
        val remainingMillis = listOfNotNull(appRemaining, groupRemaining).minOrNull()
        if (remainingMillis != null) {
            handler.postDelayed(blockRunnable, remainingMillis)
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
