package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.lock.SettingsFreeze
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Keeps the Private DNS filter chosen in Déchaîner switched on.
 *
 * Android lets a Device Owner set the device's DNS, and offers a restriction meant to stop the user
 * changing it. On some phones — Xiaomi's redesigned Settings among them — the Settings screen
 * doesn't honour that restriction, so the filter could be turned off in two taps. Worse, the app
 * never recorded which DNS it had set; it only read the live value back, so once that changed
 * there was nothing to compare against and nothing to restore.
 *
 * This remembers the chosen provider and puts it back whenever the live setting drifts: straight
 * away via a settings observer, and again on every sync as a fallback in case the observer is
 * never notified.
 *
 * It can't strand the phone offline. Android probes a provider before accepting it, so on a network
 * that blocks Private DNS the restore just fails and the internet keeps working.
 */
object DnsGuard {
    private const val PREFS = "dns_guard"
    private const val KEY_PINNED_HOST = "pinned_host"

    // One thread: setting the DNS makes Android probe the provider over the network, which can
    // take seconds, and restores must never overlap or run on the main thread.
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var observing = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pinnedHost(context: Context): String? = prefs(context).getString(KEY_PINNED_HOST, null)

    /** Records [host] as the provider to keep enforcing. Call only once Android has accepted it. */
    fun remember(context: Context, host: String) {
        if (!SettingsFreeze.allowWrite(context, "DNS filter")) return
        prefs(context).edit(commit = true) { putString(KEY_PINNED_HOST, host) }
    }

    /** Drops the pinned provider, so the guard stops restoring it. Used when filtering is turned off. */
    fun forget(context: Context) {
        if (!SettingsFreeze.allowWrite(context, "DNS filter")) return
        prefs(context).edit(commit = true) { remove(KEY_PINNED_HOST) }
    }

    /** True when a filter has been chosen in the app and is the one actually in effect. */
    fun isActive(context: Context): Boolean {
        val pinned = pinnedHost(context) ?: return false
        return !needsRestore(pinned, currentHost())
    }

    /**
     * Whether the live setting has drifted from the pinned provider. Pure, so it's unit-tested.
     * Host names compare case-insensitively, as DNS names do. With nothing pinned there is
     * nothing to restore — the guard never invents a DNS the person didn't choose.
     */
    internal fun needsRestore(pinnedHost: String?, currentHost: String?): Boolean =
        pinnedHost != null && !pinnedHost.equals(currentHost, ignoreCase = true)

    private fun currentHost(): String? = try {
        DeviceOwnerRepository.getPrivateDNS()
    } catch (_: Exception) {
        null
    }

    /** Restores the pinned provider if it has drifted. Returns at once; the work runs off-thread. */
    fun enforce(context: Context) {
        val ctx = context.applicationContext
        worker.execute {
            try {
                val pinned = pinnedHost(ctx) ?: return@execute
                if (!needsRestore(pinned, currentHost())) return@execute
                // Enforcement, not a change of setting: it must keep working on a punishment day.
                val result = DeviceOwnerRepository.restorePrivateDns(pinned)
                if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    Timber.i("DNS filter restored to %s", pinned)
                    // The browser policy depends on a DNS filter being set, so refresh it too.
                    BrowserRestrictionsManager(ctx).applyRestrictions()
                } else {
                    Timber.w("DNS filter %s could not be restored (result %d)", pinned, result)
                }
            } catch (e: Exception) {
                Timber.w(e, "DNS guard failed")
            }
        }
    }

    /** Reacts the moment the setting changes, rather than at the next sync. Safe to call twice. */
    fun observe(context: Context) {
        if (observing) return
        val ctx = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enforce(ctx)
            }
        }
        try {
            for (key in listOf("private_dns_mode", "private_dns_specifier")) {
                ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }
            observing = true
        } catch (e: Exception) {
            // The sync-time checks still catch a change; this only makes it instant.
            Timber.w(e, "Could not observe the Private DNS setting")
        }
    }
}
