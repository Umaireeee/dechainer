package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import io.github.warleysr.dechainer.AlarmTickReceiver
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class UpdatePassState(
    val active: Boolean = false,
    val remainingMs: Long = 0L,
    val blockedNewInstalls: Int = 0
)

/**
 * Grants a short (10-minute) Play Store Updates-Only Pass while keeping brand-new app installs
 * blocked: any newly installed package not present before the pass started (or with
 * `EXTRA_REPLACING == false`) is immediately and silently uninstalled via Device Owner.
 */
object PlayStoreUpdateManager {
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val ACTION_PASS_END = "io.github.warleysr.dechainer.UPDATE_PASS_END"
    const val PASS_DURATION_MS = 10L * 60L * 1000L

    private const val ALARM_REQUEST_CODE = 7315
    private const val PREFS_NAME = "play_store_update_pass"
    private const val KEY_ACTIVE = "active"
    private const val KEY_START_RTC = "start_rtc"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_PRE_PASS_PACKAGES = "pre_pass_packages"
    private const val KEY_RESTORE_DISALLOW_INSTALL = "restore_disallow_install"
    private const val KEY_RESTORE_PLAY_SUSPENDED = "restore_play_suspended"
    private const val KEY_BLOCKED_COUNT = "blocked_count"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(UpdatePassState())
    val state: StateFlow<UpdatePassState> = _state.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun isUnauthorizedPackageInstall(
        packageName: String,
        prePassPackages: Set<String>,
        isReplacing: Boolean,
        ownPackageName: String
    ): Boolean {
        if (packageName == ownPackageName) return false
        return !(isReplacing && packageName in prePassPackages)
    }

    internal fun findUnauthorizedNewPackages(
        currentPackages: Set<String>,
        prePassPackages: Set<String>,
        ownPackageName: String
    ): Set<String> = (currentPackages - prePassPackages) - ownPackageName

    private fun readBootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    fun isPassActive(context: Context): Boolean = getRemainingMillis(context) > 0L

    fun getRemainingMillis(
        context: Context,
        nowRtc: Long = System.currentTimeMillis(),
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = readBootCount(context)
    ): Long {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            if (!p.getBoolean(KEY_ACTIVE, false)) {
                _state.value = UpdatePassState(active = false, remainingMs = 0L, blockedNewInstalls = 0)
                return 0L
            }

            val startRtc = p.getLong(KEY_START_RTC, 0L)
            val startElapsed = p.getLong(KEY_START_ELAPSED, 0L)
            val storedBoot = p.getInt(KEY_BOOT_COUNT, -1)
            val rebooted = (storedBoot != -1 && bootCount != -1 && bootCount != storedBoot) ||
                nowElapsed < startElapsed

            val remaining = if (rebooted) {
                ((startRtc + PASS_DURATION_MS) - nowRtc).coerceAtMost(PASS_DURATION_MS)
            } else {
                (startElapsed + PASS_DURATION_MS) - nowElapsed
            }

            if (remaining <= 0L) {
                endPass(ctx)
                return 0L
            }

            _state.value = UpdatePassState(
                active = true,
                remainingMs = remaining,
                blockedNewInstalls = p.getInt(KEY_BLOCKED_COUNT, 0)
            )
            return remaining
        }
    }

    fun startPass(context: Context): Boolean {
        val ctx = context.applicationContext
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return false
        if (SecurityManager.getImpulseBlockRemainingTime(ctx) > 0L) return false

        synchronized(lock) {
            if (prefs(ctx).getBoolean(KEY_ACTIVE, false)) {
                val rem = getRemainingMillis(ctx)
                if (rem > 0L) return true
            }

            val installed = runCatching {
                ctx.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    .map { it.packageName }
                    .toSet()
            }.getOrDefault(emptySet())

            val wasDisallowInstall = runCatching {
                dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
            }.getOrDefault(false)
            val wasPlaySuspended = runCatching {
                dpm.isPackageSuspended(admin, PLAY_STORE_PACKAGE)
            }.getOrDefault(false)

            // Persist snapshot and restore flags BEFORE lifting restrictions.
            prefs(ctx).edit(commit = true) {
                putBoolean(KEY_ACTIVE, true)
                putLong(KEY_START_RTC, System.currentTimeMillis())
                putLong(KEY_START_ELAPSED, SystemClock.elapsedRealtime())
                putInt(KEY_BOOT_COUNT, readBootCount(ctx))
                putStringSet(KEY_PRE_PASS_PACKAGES, installed)
                putBoolean(KEY_RESTORE_DISALLOW_INSTALL, wasDisallowInstall)
                putBoolean(KEY_RESTORE_PLAY_SUSPENDED, wasPlaySuspended)
                putInt(KEY_BLOCKED_COUNT, 0)
            }

            if (wasDisallowInstall) {
                runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                    .onFailure { Timber.w(it, "Could not clear DISALLOW_INSTALL_APPS for update pass") }
            }
            if (wasPlaySuspended) {
                synchronized(SuspensionCoordinator.lock) {
                    runCatching { dpm.setPackagesSuspended(admin, arrayOf(PLAY_STORE_PACKAGE), false) }
                        .onFailure { Timber.w(it, "Could not unsuspend Play Store for update pass") }
                    AppRepository.invalidateCache()
                }
            }

            scheduleEndAlarm(ctx, PASS_DURATION_MS)
            _state.value = UpdatePassState(active = true, remainingMs = PASS_DURATION_MS, blockedNewInstalls = 0)
            return true
        }
    }

    fun onPackageAdded(context: Context, packageName: String, isReplacing: Boolean) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ACTIVE, false)) return
        val prePass = p.getStringSet(KEY_PRE_PASS_PACKAGES, emptySet()) ?: emptySet()
        if (!isUnauthorizedPackageInstall(packageName, prePass, isReplacing, ctx.packageName)) {
            return
        }

        Timber.w("Update Pass: blocking unauthorized new install $packageName (replacing=$isReplacing)")
        val nextBlocked = p.getInt(KEY_BLOCKED_COUNT, 0) + 1
        p.edit { putInt(KEY_BLOCKED_COUNT, nextBlocked) }
        _state.value = _state.value.copy(blockedNewInstalls = nextBlocked)

        runCatching { ApkUpdateInstaller.uninstallForFreshRollback(ctx, packageName) }
        mainHandler.post {
            Toast.makeText(ctx, R.string.update_pass_blocked_new_app, Toast.LENGTH_LONG).show()
        }
    }

    fun endPass(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            cancelEndAlarm(ctx)
            val p = prefs(ctx)
            if (!p.getBoolean(KEY_ACTIVE, false)) {
                _state.value = UpdatePassState(active = false, remainingMs = 0L, blockedNewInstalls = 0)
                return
            }

            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            val isOwner = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

            val prePass = p.getStringSet(KEY_PRE_PASS_PACKAGES, emptySet()) ?: emptySet()
            val restoreInstall = p.getBoolean(KEY_RESTORE_DISALLOW_INSTALL, false)
            val restorePlaySuspended = p.getBoolean(KEY_RESTORE_PLAY_SUSPENDED, false)

            if (isOwner && prePass.isNotEmpty()) {
                val current = runCatching {
                    ctx.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        .map { it.packageName }
                        .toSet()
                }.getOrDefault(emptySet())
                val stray = findUnauthorizedNewPackages(current, prePass, ctx.packageName)
                stray.forEach { pkg ->
                    Timber.w("Update Pass sweep: removing unauthorized package $pkg")
                    runCatching { ApkUpdateInstaller.uninstallForFreshRollback(ctx, pkg) }
                }
            }

            // Clear active flag before restoring restrictions so ApkUpdateInstaller doesn't skip it.
            p.edit(commit = true) { clear() }
            _state.value = UpdatePassState(active = false, remainingMs = 0L, blockedNewInstalls = 0)

            if (isOwner) {
                if (restoreInstall) {
                    if (ApkUpdateInstaller.hasActiveSessions(ctx)) {
                        ApkUpdateInstaller.enqueueRestoreRestriction(ctx, UserManager.DISALLOW_INSTALL_APPS)
                    } else {
                        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                            .onFailure { Timber.w(it, "Could not restore DISALLOW_INSTALL_APPS") }
                    }
                }
                if (restorePlaySuspended) {
                    synchronized(SuspensionCoordinator.lock) {
                        runCatching { dpm.setPackagesSuspended(admin, arrayOf(PLAY_STORE_PACKAGE), true) }
                            .onFailure { Timber.w(it, "Could not re-suspend Play Store") }
                        AppRepository.invalidateCache()
                    }
                }
            }
        }
    }

    fun sync(context: Context) {
        val ctx = context.applicationContext
        val remaining = getRemainingMillis(ctx)
        if (remaining > 0L) {
            scheduleEndAlarm(ctx, remaining)
        }
    }

    fun openPlayStoreUpdates(context: Context): Boolean {
        val updatesIntent = Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS")
            .setPackage(PLAY_STORE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching {
            context.startActivity(updatesIntent)
            true
        }.getOrDefault(false)
        if (launched) return true

        val fallback = context.packageManager.getLaunchIntentForPackage(PLAY_STORE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching {
            context.startActivity(fallback)
            true
        }.getOrDefault(false)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_PASS_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleEndAlarm(context: Context, delayMillis: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(1_000L)
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelEndAlarm(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarms.cancel(alarmIntent(context))
    }
}
