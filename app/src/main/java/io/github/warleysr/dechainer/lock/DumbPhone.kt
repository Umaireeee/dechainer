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
 * The dumb-phone lock, as stored on the phone. It starts once, from the Start screen, and from then on
 * [LockEngine] plans [DumbPhoneRules.schedule] on every pass. It needs Device Owner and nothing else.
 */
object DumbPhone {
    private const val PREFS = "dumb_phone"
    private const val KEY_STARTED_AT = "started_at"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the lock started on the trusted clock, or 0 when it has not. */
    fun startedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_STARTED_AT, 0L)

    fun isActive(ctx: Context): Boolean = startedAt(ctx) > 0L

    fun isDeviceOwner(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(ctx.packageName)
    }

    /** Starts the lock. Returns false when Device Owner is missing; nothing is changed then. */
    fun start(context: Context): Boolean {
        val ctx = context.applicationContext
        if (!isDeviceOwner(ctx)) return false
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

    /** Clears the lock for good: after the undo, or after the exit wait has run out and Device Owner is going. */
    fun release(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { remove(KEY_STARTED_AT) }
        SecurityManager.cancelForcedRemoval(ctx)
        Grayscale.set(ctx, false)
        try {
            DnsGuard.clearAll(ctx)
            DeviceOwnerRepository.setPrivateDnsAutomatic()
        } catch (e: Exception) {
            Timber.w(e, "DNS not returned to automatic")
        }
        LockEngine.requestSync(ctx)
    }

    /**
     * Run on every lock pass while the lock is on: puts back what a phone may have lost (the DNS
     * filter). Idempotent, and it never raises anything.
     */
    fun enforce(context: Context, locked: Boolean = true) {
        val ctx = context.applicationContext
        if (!isActive(ctx)) return
        DnsGuard.ensureFiltered(ctx)
        // Black and white while locked, colour in the open window.
        Grayscale.set(ctx, locked)
    }
}
