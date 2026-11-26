package io.github.warleysr.dechainer.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * DNS-only local VpnService. Uses a blocking TUN descriptor (`.setBlocking(true)`) so idle battery
 * drain is near zero, routes only virtual DNS servers (`10.111.222.1/32` and optional IPv6
 * `fd00:deca::1/128`) into TUN, and binds upstream UDP/53 sockets directly onto the active
 * physical network (`Wi-Fi` / `Cellular`) using real ISP DNS servers.
 */
class LocalDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.warleysr.dechainer.vpn.START"
        const val ACTION_STOP = "io.github.warleysr.dechainer.vpn.STOP"
        private const val CHANNEL_ID = "local_dns_shield"
        private const val NOTIFICATION_ID = 7310
        private const val TUN_IPV4_CLIENT = "10.111.222.2"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_IPV4_DNS = "10.111.222.1"
        private const val TUN_IPV6_CLIENT = "fd00:deca::2"
        private const val TUN_IPV6_DNS = "fd00:deca::1"
        private const val UPSTREAM_TIMEOUT_MS = 1_800
        private const val DNS_CACHE_MAX_ENTRIES = 256
        private const val DNS_CACHE_TTL_MS = 60_000L
        private val FALLBACK_UPSTREAMS = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
    }

    private data class CachedDnsEntry(
        val dnsResponsePayload: ByteArray,
        val expiresAtElapsedMs: Long
    )

    private var tunInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var workers: ExecutorService? = null
    private val writeLock = Any()
    private var outputStream: FileOutputStream? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var running = false

    @Volatile
    private var underlyingNetwork: Network? = null

    @Volatile
    private var upstreamServers: List<InetAddress> = emptyList()

    private val dnsCache = object : LinkedHashMap<String, CachedDnsEntry>(DNS_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDnsEntry>?): Boolean =
            size > DNS_CACHE_MAX_ENTRIES
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !LocalDnsShieldManager.getConfig(this).enabled) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        LocalDnsBlocklist.ensureLoaded(applicationContext)
        startVpnIfNeeded()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dns_shield_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.dns_shield_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.dns_shield_notif_title))
            .setContentText(getString(R.string.dns_shield_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun findUnderlyingPhysicalNetwork(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.filter { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } ?: candidates.firstOrNull()
    }

    private fun hasPhysicalIpv6(cm: ConnectivityManager, network: Network?): Boolean {
        val lp = network?.let { cm.getLinkProperties(it) } ?: return false
        val hasGlobalAddr = lp.linkAddresses.any { linkAddr ->
            val addr = linkAddr.address
            addr is Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress
        }
        val hasIpv6Dns = lp.dnsServers.any { addr ->
            val host = addr.hostAddress.orEmpty()
            addr is Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress && host != TUN_IPV6_DNS
        }
        return hasGlobalAddr || hasIpv6Dns
    }

    private fun establishTun(includeIpv6: Boolean): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.dns_shield_title))
            .setMtu(1500)
            .setBlocking(true)
            .addAddress(TUN_IPV4_CLIENT, TUN_IPV4_PREFIX)
            .addRoute(TUN_IPV4_DNS, 32)
            .addDnsServer(TUN_IPV4_DNS)
            .setMetered(false)

        if (includeIpv6) {
            builder
                .addAddress(TUN_IPV6_CLIENT, 128)
                .addRoute(TUN_IPV6_DNS, 128)
                .addDnsServer(TUN_IPV6_DNS)
        }

        runCatching { builder.addDisallowedApplication(packageName) }
        return runCatching { builder.establish() }
            .onFailure { Timber.e(it, "Could not establish LocalDnsVpnService TUN (ipv6=$includeIpv6)") }
            .getOrNull()
    }

    @Synchronized
    private fun startVpnIfNeeded() {
        if (running && tunInterface != null) return

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetworkObserver()
        val physical = cm?.let { findUnderlyingPhysicalNetwork(it) }
        underlyingNetwork = physical
        refreshUpstreams(physical)

        val includeIpv6 = cm != null && hasPhysicalIpv6(cm, physical)
        val pfd = establishTun(includeIpv6) ?: if (includeIpv6) establishTun(false) else null
        if (pfd == null) {
            LocalDnsShieldManager.setServiceRunning(false)
            LocalDnsShieldManager.setError("Could not establish local VPN interface (check no conflicting VPN is active)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (physical != null) {
            runCatching { setUnderlyingNetworks(arrayOf(physical)) }
        }

        tunInterface = pfd
        outputStream = FileOutputStream(pfd.fileDescriptor)
        workers = ThreadPoolExecutor(
            0, 4,
            10L, TimeUnit.SECONDS,
            LinkedBlockingQueue(64),
            { r -> Thread(r, "local-dns-worker").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
        running = true
        LocalDnsShieldManager.setServiceRunning(true)

        readerThread = Thread({
            readLoop(pfd)
        }, "local-dns-tun-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(4096)
        try {
            while (running) {
                val len = try {
                    input.read(buf)
                } catch (e: Exception) {
                    val cause = e.cause
                    if (cause is ErrnoException &&
                        (cause.errno == OsConstants.EAGAIN || cause.errno == OsConstants.EINTR)
                    ) {
                        continue
                    }
                    break
                }
                if (len <= 0) {
                    if (len < 0) break
                    continue
                }
                val packetCopy = buf.copyOfRange(0, len)
                workers?.execute { handlePacket(packetCopy, len) }
            }
        } finally {
            if (running) {
                running = false
                LocalDnsShieldManager.setServiceRunning(false)
            }
            LocalDnsShieldManager.flushBlockedCount(applicationContext)
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int) {
        val parsed = DnsPacketCodec.parseIpUdpDns(packet, len) ?: return
        val config = LocalDnsShieldManager.getConfig(applicationContext)
        val decision = LocalDnsBlocklist.evaluate(parsed.qName, config)
        val gen = LocalDnsShieldManager.configGeneration.get()
        val cacheKey = "${parsed.qName}|${parsed.qType}|$gen"

        val responsePayload: ByteArray = when (decision) {
            is DnsDecision.Block -> {
                LocalDnsShieldManager.recordBlocked(parsed.qName, decision.reason)
                DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
            }
            is DnsDecision.Rewrite -> {
                getCachedDns(cacheKey, parsed.dnsPayload) ?: run {
                    val rewrittenQuery = DnsPacketCodec.rewriteDnsQueryName(
                        parsed.dnsPayload,
                        decision.targetCname,
                        parsed.qType,
                        parsed.qClass
                    )
                    val upstreamResp = queryUpstream(rewrittenQuery)
                    if (upstreamResp != null) {
                        val finalResp = DnsPacketCodec.buildRewrittenAnswerPayload(
                            parsed.dnsPayload,
                            parsed.questionEndOffset,
                            parsed.qType,
                            upstreamResp
                        )
                        putCachedDns(cacheKey, finalResp)
                        finalResp
                    } else {
                        DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
                    }
                }
            }
            DnsDecision.Allow -> {
                getCachedDns(cacheKey, parsed.dnsPayload) ?: run {
                    val resp = queryUpstream(parsed.dnsPayload) ?: return
                    putCachedDns(cacheKey, resp)
                    resp
                }
            }
        }

        val ipResponse = DnsPacketCodec.wrapUdpInIpPacket(parsed, responsePayload)
        synchronized(writeLock) {
            runCatching { outputStream?.write(ipResponse) }
        }
    }

    private fun getCachedDns(cacheKey: String, requestDnsPayload: ByteArray): ByteArray? {
        if (requestDnsPayload.size < 2) return null
        val now = SystemClock.elapsedRealtime()
        val cachedBytes = synchronized(dnsCache) {
            val entry = dnsCache[cacheKey] ?: return null
            if (entry.expiresAtElapsedMs <= now) {
                dnsCache.remove(cacheKey)
                return null
            }
            entry.dnsResponsePayload.copyOf()
        }
        if (cachedBytes.size >= 2) {
            cachedBytes[0] = requestDnsPayload[0]
            cachedBytes[1] = requestDnsPayload[1]
        }
        return cachedBytes
    }

    private fun putCachedDns(cacheKey: String, dnsResponse: ByteArray) {
        if (dnsResponse.size < 12) return
        val rcode = dnsResponse[3].toInt() and 0x0F
        if (rcode != 0) return
        val now = SystemClock.elapsedRealtime()
        synchronized(dnsCache) {
            dnsCache[cacheKey] = CachedDnsEntry(dnsResponse.copyOf(), now + DNS_CACHE_TTL_MS)
        }
    }

    private fun clearDnsCache() {
        synchronized(dnsCache) {
            dnsCache.clear()
        }
    }

    private fun queryUpstream(dnsPayload: ByteArray): ByteArray? {
        val targets = upstreamServers.ifEmpty {
            FALLBACK_UPSTREAMS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }
        val net = underlyingNetwork
        for (server in targets) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) {
                        Timber.w("VpnService.protect failed for DNS socket")
                    }
                    net?.let { runCatching { it.bindSocket(socket) } }
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    val outPkt = DatagramPacket(dnsPayload, dnsPayload.size, server, 53)
                    socket.send(outPkt)
                    val respBuf = ByteArray(4096)
                    val inPkt = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(inPkt)
                    return respBuf.copyOfRange(0, inPkt.length)
                }
            } catch (_: Exception) {
                // Try the next upstream server
            }
        }
        return null
    }

    private fun registerNetworkObserver() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == underlyingNetwork || underlyingNetwork == null) {
                    updateFromLinkProperties(linkProperties)
                }
            }

            override fun onAvailable(network: Network) {
                val activePhysical = findUnderlyingPhysicalNetwork(cm) ?: network
                underlyingNetwork = activePhysical
                runCatching { setUnderlyingNetworks(arrayOf(activePhysical)) }
                clearDnsCache()
                refreshUpstreams(activePhysical)
            }

            override fun onLost(network: Network) {
                if (network == underlyingNetwork) {
                    val fallback = findUnderlyingPhysicalNetwork(cm)
                    underlyingNetwork = fallback
                    runCatching { setUnderlyingNetworks(fallback?.let { arrayOf(it) }) }
                    clearDnsCache()
                    refreshUpstreams(fallback)
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun refreshUpstreams(network: Network? = underlyingNetwork) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val target = network ?: findUnderlyingPhysicalNetwork(cm)
        val lp = target?.let { cm.getLinkProperties(it) }
        if (lp != null) {
            updateFromLinkProperties(lp)
        } else {
            val fallback = FALLBACK_UPSTREAMS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            upstreamServers = fallback
            LocalDnsShieldManager.updateUpstreams(fallback.mapNotNull { it.hostAddress })
        }
    }

    private fun updateFromLinkProperties(lp: LinkProperties) {
        val filtered = lp.dnsServers.filter { addr ->
            val host = addr.hostAddress.orEmpty()
            !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                host != TUN_IPV4_DNS &&
                host != TUN_IPV6_DNS
        }.sortedBy { if (it is Inet4Address) 0 else 1 }

        val finalList = if (filtered.isNotEmpty()) {
            filtered
        } else {
            FALLBACK_UPSTREAMS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }
        upstreamServers = finalList
        LocalDnsShieldManager.updateUpstreams(finalList.mapNotNull { it.hostAddress })
    }

    @Synchronized
    private fun stopVpn() {
        running = false
        LocalDnsShieldManager.setServiceRunning(false)
        LocalDnsShieldManager.flushBlockedCount(applicationContext)
        clearDnsCache()
        networkCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        underlyingNetwork = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        outputStream = null
        readerThread?.interrupt()
        readerThread = null
        workers?.shutdownNow()
        workers = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopVpn()
        if (LocalDnsShieldManager.getConfig(this).enabled) {
            LocalDnsShieldManager.enforce(this)
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
