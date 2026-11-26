package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.edit
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.vpn.LocalDnsShieldManager
import timber.log.Timber

/**
 * One-time migration for v2.4.5 that wipes every previously applied restriction, suspension,
 * app restriction bundle, hidden/uninstall-blocked flag, VPN, Private DNS pin, timelock, and
 * forced removal timer while preserving Device Owner privileges.
 */
object CleanResetMigration {
    private const val PREFS = "dechainer_migrations"
    private const val KEY_CLEAN_RESET_V245 = "clean_reset_v245_done"
    private val lock = Any()

    internal fun resetForTest(context: Context) {
        synchronized(lock) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit(commit = true) { clear() }
        }
    }

    fun runIfNeeded(context: Context): Boolean {
        val ctx = context.applicationContext
        synchronized(lock) {
            val migrationPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (migrationPrefs.getBoolean(KEY_CLEAN_RESET_V245, false)) {
                return false
            }

            runCatching { SettingsLock.setLocked(false) }
            runCatching { LocalDnsShieldManager.releaseForDeviceOwnerRemoval(ctx) }
            runCatching { BrowserHardeningManager.releaseForDeviceOwnerRemoval(ctx) }
            runCatching { PlayStoreUpdateManager.releaseForDeviceOwnerRemoval(ctx) }
            runCatching { DnsGuard.forget(ctx) }
            runCatching { TimelockManager.cancelAll(ctx) }
            runCatching { SecurityManager.cancelForcedRemoval(ctx) }

            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            val isOwner = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

            if (isOwner) {
                runCatching {
                    dpm.getUserRestrictions(admin).keySet().toList().forEach { key ->
                        runCatching { dpm.clearUserRestriction(admin, key) }
                    }
                }
                runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
                runCatching {
                    if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                        dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                    }
                }

                val hiddenPrefs = ctx.getSharedPreferences("hidden_apps_prefs", Context.MODE_PRIVATE)
                hiddenPrefs.getStringSet("hidden_packages", emptySet()).orEmpty().forEach { pkg ->
                    runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                }

                val manualSuspended = ctx.getSharedPreferences("manual_suspensions", Context.MODE_PRIVATE).all.keys
                val blockSuspended = ctx.getSharedPreferences("block_suspensions", Context.MODE_PRIVATE).all.keys
                val downtimeHeld = runCatching { DowntimeRepository.getHeld(ctx) }.getOrDefault(emptySet())
                val impulseHeld = runCatching { SecurityManager.getActiveImpulseSuspension(ctx) }.getOrDefault(emptySet())
                val suspendedCandidates = (manualSuspended + blockSuspended + downtimeHeld + impulseHeld)
                    .toMutableSet()

                runCatching {
                    ctx.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        .forEach { pkgInfo ->
                            val pkg = pkgInfo.packageName
                            if (pkg == ctx.packageName) return@forEach
                            if (runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)) {
                                suspendedCandidates.add(pkg)
                            }
                            if (runCatching { dpm.isUninstallBlocked(admin, pkg) }.getOrDefault(false)) {
                                runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
                            }
                            val existingRestrictions = runCatching {
                                dpm.getApplicationRestrictions(admin, pkg)
                            }.getOrNull()
                            if (existingRestrictions != null && !existingRestrictions.isEmpty) {
                                runCatching { dpm.setApplicationRestrictions(admin, pkg, Bundle()) }
                            }
                        }
                }

                val allSuspended = suspendedCandidates.filter { it != ctx.packageName }.distinct()
                if (allSuspended.isNotEmpty()) {
                    runCatching { dpm.setPackagesSuspended(admin, allSuspended.toTypedArray(), false) }
                }
            }

            listOf(
                "hidden_apps_prefs",
                "manual_suspensions",
                "block_suspensions",
                "security_prefs",
                "activity_blocker_prefs",
                "reopen_times",
                "play_store_update_pass",
                AppTimeLimits.PREFS_NAME,
                AppTimeLimits.WEEKLY_PREFS_NAME,
                AppTimeWindows.PREFS_NAME,
                AppGroupRepository.PREFS_NAME
            ).forEach { name ->
                runCatching {
                    ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                }
            }

            runCatching { DowntimeRepository.save(emptyList(), ctx) }
            runCatching { DowntimeRepository.setHeld(ctx, emptySet()) }
            runCatching { DowntimeManager.sync(ctx) }
            runCatching { AppRepository.invalidateCache() }

            migrationPrefs.edit(commit = true) {
                putBoolean(KEY_CLEAN_RESET_V245, true)
            }
            Timber.i("CleanResetMigration v2.4.5 completed cleanly")
            return true
        }
    }
}
