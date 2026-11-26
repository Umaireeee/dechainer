package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import androidx.core.content.edit
import io.github.warleysr.dechainer.AlarmTickReceiver
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

data class PlayStoreUpdateConfig(
    val blockPlayStoreUi: Boolean = false,
    val silentAutoUpdateEnabled: Boolean = false,
    val windowStartMinute: Int = DEFAULT_START_MINUTE,
    val windowEndMinute: Int = DEFAULT_END_MINUTE,
    val screenOffOnly: Boolean = true
) {
    fun normalized(): PlayStoreUpdateConfig = copy(
        windowStartMinute = normalizeMinute(windowStartMinute),
        windowEndMinute = normalizeMinute(windowEndMinute)
    )

    fun windowDurationMinutes(): Int {
        val start = normalizeMinute(windowStartMinute)
        val end = normalizeMinute(windowEndMinute)
        return when {
            start == end -> 0
            end > start -> end - start
            else -> (MINUTES_PER_DAY - start) + end
        }
    }

    fun isWithinWindow(minuteOfDay: Int): Boolean {
        val start = normalizeMinute(windowStartMinute)
        val end = normalizeMinute(windowEndMinute)
        val minute = normalizeMinute(minuteOfDay)
        if (start == end) return false
        return if (start < end) {
            minute in start until end
        } else {
            minute >= start || minute < end
        }
    }

    fun formatWindow(): String {
        val start = normalizeMinute(windowStartMinute)
        val end = normalizeMinute(windowEndMinute)
        return "%02d:%02d – %02d:%02d".format(
            start / 60, start % 60,
            end / 60, end % 60
        )
    }

    fun isLooserThan(old: PlayStoreUpdateConfig): Boolean {
        if (old.blockPlayStoreUi && !blockPlayStoreUi) return true
        if (silentAutoUpdateEnabled && !blockPlayStoreUi &&
            (!old.silentAutoUpdateEnabled || old.blockPlayStoreUi)
        ) {
            return true
        }
        if (silentAutoUpdateEnabled && old.screenOffOnly && !screenOffOnly) return true
        if (old.silentAutoUpdateEnabled && silentAutoUpdateEnabled &&
            windowDurationMinutes() > old.windowDurationMinutes()
        ) {
            return true
        }
        if (old.silentAutoUpdateEnabled && silentAutoUpdateEnabled && !screenOffOnly &&
            (normalizeMinute(windowStartMinute) != normalizeMinute(old.windowStartMinute) ||
                normalizeMinute(windowEndMinute) != normalizeMinute(old.windowEndMinute))
        ) {
            return true
        }
        return false
    }

    fun toJsonString(): String = JSONObject()
        .put("blockPlayStoreUi", blockPlayStoreUi)
        .put("silentAutoUpdateEnabled", silentAutoUpdateEnabled)
        .put("windowStartMinute", normalizeMinute(windowStartMinute))
        .put("windowEndMinute", normalizeMinute(windowEndMinute))
        .put("screenOffOnly", screenOffOnly)
        .toString()

    companion object {
        const val DEFAULT_START_MINUTE = 2 * 60
        const val DEFAULT_END_MINUTE = 5 * 60
        private const val MINUTES_PER_DAY = 24 * 60

        internal fun normalizeMinute(minute: Int): Int =
            ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

        fun fromJsonString(raw: String): PlayStoreUpdateConfig? = runCatching {
            val obj = JSONObject(raw)
            PlayStoreUpdateConfig(
                blockPlayStoreUi = obj.optBoolean("blockPlayStoreUi", false),
                silentAutoUpdateEnabled = obj.optBoolean("silentAutoUpdateEnabled", false),
                windowStartMinute = normalizeMinute(obj.optInt("windowStartMinute", DEFAULT_START_MINUTE)),
                windowEndMinute = normalizeMinute(obj.optInt("windowEndMinute", DEFAULT_END_MINUTE)),
                screenOffOnly = obj.optBoolean("screenOffOnly", true)
            )
        }.getOrNull()
    }
}

data class PlayStoreUpdateStatus(
    val config: PlayStoreUpdateConfig = PlayStoreUpdateConfig(),
    val windowCurrentlyOpen: Boolean = false,
    val rolledBackCount: Int = 0,
    val lastSweepRtcMs: Long = 0L,
    val playStoreSuspended: Boolean = false
)

/**
 * Zero-UI Play Store Silent Auto-Update policy controller:
 * - Blocks Play Store (`com.android.vending`) foreground UI 24/7 via Accessibility while keeping
 *   the package unsuspended so Google Play background sync stays operational.
 * - Opens a scheduled overnight silent auto-update window ONLY while the device screen is off /
 *   locked (and no impulse lock is active), snapshotting only currently enabled & non-hidden
 *   packages beforehand.
 * - Immediately re-locks `DISALLOW_INSTALL_APPS` on screen-on / unlock or window end, rolling back
 *   both newly installed third-party apps and re-enabled dormant OEM bloatware stubs.
 */
object PlayStoreUpdateManager {
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val ACTION_WINDOW_TICK = "io.github.warleysr.dechainer.PLAY_UPDATE_WINDOW_TICK"

    private const val ALARM_REQUEST_CODE = 7315
    private const val PREFS_NAME = "play_store_update_policy"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_WINDOW_OPEN = "window_open"
    private const val KEY_PRE_WINDOW_PACKAGES = "pre_window_packages"
    private const val KEY_RESTORE_DISALLOW_INSTALL = "restore_disallow_install"
    private const val KEY_ROLLED_BACK_COUNT = "rolled_back_count"
    private const val KEY_LAST_SWEEP_RTC = "last_sweep_rtc"

    private val lock = Any()

    @Volatile
    private var cachedBlockUi: Boolean? = null

    private val _status = MutableStateFlow(PlayStoreUpdateStatus())
    val status: StateFlow<PlayStoreUpdateStatus> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun resetForTest(context: Context) {
        synchronized(lock) {
            cachedBlockUi = null
            prefs(context).edit(commit = true) { clear() }
            _status.value = PlayStoreUpdateStatus()
        }
    }

    internal fun isUnauthorizedPackageInstall(
        packageName: String,
        preWindowEnabled: Set<String>,
        isReplacing: Boolean,
        ownPackageName: String
    ): Boolean {
        if (packageName == ownPackageName) return false
        return !(isReplacing && packageName in preWindowEnabled)
    }

    internal fun findUnauthorizedPackages(
        currentEnabled: Set<String>,
        preWindowEnabled: Set<String>,
        ownPackageName: String
    ): Set<String> = (currentEnabled - preWindowEnabled) - ownPackageName

    internal fun shouldWindowBeOpen(
        config: PlayStoreUpdateConfig,
        nowMinuteOfDay: Int,
        deviceUnattended: Boolean,
        impulseBlockActive: Boolean
    ): Boolean {
        if (!config.silentAutoUpdateEnabled || impulseBlockActive) return false
        if (!config.isWithinWindow(nowMinuteOfDay)) return false
        return !config.screenOffOnly || deviceUnattended
    }

    internal fun computeNextBoundaryInstant(
        now: ZonedDateTime,
        startMinute: Int,
        endMinute: Int
    ): Instant? {
        val normStart = PlayStoreUpdateConfig.normalizeMinute(startMinute)
        val normEnd = PlayStoreUpdateConfig.normalizeMinute(endMinute)
        if (normStart == normEnd) return null
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val candidates = listOf(
            LocalDateTime.of(today, java.time.LocalTime.of(normStart / 60, normStart % 60)),
            LocalDateTime.of(today, java.time.LocalTime.of(normEnd / 60, normEnd % 60)),
            LocalDateTime.of(tomorrow, java.time.LocalTime.of(normStart / 60, normStart % 60)),
            LocalDateTime.of(tomorrow, java.time.LocalTime.of(normEnd / 60, normEnd % 60))
        )
        return SuspensionCoordinator.nextZonedInstantAfter(candidates, now)
    }

    fun shouldBlockUiForPackage(context: Context, packageName: String): Boolean {
        if (packageName != PLAY_STORE_PACKAGE) return false
        return cachedBlockUi ?: getConfig(context).blockPlayStoreUi.also { cachedBlockUi = it }
    }

    fun isBlockUiEnabledCached(context: Context): Boolean =
        cachedBlockUi ?: getConfig(context).blockPlayStoreUi.also { cachedBlockUi = it }

    fun getConfig(context: Context): PlayStoreUpdateConfig {
        val ctx = context.applicationContext
        val raw = prefs(ctx).getString(KEY_CONFIG_JSON, null)
        val parsed = raw?.let { PlayStoreUpdateConfig.fromJsonString(it) } ?: PlayStoreUpdateConfig()
        cachedBlockUi = parsed.blockPlayStoreUi
        return parsed
    }

    fun isSilentWindowOpen(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(KEY_WINDOW_OPEN, false)

    fun refreshStatus(context: Context): PlayStoreUpdateStatus {
        val ctx = context.applicationContext
        synchronized(lock) {
            return emitStatusLocked(ctx)
        }
    }

    fun applyConfig(context: Context, config: PlayStoreUpdateConfig) {
        val ctx = context.applicationContext
        val normalized = config.normalized()
        synchronized(lock) {
            prefs(ctx).edit(commit = true) {
                putString(KEY_CONFIG_JSON, normalized.toJsonString())
            }
            cachedBlockUi = normalized.blockPlayStoreUi
        }
        if (normalized.silentAutoUpdateEnabled &&
            normalized.blockPlayStoreUi &&
            DechainerAccessibilityService.isRunning
        ) {
            unsuspendPlayStoreForBackgroundSync(ctx)
        }
        sync(ctx)
    }

    fun unsuspendPlayStoreForBackgroundSync(context: Context): Boolean {
        val ctx = context.applicationContext
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return false
        if (!getConfig(ctx).blockPlayStoreUi || !DechainerAccessibilityService.isRunning) return false

        val ok = synchronized(SuspensionCoordinator.lock) {
            ctx.getSharedPreferences("manual_suspensions", Context.MODE_PRIVATE).edit(commit = true) {
                remove(PLAY_STORE_PACKAGE)
            }
            val failed = runCatching {
                dpm.setPackagesSuspended(admin, arrayOf(PLAY_STORE_PACKAGE), false).toSet()
            }.getOrDefault(setOf(PLAY_STORE_PACKAGE))
            AppRepository.invalidateCache()
            PLAY_STORE_PACKAGE !in failed
        }
        refreshStatus(ctx)
        return ok
    }

    fun sync(
        context: Context,
        now: ZonedDateTime = ZonedDateTime.now(),
        screenOffOverride: Boolean? = null
    ) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val config = getConfig(ctx)
            val dpm = DeviceAdmin.policyManager
            val isOwner = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)
            val currentlyOpen = prefs(ctx).getBoolean(KEY_WINDOW_OPEN, false)

            if (!isOwner) {
                if (currentlyOpen) {
                    closeSilentWindowLocked(ctx, isOwner = false)
                }
                cancelBoundaryAlarm(ctx)
                emitStatusLocked(ctx)
                return
            }

            val unattended = screenOffOverride ?: isDeviceUnattended(ctx)
            val impulseActive = SecurityManager.getImpulseBlockRemainingTime(ctx) > 0L
            val minuteOfDay = now.hour * 60 + now.minute
            val shouldOpen = shouldWindowBeOpen(config, minuteOfDay, unattended, impulseActive)

            if (shouldOpen && !currentlyOpen) {
                openSilentWindowLocked(ctx)
            } else if (!shouldOpen && currentlyOpen) {
                closeSilentWindowLocked(ctx, isOwner = true)
            }

            if (config.silentAutoUpdateEnabled) {
                val next = computeNextBoundaryInstant(now, config.windowStartMinute, config.windowEndMinute)
                if (next != null) {
                    scheduleBoundaryAlarm(ctx, next)
                } else {
                    cancelBoundaryAlarm(ctx)
                }
            } else {
                cancelBoundaryAlarm(ctx)
            }

            emitStatusLocked(ctx)
        }
    }

    fun onScreenStateChanged(context: Context, screenOff: Boolean) {
        val ctx = context.applicationContext
        if (!getConfig(ctx).silentAutoUpdateEnabled && !isSilentWindowOpen(ctx)) return
        sync(ctx, screenOffOverride = screenOff)
    }

    fun closeSilentWindowNow(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (!prefs(ctx).getBoolean(KEY_WINDOW_OPEN, false)) {
                emitStatusLocked(ctx)
                return
            }
            val isOwner = runCatching {
                DeviceAdmin.policyManager.isDeviceOwnerApp(ctx.packageName)
            }.getOrDefault(false)
            closeSilentWindowLocked(ctx, isOwner = isOwner)
            emitStatusLocked(ctx)
        }
    }

    fun onPackageAdded(context: Context, packageName: String, isReplacing: Boolean) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            if (!p.getBoolean(KEY_WINDOW_OPEN, false)) return
            val preWindow = p.getStringSet(KEY_PRE_WINDOW_PACKAGES, emptySet()) ?: emptySet()
            if (!isUnauthorizedPackageInstall(packageName, preWindow, isReplacing, ctx.packageName)) {
                return
            }
            Timber.w("PlayStore silent window: rolling back unauthorized install $packageName (replacing=$isReplacing)")
            rollbackUnauthorizedPackage(ctx, packageName)
            incrementRolledBackCountLocked(ctx)
            emitStatusLocked(ctx)
        }
    }

    fun onPackageChanged(context: Context, packageName: String) {
        if (packageName == PLAY_STORE_PACKAGE) return
        val ctx = context.applicationContext
        if (packageName == ctx.packageName) return
        synchronized(lock) {
            val p = prefs(ctx)
            if (!p.getBoolean(KEY_WINDOW_OPEN, false)) return
            val preWindow = p.getStringSet(KEY_PRE_WINDOW_PACKAGES, emptySet()) ?: emptySet()
            if (packageName in preWindow) return
            if (!isPackageCurrentlyEnabledAndVisible(ctx, packageName)) return

            Timber.w("PlayStore silent window: rolling back newly enabled package $packageName")
            rollbackUnauthorizedPackage(ctx, packageName)
            incrementRolledBackCountLocked(ctx)
            emitStatusLocked(ctx)
        }
    }

    fun openPlayStoreSettings(context: Context): Boolean {
        val cfg = getConfig(context)
        if (cfg.blockPlayStoreUi) return false
        val launch = context.packageManager.getLaunchIntentForPackage(PLAY_STORE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching {
            context.startActivity(launch)
            true
        }.getOrDefault(false)
    }

    fun releaseForDeviceOwnerRemoval(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            cancelBoundaryAlarm(ctx)
            if (prefs(ctx).getBoolean(KEY_WINDOW_OPEN, false)) {
                val isOwner = runCatching {
                    DeviceAdmin.policyManager.isDeviceOwnerApp(ctx.packageName)
                }.getOrDefault(false)
                closeSilentWindowLocked(ctx, isOwner = isOwner)
            }
            prefs(ctx).edit(commit = true) { clear() }
            cachedBlockUi = false
            _status.value = PlayStoreUpdateStatus()
        }
    }

    private fun isDeviceUnattended(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        return !interactive || locked
    }

    private fun snapshotEnabledPackages(context: Context): Set<String> {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val isOwner = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        return runCatching {
            // Omit MATCH_UNINSTALLED_PACKAGES so dormant/disabled OEM stubs are excluded from snapshot.
            context.packageManager.getInstalledPackages(0)
                .asSequence()
                .filter { pkg ->
                    val enabled = pkg.applicationInfo?.enabled == true
                    val hidden = if (isOwner) {
                        runCatching { dpm.isApplicationHidden(admin, pkg.packageName) }.getOrDefault(false)
                    } else {
                        false
                    }
                    enabled && !hidden
                }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun isPackageCurrentlyEnabledAndVisible(context: Context, packageName: String): Boolean {
        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return false
        if (!appInfo.enabled) return false
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val hidden = runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)
        return !hidden
    }

    private fun rollbackUnauthorizedPackage(context: Context, packageName: String) {
        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!isSystem) {
            runCatching { ApkUpdateInstaller.uninstallForFreshRollback(context, packageName) }
                .onFailure { Timber.w(it, "Could not uninstall unauthorized package $packageName") }
        } else {
            val hidViaRepo = runCatching {
                AppRepository.setAppHidden(packageName, true)
                true
            }.getOrDefault(false)
            if (!hidViaRepo) {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                runCatching { dpm.setApplicationHidden(admin, packageName, true) }
                    .onFailure { Timber.w(it, "Could not hide re-enabled system app $packageName") }
            }
        }
    }

    private fun openSilentWindowLocked(context: Context) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val snapshot = snapshotEnabledPackages(context)
        val wasDisallowInstall = runCatching {
            dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
        }.getOrDefault(false)

        // Persist snapshot and restore flag BEFORE lifting DISALLOW_INSTALL_APPS.
        prefs(context).edit(commit = true) {
            putBoolean(KEY_WINDOW_OPEN, true)
            putStringSet(KEY_PRE_WINDOW_PACKAGES, snapshot)
            putBoolean(KEY_RESTORE_DISALLOW_INSTALL, wasDisallowInstall)
        }

        if (wasDisallowInstall) {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                .onFailure { Timber.w(it, "Could not clear DISALLOW_INSTALL_APPS for silent window") }
        }
    }

    private fun closeSilentWindowLocked(context: Context, isOwner: Boolean) {
        val p = prefs(context)
        val preWindow = p.getStringSet(KEY_PRE_WINDOW_PACKAGES, emptySet()) ?: emptySet()
        val restoreDisallowInstall = p.getBoolean(KEY_RESTORE_DISALLOW_INSTALL, false)

        if (isOwner && preWindow.isNotEmpty()) {
            val current = snapshotEnabledPackages(context)
            val unauthorized = findUnauthorizedPackages(current, preWindow, context.packageName)
            unauthorized.forEach { pkg ->
                Timber.w("PlayStore silent window close sweep: rolling back $pkg")
                rollbackUnauthorizedPackage(context, pkg)
                incrementRolledBackCountLocked(context)
            }
        }

        // Clear open flag before re-applying DISALLOW_INSTALL_APPS so ApkUpdateInstaller doesn't defer it.
        p.edit(commit = true) {
            putBoolean(KEY_WINDOW_OPEN, false)
            remove(KEY_PRE_WINDOW_PACKAGES)
            remove(KEY_RESTORE_DISALLOW_INSTALL)
            putLong(KEY_LAST_SWEEP_RTC, System.currentTimeMillis())
        }

        if (isOwner && restoreDisallowInstall) {
            if (ApkUpdateInstaller.hasActiveSessions(context)) {
                ApkUpdateInstaller.enqueueRestoreRestriction(context, UserManager.DISALLOW_INSTALL_APPS)
            } else {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                    .onFailure { Timber.w(it, "Could not restore DISALLOW_INSTALL_APPS after silent window") }
            }
        }
    }

    private fun incrementRolledBackCountLocked(context: Context) {
        val p = prefs(context)
        val next = p.getInt(KEY_ROLLED_BACK_COUNT, 0) + 1
        p.edit(commit = true) { putInt(KEY_ROLLED_BACK_COUNT, next) }
    }

    private fun emitStatusLocked(context: Context): PlayStoreUpdateStatus {
        val p = prefs(context)
        val config = getConfig(context)
        val open = p.getBoolean(KEY_WINDOW_OPEN, false)
        val rolledBack = p.getInt(KEY_ROLLED_BACK_COUNT, 0)
        val lastSweep = p.getLong(KEY_LAST_SWEEP_RTC, 0L)
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val suspended = runCatching {
            dpm.isDeviceOwnerApp(context.packageName) &&
                dpm.isPackageSuspended(admin, PLAY_STORE_PACKAGE)
        }.getOrDefault(false)

        val next = PlayStoreUpdateStatus(
            config = config,
            windowCurrentlyOpen = open,
            rolledBackCount = rolledBack,
            lastSweepRtcMs = lastSweep,
            playStoreSuspended = suspended
        )
        _status.value = next
        return next
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_WINDOW_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleBoundaryAlarm(context: Context, triggerAt: Instant) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerMs = triggerAt.toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        }
    }

    private fun cancelBoundaryAlarm(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarms.cancel(alarmIntent(context))
    }
}
