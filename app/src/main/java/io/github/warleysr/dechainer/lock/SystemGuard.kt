package io.github.warleysr.dechainer.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import timber.log.Timber

/**
 * The device policies that hold all the time, whatever is running (blueprint 5.4 and 9.2). Every
 * step is idempotent and reads before it writes, so the engine can run it on every wake-up, and each
 * step stands alone: one that is refused never stops the others.
 *
 * - Automatic date, time and time zone are forced on (the matching restriction comes with every
 *   plan, see [LockRestrictions.DATE_TIME]).
 * - Force stop and Clear data are disabled for this app (`setUserControlDisabledPackages`), so
 *   neither can be used to kill a lock or wipe its state.
 */
object SystemGuard {
    fun apply(context: Context) {
        val ctx = context.applicationContext
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
        val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)

        step("automatic time") { if (!dpm.getAutoTimeEnabled(admin)) dpm.setAutoTimeEnabled(admin, true) }
        step("automatic time zone") { if (!dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin, true) }
        step("force stop and clear data") {
            val disabled = dpm.getUserControlDisabledPackages(admin) ?: emptyList()
            // The list is replaced as a whole: keep whatever else was on it.
            if (ctx.packageName !in disabled) dpm.setUserControlDisabledPackages(admin, disabled + ctx.packageName)
        }
        // The system rules of blueprint 9.3 that hold all the time, not only during a brick: the
        // ways around a lock (a VPN or a browser's own DNS, uninstalling apps, adding a user).
        step("app control") { dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL) }
        step("VPN settings") { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
        step("private DNS settings") { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) }
        step("adding users") { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        step("uninstall of this app") { dpm.setUninstallBlocked(admin, ctx.packageName, true) }
    }

    private fun step(name: String, work: () -> Unit) {
        try {
            work()
        } catch (e: Exception) {
            Timber.w(e, "System guard: $name not applied")
        }
    }
}
