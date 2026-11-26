package io.github.warleysr.dechainer.vpn

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.data.DnsGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

data class BlockedDnsEntry(
    val domain: String,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class LocalDnsShieldStatus(
    val config: LocalDnsConfig = LocalDnsConfig(),
    val isRunning: Boolean = false,
    val blockedCount: Long = 0L,
    val recentBlocked: List<BlockedDnsEntry> = emptyList(),
    val upstreamServers: List<String> = emptyList(),
    val lastError: String? = null
)

object LocalDnsShieldManager {
    private const val PREFS = "local_dns_shield_prefs"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_BLOCKED_COUNT = "blocked_count"
    private const val MAX_RECENT = 25
    private const val COUNT_PERSIST_DEBOUNCE_MS = 30_000L

    private val lock = Any()

    @Volatile
    private var cachedConfig: LocalDnsConfig? = null

    @Volatile
    private var lastCountPersistElapsedMs: Long = 0L

    val configGeneration = AtomicLong(0L)

    private val _status = MutableStateFlow(LocalDnsShieldStatus())
    val status: StateFlow<LocalDnsShieldStatus> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfig(context: Context): LocalDnsConfig {
        cachedConfig?.let { return it }
        val raw = prefs(context).getString(KEY_CONFIG, null)
        val loaded = LocalDnsConfig.fromJsonString(raw) ?: LocalDnsConfig()
        cachedConfig = loaded
        return loaded
    }

    fun refreshState(context: Context) {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        val cfg = LocalDnsConfig.fromJsonString(raw) ?: LocalDnsConfig()
        cachedConfig = cfg
        val count = prefs(context).getLong(KEY_BLOCKED_COUNT, 0L).coerceAtLeast(_status.value.blockedCount)
        _status.value = _status.value.copy(config = cfg, blockedCount = count)
    }

    fun notifyConfigChanged() {
        configGeneration.incrementAndGet()
    }

    fun applyConfig(context: Context, config: LocalDnsConfig) {
        val ctx = context.applicationContext
        synchronized(lock) {
            cachedConfig = config
            prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, config.toJsonString()) }
            val count = prefs(ctx).getLong(KEY_BLOCKED_COUNT, 0L).coerceAtLeast(_status.value.blockedCount)
            _status.value = _status.value.copy(
                config = config,
                blockedCount = count,
                lastError = if (!config.enabled) null else _status.value.lastError
            )
            notifyConfigChanged()
        }
        enforce(ctx)
    }

    fun enforce(context: Context) {
        val ctx = context.applicationContext
        val config = getConfig(ctx)
        val count = prefs(ctx).getLong(KEY_BLOCKED_COUNT, 0L).coerceAtLeast(_status.value.blockedCount)
        _status.value = _status.value.copy(config = config, blockedCount = count)

        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val isOwner = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

        if (!config.enabled) {
            if (isOwner) {
                runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            }
            setError(null)
            runCatching {
                ctx.startService(Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_STOP))
            }
            return
        }

        if (isOwner) {
            // Private DNS over port 853 bypasses local VPN TUN if left active; clear pinned DoT.
            // Never silently enable user restrictions here (DISALLOW_CONFIG_* remain user-controlled).
            DnsGuard.forget(ctx)
            disablePrivateDnsForLocalVpn(ctx, dpm, admin)
            runCatching { dpm.setAlwaysOnVpnPackage(admin, ctx.packageName, false) }
                .onFailure {
                    Timber.w(it, "Could not set Always-On VPN package")
                    setError("Could not set Always-On VPN: ${it.message ?: "check permissions"}")
                }
        } else {
            setError("Device Owner privileges are required to run Local DNS Shield automatically")
        }

        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_START)
            )
        }.onFailure {
            Timber.w(it, "Could not start LocalDnsVpnService")
            setError("Could not start VPN service: ${it.message ?: "unknown error"}")
        }
    }

    fun releaseForDeviceOwnerRemoval(context: Context) {
        val ctx = context.applicationContext
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) {
            runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        }
        val off = LocalDnsConfig(enabled = false)
        synchronized(lock) {
            cachedConfig = off
            prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, off.toJsonString()) }
            notifyConfigChanged()
        }
        _status.value = _status.value.copy(config = off, isRunning = false, lastError = null)
        runCatching {
            ctx.startService(Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_STOP))
        }
    }

    internal fun setServiceRunning(running: Boolean) {
        _status.value = _status.value.copy(
            isRunning = running,
            lastError = if (running) null else _status.value.lastError
        )
    }

    internal fun setError(message: String?) {
        _status.value = _status.value.copy(lastError = message)
    }

    internal fun updateUpstreams(servers: List<String>) {
        _status.value = _status.value.copy(upstreamServers = servers)
    }

    internal fun recordBlocked(domain: String, reason: String) {
        val cur = _status.value
        val nextCount = cur.blockedCount + 1L
        val nextRecent = (listOf(BlockedDnsEntry(domain, reason)) + cur.recentBlocked).take(MAX_RECENT)
        _status.value = cur.copy(blockedCount = nextCount, recentBlocked = nextRecent)

        val now = SystemClock.elapsedRealtime()
        if (now - lastCountPersistElapsedMs >= COUNT_PERSIST_DEBOUNCE_MS) {
            lastCountPersistElapsedMs = now
            runCatching {
                io.github.warleysr.dechainer.DechainerApplication.getInstance()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit { putLong(KEY_BLOCKED_COUNT, nextCount) }
            }
        }
    }

    internal fun flushBlockedCount(context: Context) {
        val count = _status.value.blockedCount
        lastCountPersistElapsedMs = SystemClock.elapsedRealtime()
        runCatching {
            prefs(context).edit { putLong(KEY_BLOCKED_COUNT, count) }
        }
    }

    private fun disablePrivateDnsForLocalVpn(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ) {
        // Release Device Owner hostname pin FIRST so later writes don't get overwritten by opportunistic mode.
        runCatching {
            if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            }
        }
        if (AccessibilityWatchdog.hasPermission(ctx)) {
            runCatching {
                Settings.Global.putString(ctx.contentResolver, "private_dns_mode", "off")
                Settings.Global.putString(ctx.contentResolver, "private_dns_specifier", "")
            }
        }
    }
}
