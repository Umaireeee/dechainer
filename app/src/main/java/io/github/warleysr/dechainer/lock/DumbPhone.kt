package io.github.warleysr.dechainer.lock

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber

/**
 * The dumb-phone lock, as stored on the phone. It starts once, from the Start screen, and from then on
 * [LockEngine] plans [DumbPhoneRules.schedule] on every pass. What it needs from the phone:
 * Device Owner, and Usage access (it is what measures WhatsApp's 45 minutes).
 */
object DumbPhone {
    private const val PREFS = "dumb_phone"
    private const val KEY_STARTED_AT = "started_at"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the lock started on the trusted clock, or 0 when it has not. */
    fun startedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_STARTED_AT, 0L)

    fun isActive(ctx: Context): Boolean = startedAt(ctx) > 0L

    /** What is still missing before the lock can start. */
    class Readiness(val deviceOwner: Boolean, val usageAccess: Boolean) {
        val ready: Boolean get() = deviceOwner && usageAccess
    }

    fun readiness(ctx: Context): Readiness {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return Readiness(
            deviceOwner = dpm.isDeviceOwnerApp(ctx.packageName),
            usageAccess = TimeLimits.hasUsageAccess(ctx)
        )
    }

    /** Starts the lock. Returns false when something is missing; nothing is changed then. */
    fun start(context: Context): Boolean {
        val ctx = context.applicationContext
        if (!readiness(ctx).ready) return false
        if (isActive(ctx)) return true
        val now = TrustedClock.checkpoint(ctx)
        prefs(ctx).edit(commit = true) { putLong(KEY_STARTED_AT, now) }
        // A wait left over from an earlier try must not run the new lock out.
        SecurityManager.cancelForcedRemoval(ctx)
        enforce(ctx)
        LockEngine.requestSync(ctx)
        return true
    }

    /** Undoes the lock inside its first ten minutes. Returns false when that window is over. */
    fun undo(context: Context): Boolean {
        val ctx = context.applicationContext
        val now = TrustedClock.now(ctx)
        if (!DumbPhoneRules.undoAvailable(now, startedAt(ctx))) return false
        release(ctx)
        return true
    }

    /** Clears the lock for good: after the undo, or after the exit wait has run out and Device Owner is gone. */
    fun release(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { remove(KEY_STARTED_AT) }
        SecurityManager.cancelForcedRemoval(ctx)
        try {
            TimeLimits.set(ctx, DumbPhoneRules.WHATSAPP, 0)
        } catch (e: Exception) {
            Timber.w(e, "WhatsApp limit not cleared")
        }
        try {
            DnsGuard.clearAll(ctx)
            DeviceOwnerRepository.setPrivateDnsAutomatic()
        } catch (e: Exception) {
            Timber.w(e, "DNS not returned to automatic")
        }
        LockEngine.requestSync(ctx)
    }

    /**
     * Run on every lock pass while the lock is on: puts back what a phone may have lost (the
     * WhatsApp limit, the DNS filter). Idempotent, and it never raises anything.
     */
    fun enforce(context: Context) {
        val ctx = context.applicationContext
        if (!isActive(ctx)) return
        try {
            if (TimeLimits.all(ctx)[DumbPhoneRules.WHATSAPP] != DumbPhoneRules.WHATSAPP_LIMIT_MINUTES) {
                TimeLimits.set(ctx, DumbPhoneRules.WHATSAPP, DumbPhoneRules.WHATSAPP_LIMIT_MINUTES)
            }
        } catch (e: Exception) {
            Timber.w(e, "WhatsApp limit not applied")
        }
        DnsGuard.ensureFiltered(ctx)
    }
}
