package io.github.warleysr.dechainer.data

import android.content.Context
import android.os.UserManager
import androidx.core.content.edit
import timber.log.Timber

/**
 * Single synchronized coordinator for temporary `UserManager.DISALLOW_INSTALL_APPS` changes across
 * APK updates, Play Store silent update windows, and Accessibility Service unexpected unbinds.
 *
 * Keeps the user's baseline restriction state separate from active leases so overlapping components
 * cannot permanently drop or accidentally retain `DISALLOW_INSTALL_APPS`.
 */
object InstallRestrictionCoordinator {
    const val LEASE_APK_UPDATE = "apk_update"
    const val LEASE_PLAY_STORE_WINDOW = "play_store_window"
    const val LEASE_ACCESSIBILITY_UNBIND = "accessibility_unbind"

    private const val PREFS = "install_restriction_coordinator_prefs"
    private const val KEY_BASELINE_INSTALL_BLOCKED = "baseline_install_blocked"
    private const val KEY_HAS_BASELINE = "has_baseline"
    private const val KEY_LIFT_LEASES = "lift_leases"
    private const val KEY_ENFORCE_LEASES = "enforce_leases"

    val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun acquireLiftLease(context: Context, lease: String) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return

            val p = prefs(ctx)
            val liftLeases = p.getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty().toMutableSet()
            val enforceLeases = p.getStringSet(KEY_ENFORCE_LEASES, emptySet()).orEmpty()

            if (!p.getBoolean(KEY_HAS_BASELINE, false)) {
                val live = runCatching {
                    dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
                }.getOrDefault(false)
                // If enforce leases already added it temporarily, the user baseline was false.
                val baseline = live && enforceLeases.isEmpty()
                p.edit(commit = true) {
                    putBoolean(KEY_HAS_BASELINE, true)
                    putBoolean(KEY_BASELINE_INSTALL_BLOCKED, baseline)
                }
            }

            if (liftLeases.add(lease)) {
                p.edit(commit = true) { putStringSet(KEY_LIFT_LEASES, liftLeases) }
            }

            if (enforceLeases.isEmpty()) {
                runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                    .onFailure { Timber.w(it, "Could not clear DISALLOW_INSTALL_APPS for lease %s", lease) }
            }
        }
    }

    fun releaseLiftLease(context: Context, lease: String) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            val liftLeases = p.getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty().toMutableSet()
            liftLeases.remove(lease)
            p.edit(commit = true) { putStringSet(KEY_LIFT_LEASES, liftLeases) }
            reconcileLocked(ctx)
        }
    }

    fun acquireEnforceLease(context: Context, lease: String) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return

            val p = prefs(ctx)
            val liftLeases = p.getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty()
            val enforceLeases = p.getStringSet(KEY_ENFORCE_LEASES, emptySet()).orEmpty().toMutableSet()

            if (!p.getBoolean(KEY_HAS_BASELINE, false)) {
                val live = runCatching {
                    dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
                }.getOrDefault(false)
                val baseline = live && liftLeases.isEmpty()
                p.edit(commit = true) {
                    putBoolean(KEY_HAS_BASELINE, true)
                    putBoolean(KEY_BASELINE_INSTALL_BLOCKED, baseline)
                }
            }

            if (enforceLeases.add(lease)) {
                p.edit(commit = true) { putStringSet(KEY_ENFORCE_LEASES, enforceLeases) }
            }
            reconcileLocked(ctx)
        }
    }

    fun releaseEnforceLease(context: Context, lease: String) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            val enforceLeases = p.getStringSet(KEY_ENFORCE_LEASES, emptySet()).orEmpty().toMutableSet()
            enforceLeases.remove(lease)
            p.edit(commit = true) { putStringSet(KEY_ENFORCE_LEASES, enforceLeases) }
            reconcileLocked(ctx)
        }
    }

    /** Called when the user explicitly adds or removes `DISALLOW_INSTALL_APPS` in Restrictions. */
    fun setUserBaseline(context: Context, blocked: Boolean) {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            val liftLeases = p.getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty()
            val enforceLeases = p.getStringSet(KEY_ENFORCE_LEASES, emptySet()).orEmpty()
            if (liftLeases.isEmpty() && enforceLeases.isEmpty()) {
                p.edit(commit = true) {
                    remove(KEY_HAS_BASELINE)
                    remove(KEY_BASELINE_INSTALL_BLOCKED)
                }
            } else {
                p.edit(commit = true) {
                    putBoolean(KEY_HAS_BASELINE, true)
                    putBoolean(KEY_BASELINE_INSTALL_BLOCKED, blocked)
                }
            }
            reconcileLocked(ctx)
        }
    }

    fun isBaselineInstallBlocked(context: Context): Boolean {
        val ctx = context.applicationContext
        synchronized(lock) {
            val p = prefs(ctx)
            if (p.getBoolean(KEY_HAS_BASELINE, false)) {
                return p.getBoolean(KEY_BASELINE_INSTALL_BLOCKED, false)
            }
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return false
            return runCatching {
                dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
            }.getOrDefault(false)
        }
    }

    fun hasActiveLiftLease(context: Context, lease: String): Boolean {
        synchronized(lock) {
            return prefs(context).getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty().contains(lease)
        }
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            prefs(context).edit(commit = true) { clear() }
        }
    }

    private fun reconcileLocked(ctx: Context) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) return

        val p = prefs(ctx)
        val liftLeases = p.getStringSet(KEY_LIFT_LEASES, emptySet()).orEmpty()
        val enforceLeases = p.getStringSet(KEY_ENFORCE_LEASES, emptySet()).orEmpty()
        val baseline = p.getBoolean(KEY_BASELINE_INSTALL_BLOCKED, false)

        val shouldBeRestricted = when {
            enforceLeases.isNotEmpty() -> true
            liftLeases.isNotEmpty() -> false
            else -> baseline
        }

        val currentRestricted = runCatching {
            dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_APPS)
        }.getOrDefault(false)

        if (shouldBeRestricted && !currentRestricted) {
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                .onFailure { Timber.w(it, "Could not restore DISALLOW_INSTALL_APPS") }
        } else if (!shouldBeRestricted && currentRestricted) {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                .onFailure { Timber.w(it, "Could not clear DISALLOW_INSTALL_APPS") }
        }

        if (liftLeases.isEmpty() && enforceLeases.isEmpty()) {
            p.edit(commit = true) {
                remove(KEY_HAS_BASELINE)
                remove(KEY_BASELINE_INSTALL_BLOCKED)
            }
        }
    }
}
