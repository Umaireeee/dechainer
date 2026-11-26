package io.github.warleysr.dechainer.vpn

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
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
    val upstreamServers: List<String> = emptyList()
)

object LocalDnsShieldManager {
    private const val PREFS = "local_dns_shield_prefs"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_BLOCKED_COUNT = "blocked_count"
    private const val MAX_RECENT = 25

    private val lock = Any()
    private val _status = MutableStateFlow(LocalDnsShieldStatus())
    val status: StateFlow<LocalDnsShieldStatus> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfig(context: Context): LocalDnsConfig {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        return LocalDnsConfig.fromJsonString(raw) ?: LocalDnsConfig()
    }

    fun refreshState(context: Context) {
        val cfg = getConfig(context)
        val count = prefs(context).getLong(KEY_BLOCKED_COUNT, 0L)
        _status.value = _status.value.copy(config = cfg, blockedCount = count)
    }

    fun applyConfig(context: Context, config: LocalDnsConfig) {
        val ctx = context.applicationContext
        synchronized(lock) {
            prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, config.toJsonString()) }
            _status.value = _status.value.copy(
                config = config,
                blockedCount = prefs(ctx).getLong(KEY_BLOCKED_COUNT, 0L)
            )
        }
        enforce(ctx)
    }

    fun enforce(context: Context) {
        val ctx = context.applicationContext
        val config = getConfig(ctx)
        _status.value = _status.value.copy(
            config = config,
            blockedCount = prefs(ctx).getLong(KEY_BLOCKED_COUNT, 0L)
        )

        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val isOwner = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

        if (!config.enabled) {
            if (isOwner) {
                runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            }
            runCatching {
                ctx.startService(Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_STOP))
            }
            return
        }

        if (isOwner) {
            // Private DNS over port 853 would bypass the local VPN tunnel if left active.
            DnsGuard.forget(ctx)
            disablePrivateDnsForLocalVpn(ctx, dpm, admin)
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) }
            runCatching { dpm.setAlwaysOnVpnPackage(admin, ctx.packageName, false) }
                .onFailure { Timber.w(it, "Could not set Always-On VPN package") }
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
        }

        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_START)
            )
        }.onFailure { Timber.w(it, "Could not start LocalDnsVpnService") }
    }

    fun releaseForDeviceOwnerRemoval(context: Context) {
        val ctx = context.applicationContext
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)) {
            runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        }
        prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, LocalDnsConfig(enabled = false).toJsonString()) }
        _status.value = _status.value.copy(config = LocalDnsConfig(enabled = false), isRunning = false)
        runCatching {
            ctx.startService(Intent(ctx, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_STOP))
        }
    }

    internal fun setServiceRunning(running: Boolean) {
        _status.value = _status.value.copy(isRunning = running)
    }

    internal fun updateUpstreams(servers: List<String>) {
        _status.value = _status.value.copy(upstreamServers = servers)
    }

    internal fun recordBlocked(domain: String, reason: String) {
        val cur = _status.value
        val nextCount = cur.blockedCount + 1L
        val nextRecent = (listOf(BlockedDnsEntry(domain, reason)) + cur.recentBlocked).take(MAX_RECENT)
        _status.value = cur.copy(blockedCount = nextCount, recentBlocked = nextRecent)
        runCatching {
            io.github.warleysr.dechainer.DechainerApplication.getInstance()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putLong(KEY_BLOCKED_COUNT, nextCount) }
        }
    }

    private fun disablePrivateDnsForLocalVpn(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName
    ) {
        if (AccessibilityWatchdog.hasPermission(ctx)) {
            runCatching {
                Settings.Global.putString(ctx.contentResolver, "private_dns_mode", "off")
                Settings.Global.putString(ctx.contentResolver, "private_dns_specifier", "")
            }
        }
        runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
    }
}
