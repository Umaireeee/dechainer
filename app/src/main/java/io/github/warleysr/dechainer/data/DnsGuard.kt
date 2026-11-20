package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Keeps the Private DNS filter chosen in Déchaîner switched on, and (when auto-failover is on)
 * automatically switches to a working clean DNS server whenever the active one becomes unreachable.
 *
 * Uses two zero-polling OS hooks:
 * 1. A [ContentObserver] on `Settings.Global` (`private_dns_mode` / `private_dns_specifier`) to
 *    restore the pinned provider instantly if modified from outside the app.
 * 2. A passive [ConnectivityManager.NetworkCallback] to check reachability and fail over to a
 *    working clean DNS server immediately when switching networks or when connectivity returns.
 */
object DnsGuard {
    private const val PREFS = "private_dns_guard"
    private const val KEY_DNS_WANTED = "dns_wanted"
    private const val KEY_AUTO_FAILOVER = "auto_failover"
    private const val DOT_PORT = 853
    private const val DOT_TIMEOUT_MS = 3_500

    data class DnsStatus(
        val activeHost: String? = null,
        val autoFailover: Boolean = false,
        val reachable: Map<String, Boolean?> = emptyMap(),
        val checking: Boolean = false
    )

    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "dns-guard").apply { isDaemon = true }
    }
    private val probePool = Executors.newFixedThreadPool(5) {
        Thread(it, "dns-probe").apply { isDaemon = true }
    }
    private val stateLock = Any()

    @Volatile
    private var observing = false

    @Volatile
    private var lastVerifiedNetworkId: Long? = null

    @Volatile
    private var lastVerifiedHost: String? = null

    @Volatile
    private var lastProbeElapsedMs: Long? = null

    private val _status = MutableStateFlow(DnsStatus())
    val status: StateFlow<DnsStatus> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pinnedHost(context: Context): String? = prefs(context).getString(KEY_DNS_WANTED, null)

    fun isAutoFailover(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_FAILOVER, false)

    fun remember(context: Context, host: String, autoFailover: Boolean = isAutoFailover(context)) {
        prefs(context).edit(commit = true) {
            putString(KEY_DNS_WANTED, host)
            putBoolean(KEY_AUTO_FAILOVER, autoFailover)
        }
        _status.value = _status.value.copy(
            activeHost = host,
            autoFailover = autoFailover,
            checking = false
        )
    }

    fun forget(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_DNS_WANTED)
            putBoolean(KEY_AUTO_FAILOVER, false)
        }
        lastVerifiedNetworkId = null
        lastVerifiedHost = null
        lastProbeElapsedMs = null
        _status.value = DnsStatus(activeHost = null, autoFailover = false, reachable = _status.value.reachable)
    }

    internal fun needsRestore(pinnedHost: String?, currentHost: String?): Boolean =
        pinnedHost != null && !pinnedHost.equals(currentHost, ignoreCase = true)

    /**
     * Whether [host] completes a Private DNS (DoT, port 853) TLS handshake.
     * Tries known bootstrap IPv4 addresses first (immune to a stuck broken Private DNS or dead
     * IPv6 route), then falls back to system DNS resolution for [host].
     */
    fun canReachDoT(host: String): Boolean {
        val addresses = LinkedHashSet<InetAddress>()
        DnsFailoverRule.bootstrapIpsFor(host).forEach { ip ->
            runCatching {
                val raw = InetAddress.getByName(ip).address
                // Bind the TLS peer hostname to the bootstrap IP bytes so HTTPS hostname
                // verification checks the provider's hostname, not the numeric IP string.
                addresses += InetAddress.getByAddress(host, raw)
            }
        }
        runCatching {
            InetAddress.getAllByName(host).forEach { resolved ->
                addresses += InetAddress.getByAddress(host, resolved.address)
            }
        }
        if (addresses.isEmpty()) return false

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        for (addr in addresses) {
            try {
                (factory.createSocket() as SSLSocket).use { socket ->
                    socket.soTimeout = DOT_TIMEOUT_MS
                    socket.sslParameters = socket.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    socket.connect(InetSocketAddress(addr, DOT_PORT), DOT_TIMEOUT_MS)
                    socket.startHandshake()
                }
                return true
            } catch (_: Exception) {
                // Try the next IP candidate for this provider.
            }
        }
        return false
    }

    /** Probes all clean DNS providers in parallel and returns reachability in priority order. */
    fun probeAllCleanServers(): Map<String, Boolean> {
        val tasks = DnsFailoverRule.CLEAN_HOSTS.map { host ->
            host to probePool.submit(Callable { canReachDoT(host) })
        }
        return tasks.associate { (host, future) ->
            val ok = try {
                future.get()
            } catch (_: Exception) {
                false
            }
            host to ok
        }
    }

    /**
     * Probes all clean DNS servers in parallel and immediately connects Device Owner Private DNS to
     * the first working server, enabling [autoFailover]. Must be called off the main thread.
     */
    fun connectBestCleanDns(context: Context): Pair<String, Int> {
        val ctx = context.applicationContext
        synchronized(stateLock) {
            _status.value = _status.value.copy(checking = true)
            val reachability = probeAllCleanServers()
            val preferred = DnsFailoverRule.chooseServer(
                pinnedHost = pinnedHost(ctx),
                autoFailover = true,
                reachability = reachability
            ) ?: DnsFailoverRule.CLEAN_HOSTS.first()

            val orderedCandidates = buildList {
                add(preferred)
                DnsFailoverRule.CLEAN_HOSTS.forEach { host ->
                    if (host != preferred && reachability[host] == true) add(host)
                }
                DnsFailoverRule.CLEAN_HOSTS.forEach { host ->
                    if (host !in this) add(host)
                }
            }

            var appliedHost = preferred
            var result = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING
            for (candidate in orderedCandidates) {
                result = DeviceOwnerRepository.applyPrivateDnsHostOnly(candidate)
                if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    appliedHost = candidate
                    break
                }
            }

            if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                remember(ctx, appliedHost, autoFailover = true)
                lastVerifiedNetworkId = activeValidatedNetworkHandle(ctx)
                lastVerifiedHost = appliedHost
                lastProbeElapsedMs = SystemClock.elapsedRealtime()
            }
            _status.value = DnsStatus(
                activeHost = if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) appliedHost else DeviceOwnerRepository.getPrivateDNS(),
                autoFailover = isAutoFailover(ctx),
                reachable = reachability,
                checking = false
            )
            return appliedHost to result
        }
    }

    /** Probes all clean servers off-thread for UI display, and switches if auto-failover is active. */
    fun refreshReachabilityAsync(context: Context) {
        val ctx = context.applicationContext
        worker.execute {
            synchronized(stateLock) {
                val pinned = pinnedHost(ctx)
                val auto = isAutoFailover(ctx)
                val live = DeviceOwnerRepository.getPrivateDNS()
                _status.value = _status.value.copy(
                    activeHost = live ?: pinned,
                    autoFailover = auto,
                    checking = true
                )
                val results = probeAllCleanServers()
                var active = live ?: pinned
                if (auto && DeviceOwnerRepository.isDeviceOwner()) {
                    val chosen = DnsFailoverRule.chooseServer(pinned, true, results)
                    if (chosen != null && (chosen != pinned || needsRestore(chosen, DeviceOwnerRepository.getPrivateDNS()))) {
                        if (DeviceOwnerRepository.applyPrivateDnsHostOnly(chosen) == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                            remember(ctx, chosen, autoFailover = true)
                            active = chosen
                            lastVerifiedNetworkId = activeValidatedNetworkHandle(ctx)
                            lastVerifiedHost = chosen
                            lastProbeElapsedMs = SystemClock.elapsedRealtime()
                        }
                    } else if (chosen != null && results[chosen] == true) {
                        lastVerifiedNetworkId = activeValidatedNetworkHandle(ctx)
                        lastVerifiedHost = chosen
                        lastProbeElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
                _status.value = DnsStatus(
                    activeHost = active,
                    autoFailover = isAutoFailover(ctx),
                    reachable = results,
                    checking = false
                )
            }
        }
    }

    fun enforce(context: Context) {
        val ctx = context.applicationContext
        worker.execute {
            synchronized(stateLock) {
                try {
                    enforceLocked(ctx)
                } catch (e: Exception) {
                    Timber.w(e, "DNS guard failed")
                }
            }
        }
    }

    private fun enforceLocked(ctx: Context) {
        if (!DeviceOwnerRepository.isDeviceOwner()) return
        val pinned = pinnedHost(ctx)
        val auto = isAutoFailover(ctx)
        if (pinned == null && !auto) {
            _status.value = _status.value.copy(activeHost = DeviceOwnerRepository.getPrivateDNS(), autoFailover = false, checking = false)
            return
        }

        val live = DeviceOwnerRepository.getPrivateDNS()
        if (pinned != null && needsRestore(pinned, live)) {
            val result = DeviceOwnerRepository.setPrivateDNS(pinned, auto)
            if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                Timber.i("DNS filter restored to %s", pinned)
            } else {
                Timber.w("DNS filter %s could not be restored (result %d)", pinned, result)
            }
        }

        val netHandle = activeValidatedNetworkHandle(ctx)
        val now = SystemClock.elapsedRealtime()
        if (!DnsFailoverRule.shouldProbeNow(
                autoFailover = auto,
                hasValidatedInternet = netHandle != null,
                currentNetworkId = netHandle,
                lastVerifiedNetworkId = lastVerifiedNetworkId,
                lastVerifiedHost = lastVerifiedHost,
                pinnedHost = pinned,
                lastProbeElapsedMs = lastProbeElapsedMs,
                nowElapsedMs = now
            )
        ) {
            _status.value = _status.value.copy(
                activeHost = DeviceOwnerRepository.getPrivateDNS() ?: pinned,
                autoFailover = auto,
                checking = false
            )
            return
        }

        lastProbeElapsedMs = now
        if (pinned != null && canReachDoT(pinned)) {
            lastVerifiedNetworkId = netHandle
            lastVerifiedHost = pinned
            _status.value = DnsStatus(
                activeHost = pinned,
                autoFailover = auto,
                reachable = _status.value.reachable + (pinned to true),
                checking = false
            )
            return
        }

        _status.value = _status.value.copy(checking = true)
        val reachability = probeAllCleanServers()
        val chosen = DnsFailoverRule.chooseServer(pinned, autoFailover = true, reachability = reachability)
        var active = DeviceOwnerRepository.getPrivateDNS() ?: pinned
        if (chosen != null && (chosen != pinned || needsRestore(chosen, active))) {
            val result = DeviceOwnerRepository.applyPrivateDnsHostOnly(chosen)
            if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                Timber.i("DNS guard auto-switched to working clean server %s", chosen)
                remember(ctx, chosen, autoFailover = true)
                active = chosen
                lastVerifiedNetworkId = netHandle
                lastVerifiedHost = chosen
            } else {
                Timber.w("DNS guard could not switch to %s (result %d)", chosen, result)
            }
        }
        _status.value = DnsStatus(
            activeHost = active,
            autoFailover = auto,
            reachable = reachability,
            checking = false
        )
    }

    private fun activeValidatedNetworkHandle(ctx: Context): Long? = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        if (net != null && caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) net.networkHandle else null
    } catch (_: Exception) {
        null
    }

    /**
     * Registers passive, zero-polling observers for both `Settings.Global` Private DNS edits and
     * default network changes. Safe to call multiple times.
     */
    @Synchronized
    fun observe(context: Context) {
        if (observing) return
        val ctx = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enforce(ctx)
            }
        }
        try {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("private_dns_specifier"), false, observer
            )
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("private_dns_mode"), false, observer
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not observe Private DNS settings")
        }

        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        (isAutoFailover(ctx) || pinnedHost(ctx) != null)
                    ) {
                        enforce(ctx)
                    }
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Could not observe default network for DNS failover")
        }
        observing = true
    }

    @VisibleForTesting
    internal fun resetForTests() {
        lastVerifiedNetworkId = null
        lastVerifiedHost = null
        lastProbeElapsedMs = null
        _status.value = DnsStatus()
    }
}
