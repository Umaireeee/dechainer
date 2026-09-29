package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import timber.log.Timber

/**
 * How Déchaîner's own blocks — schedules, time windows, daily and group limits, reopening
 * cooldowns, impulse lock — take an app away: by suspending it. The icon stays, greyed out, and
 * everything about the app (permissions, settings, notifications, widgets) is kept.
 *
 * Earlier builds could hide apps instead. Android treats a hidden app as uninstalled, and on many
 * phones un-hiding it reset its permissions and settings as if newly installed — so hiding is
 * gone. [releaseAllHidden] un-hides anything those builds left hidden; it runs at every start
 * and before Device Owner is given up, and only ever touches apps Déchaîner itself hid.
 */
object Blocker {
    private const val PREFS = "blocker"
    // Record kept by the builds that hid apps. Read only to undo what they did.
    private const val KEY_HIDDEN_BY_US = "hidden_by_blocker"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val recordLock = Any()

    private fun hiddenByUs(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN_BY_US, emptySet())?.toSet() ?: emptySet()

    private fun saveHiddenByUs(context: Context, pkgs: Set<String>) =
        prefs(context).edit(commit = true) {
            if (pkgs.isEmpty()) remove(KEY_HIDDEN_BY_US) else putStringSet(KEY_HIDDEN_BY_US, pkgs)
        }

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

    /** Lifts the suspension on [pkgs]. */
    fun release(context: Context, dpm: DevicePolicyManager, admin: ComponentName, pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        try {
            dpm.setPackagesSuspended(admin, pkgs.toTypedArray(), false)
        } catch (e: Exception) {
            Timber.w(e, "Could not unsuspend %s", pkgs)
        }
    }

    /** The person hid [pkg] themselves in the Apps tab: from now on it's theirs, never ours. */
    fun forget(context: Context, pkg: String) {
        synchronized(recordLock) {
            val ours = hiddenByUs(context)
            if (pkg in ours) saveHiddenByUs(context, ours - pkg)
        }
    }

    /**
     * Un-hides every app an older build hid, and Déchaîner itself if a still older build hid its
     * own package. Whatever should still be blocked is suspended again by the next sync.
     */
    fun releaseAllHidden(context: Context, dpm: DevicePolicyManager, admin: ComponentName): Unit = synchronized(recordLock) {
        val ours = hiddenByUs(context)
        if (ours.isEmpty()) return@synchronized
        val left = ours.filterNot { pkg ->
            try { dpm.setApplicationHidden(admin, pkg, false); true } catch (_: Exception) { false }
        }.toSet()
        saveHiddenByUs(context, left)
        AppRepository.invalidateCache()
        Timber.d("Un-hid %d app(s) left hidden by an older build", ours.size - left.size)
    }

    /** Startup form of [releaseAllHidden]: a no-op without Device Owner or anything to undo. */
    fun migrateFromHiding(context: Context) {
        val ctx = context.applicationContext
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, io.github.warleysr.dechainer.DechainerDeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(admin)) return
            releaseAllHidden(ctx, dpm, admin)
            if (dpm.isApplicationHidden(admin, ctx.packageName)) dpm.setApplicationHidden(admin, ctx.packageName, false)
        } catch (e: Exception) {
            Timber.w(e, "Could not undo hiding from an older build")
        }
    }
}
