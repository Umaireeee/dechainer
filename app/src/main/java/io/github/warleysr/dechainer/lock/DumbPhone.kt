package io.github.warleysr.dechainer.lock

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber

/**
 * The protection that stays on once the first study session or lockdown has started: the adult-site
 * DNS filter. It is "armed" once, by [arm], and from then on [LockEngine] keeps it in place on every
 * pass, whether or not a session is running. The sessions themselves are in [StudyMode]. It needs
 * Device Owner and nothing else.
 *
 * (The name is from the earlier always-locked version of the app; the stored key is unchanged so a
 * phone that was already locked is simply "armed".)
 */
object DumbPhone {
    private const val PREFS = "dumb_phone"
    private const val KEY_STARTED_AT = "started_at"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the protection was switched on, on the trusted clock, or 0 when it has not been. */
    fun startedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_STARTED_AT, 0L)

    /** True once the protection is on: the DNS filter is then enforced at every hour. */
    fun isActive(ctx: Context): Boolean = startedAt(ctx) > 0L

    fun isDeviceOwner(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(ctx.packageName)
    }

    /**
     * Switches the protection on if it is not on yet. Returns false when Device Owner is missing;
     * nothing is changed then. Called by the first start of a study session or a lockdown.
     */
    fun arm(context: Context): Boolean {
        val ctx = context.applicationContext
        if (!isDeviceOwner(ctx)) return false
        if (isActive(ctx)) return true
        val now = TrustedClock.checkpoint(ctx)
        prefs(ctx).edit(commit = true) { putLong(KEY_STARTED_AT, now) }
        enforce(ctx)
        LockEngine.requestSync(ctx)
        return true
    }

    /**
     * Clears the protection for good, after the exit wait has run out and Device Owner is going: any
     * running session or lockdown is ended and the DNS goes back to automatic.
     */
    fun release(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { remove(KEY_STARTED_AT) }
        LockStateStore.clearStudy(ctx)
        LockStateStore.clearLockdown(ctx)
        SecurityManager.cancelForcedRemoval(ctx)
        try {
            DnsGuard.clearAll(ctx)
            DeviceOwnerRepository.setPrivateDnsAutomatic()
        } catch (e: Exception) {
            Timber.w(e, "DNS not returned to automatic")
        }
        LockEngine.requestSync(ctx)
    }

    /**
     * Run on every lock pass while the protection is on: puts back what a phone may have lost (the DNS
     * filter). Idempotent, and it never raises anything.
     */
    fun enforce(context: Context) {
        val ctx = context.applicationContext
        if (!isActive(ctx)) return
        DnsGuard.ensureFiltered(ctx)
    }
}
