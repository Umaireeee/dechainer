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
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DNS-only local VpnService. Only packets addressed to our virtual DNS servers (10.111.222.1 /
 * fd00:deca::1) enter the TUN interface, so all normal internet traffic goes directly over the
 * device's network at full speed with near-zero battery usage.
 */
class LocalDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.warleysr.dechainer.vpn.START"
        const val ACTION_STOP = "io.github.warleysr.dechainer.vpn.STOP"
        private const val CHANNEL_ID = "local_dns_shield"
        private const val NOTIFICATION_ID = 7310
        private const val TUN_IPV4_CLIENT = "10.111.222.2"
        private const val TUN_IPV4_DNS = "10.111.222.1"
        private const val TUN_IPV6_CLIENT = "fd00:deca::2"
        private const val TUN_IPV6_DNS = "fd00:deca::1"
        private const val UPSTREAM_TIMEOUT_MS = 2_500
        private val FALLBACK_UPSTREAMS = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var workers: ExecutorService? = null
    private val writeLock = Any()
    private var outputStream: FileOutputStream? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var running = false

    @Volatile
    private var upstreamServers: List<InetAddress> = emptyList()

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

    @Synchronized
    private fun startVpnIfNeeded() {
        if (running && tunInterface != null) return

        registerNetworkObserver()
        refreshUpstreams()

        val builder = Builder()
            .setSession(getString(R.string.dns_shield_title))
            .setMtu(1500)
            .addAddress(TUN_IPV4_CLIENT, 32)
            .addRoute(TUN_IPV4_DNS, 32)
            .addDnsServer(TUN_IPV4_DNS)
            .addAddress(TUN_IPV6_CLIENT, 128)
            .addRoute(TUN_IPV6_DNS, 128)
            .addDnsServer(TUN_IPV6_DNS)
            .setMetered(false)

        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = runCatching { builder.establish() }
            .onFailure { Timber.e(it, "Could not establish LocalDnsVpnService TUN") }
            .getOrNull() ?: return

        tunInterface = pfd
        outputStream = FileOutputStream(pfd.fileDescriptor)
        workers = Executors.newFixedThreadPool(8) { Thread(it, "local-dns-worker").apply { isDaemon = true } }
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
        while (running) {
            val len = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (len <= 0) {
                if (len < 0) break
                continue
            }
            val packetCopy = buf.copyOfRange(0, len)
            workers?.execute { handlePacket(packetCopy, len) }
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int) {
        val parsed = DnsPacketCodec.parseIpUdpDns(packet, len) ?: return
        val config = LocalDnsShieldManager.getConfig(applicationContext)
        val decision = LocalDnsBlocklist.evaluate(parsed.qName, config)

        val responsePayload: ByteArray = when (decision) {
            is DnsDecision.Block -> {
                LocalDnsShieldManager.recordBlocked(parsed.qName, decision.reason)
                DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
            }
            is DnsDecision.Rewrite -> {
                val rewrittenQuery = DnsPacketCodec.rewriteDnsQueryName(
                    parsed.dnsPayload,
                    decision.targetCname,
                    parsed.qType,
                    parsed.qClass
                )
                val upstreamResp = queryUpstream(rewrittenQuery)
                if (upstreamResp != null) {
                    DnsPacketCodec.buildRewrittenAnswerPayload(
                        parsed.dnsPayload,
                        parsed.questionEndOffset,
                        parsed.qType,
                        upstreamResp
                    )
                } else {
                    DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
                }
            }
            DnsDecision.Allow -> {
                queryUpstream(parsed.dnsPayload) ?: return
            }
        }

        val ipResponse = DnsPacketCodec.wrapUdpInIpPacket(parsed, responsePayload)
        synchronized(writeLock) {
            runCatching { outputStream?.write(ipResponse) }
        }
    }

    private fun queryUpstream(dnsPayload: ByteArray): ByteArray? {
        val targets = upstreamServers.ifEmpty {
            FALLBACK_UPSTREAMS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }
        for (server in targets) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) {
                        Timber.w("VpnService.protect failed for DNS socket")
                    }
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
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateFromLinkProperties(linkProperties)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refreshUpstreams()
            }

            override fun onAvailable(network: Network) {
                refreshUpstreams()
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun refreshUpstreams() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val active = cm.activeNetwork
        val lp = active?.let { cm.getLinkProperties(it) }
        if (lp != null) updateFromLinkProperties(lp)
    }

    private fun updateFromLinkProperties(lp: LinkProperties) {
        val filtered = lp.dnsServers.filter { addr ->
            val host = addr.hostAddress.orEmpty()
            !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                host != TUN_IPV4_DNS &&
                host != TUN_IPV6_DNS
        }
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
        networkCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
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
