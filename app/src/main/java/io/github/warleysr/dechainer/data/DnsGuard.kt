package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        prefs(context).edit(commit = true) { putString(KEY_PINNED_HOST, host) }
    }

    /** Drops the pinned provider, so the guard stops restoring it. Used when filtering is turned off. */
    fun forget(context: Context) {
        prefs(context).edit(commit = true) { remove(KEY_PINNED_HOST) }
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
                // Enforcement, not a change of setting: the pinned provider goes back at once.
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

    // ---- A filter that follows the network ----
    // The dumb-phone lock pins the first provider of [DnsProviders.ORDER] that Android accepts. If the
    // network cannot validate it, the next is tried; if none works, the phone goes back to automatic
    // DNS on that network so the internet keeps working, and the filter is tried again as soon as the
    // network changes.

    private const val KEY_FALLBACK = "fallback"
    private const val ROTATE_DELAY_MS = 12_000L

    /** Providers that did not work on the current network. Touched only under its own lock. */
    private val tried = mutableSetOf<String>()

    @Volatile
    private var lastNetwork: Network? = null

    @Volatile
    private var watching = false

    // Lazy: the pure unit tests load this object on a plain JVM, where Looper does not exist.
    private val networkHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun isFallback(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FALLBACK, false)

    private fun setFallback(ctx: Context, on: Boolean) {
        prefs(ctx).edit(commit = true) { putBoolean(KEY_FALLBACK, on) }
    }

    /** Drops the pin and every per-network memory: the filter is switched off for good (the lock was undone or ended). */
    fun clearAll(ctx: Context) {
        forget(ctx)
        setFallback(ctx, false)
        synchronized(tried) { tried.clear() }
    }

    /** Pins a working filter if none is pinned and the phone is not in its per-network fallback. Returns at once. */
    fun ensureFiltered(context: Context) {
        val ctx = context.applicationContext
        worker.execute {
            try {
                if (pinnedHost(ctx) != null || isFallback(ctx)) return@execute
                pinFirstWorking(ctx)
            } catch (e: Exception) {
                Timber.w(e, "DNS filter not set")
            }
        }
    }

    /** Tries the providers not yet tried on this network, in order. Runs on the worker thread only. */
    private fun pinFirstWorking(ctx: Context) {
        val candidates = synchronized(tried) { DnsProviders.ORDER.filter { it !in tried } }
        for (host in candidates) {
            val result = DeviceOwnerRepository.setPrivateDNS(host)
            if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                remember(ctx, host)
                BrowserRestrictionsManager(ctx).applyRestrictions()
                Timber.i("DNS filter pinned: %s", host)
                return
            }
            synchronized(tried) { tried += host }
        }
        // Nothing works on this network: automatic DNS, so the internet works. Tried afresh on the next network.
        forget(ctx)
        DeviceOwnerRepository.setPrivateDnsAutomatic()
        setFallback(ctx, true)
        BrowserRestrictionsManager(ctx).applyRestrictions()
        Timber.w("No DNS filter works on this network; automatic DNS until it changes")
    }

    /** Called a while after a network stopped validating: moves to the next provider, or to automatic DNS. */
    private fun rotateIfBroken(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        val broken = DnsProviders.looksBroken(
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
        if (!broken) return
        worker.execute {
            try {
                val pinned = pinnedHost(ctx) ?: return@execute
                synchronized(tried) { tried += pinned }
                forget(ctx)
                pinFirstWorking(ctx)
            } catch (e: Exception) {
                Timber.w(e, "DNS filter not rotated")
            }
        }
    }

    /** A different network than before: forget what failed on the last one and give the filter another go. */
    private fun networkSeen(ctx: Context, network: Network) {
        if (network == lastNetwork) return
        lastNetwork = network
        synchronized(tried) { tried.clear() }
        if (isFallback(ctx)) {
            setFallback(ctx, false)
            ensureFiltered(ctx)
        }
    }

    /** Watches the default network. Safe to call twice; call once at startup. */
    fun watchNetwork(context: Context) {
        if (watching) return
        val ctx = context.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkSeen(ctx, network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networkSeen(ctx, network)
                networkHandler.removeCallbacksAndMessages(null)
                if (DnsProviders.looksBroken(
                        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    )
                ) {
                    // Validation takes a few seconds after connecting: look again before blaming the DNS.
                    networkHandler.postDelayed({ rotateIfBroken(ctx) }, ROTATE_DELAY_MS)
                }
            }

            override fun onLost(network: Network) {
                networkHandler.removeCallbacksAndMessages(null)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            watching = true
        } catch (e: Exception) {
            Timber.w(e, "Could not watch the network")
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
