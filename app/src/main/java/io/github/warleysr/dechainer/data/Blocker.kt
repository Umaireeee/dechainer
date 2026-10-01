package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import timber.log.Timber

/**
 * How Déchaîner's own blocks — schedules, time windows, daily and group limits, reopening
 * cooldowns, the urge lock — take an app away: by suspending it. The icon stays, greyed out, and
 * everything about the app (permissions, settings, notifications, widgets) is kept.

 */
object Blocker {
    private const val PREFS = "blocker"
    /** Installed for this user — hidden apps included, which a plain package lookup would miss. */
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Already out of reach — suspended, or hidden (by hand in the Apps tab, or left over from an
     * older build) — so there's nothing to add.
     */
    fun isBlocked(dpm: DevicePolicyManager, admin: ComponentName, pkg: String): Boolean {
        val hidden = try { dpm.isApplicationHidden(admin, pkg) } catch (_: Exception) { false }
        if (hidden) return true
        return try { dpm.isPackageSuspended(admin, pkg) } catch (_: Exception) { false }
    }

    /** Suspends [pkgs]. Returns the ones Android refused. */
    fun block(context: Context, dpm: DevicePolicyManager, admin: ComponentName, pkgs: Collection<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()
        // Throws if Déchaîner is an admin but not Device Owner; a failed block, not a crash.
        return try {
            dpm.setPackagesSuspended(admin, pkgs.toTypedArray(), true).toSet()
        } catch (e: Exception) {
            Timber.w(e, "Could not suspend %s", pkgs)
            pkgs.toSet()
        }
    }

    /**
     * Lifts the suspension on [pkgs]. Returns the ones Android did not release, so the caller keeps
     * owning them and tries again instead of forgetting an app that is still suspended.
     */
    fun release(context: Context, dpm: DevicePolicyManager, admin: ComponentName, pkgs: Collection<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()
        return try {
            dpm.setPackagesSuspended(admin, pkgs.toTypedArray(), false).toSet()
        } catch (e: Exception) {
            Timber.w(e, "Could not unsuspend %s", pkgs)
            pkgs.toSet()
        }
    }
}
