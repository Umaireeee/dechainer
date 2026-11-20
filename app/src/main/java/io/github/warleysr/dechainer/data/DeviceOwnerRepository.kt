package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.security.SecurityManager

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.util.Log
import androidx.core.net.toUri
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.DechainerAccessibilityService
import kotlinx.coroutines.launch
import io.github.warleysr.dechainer.utils.ShizukuRunner
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import timber.log.Timber

/** Device-owner setup/state: Shizuku shell commands plus the [DevicePolicyManager] calls they unlock. */
object DeviceOwnerRepository {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val context = DechainerApplication.getInstance()
    private val dpm get() = DeviceAdmin.policyManager
    private val adminName get() = DeviceAdmin.component
    private val packageName = context.packageName
    private val serviceComponent = "$packageName/.DechainerAccessibilityService"

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun startViewIntent(uri: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri.toUri()).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun installShizuku() {
        if (!startViewIntent("market://details?id=$SHIZUKU_PACKAGE")) {
            startViewIntent("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
        }
    }

    fun openShizukuSetupGuide() {
        startViewIntent("https://shizuku.rikka.app/guide/setup/")
    }

    fun checkShizukuPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun getUserId(): Int = Process.myUid() / 100000

    @SuppressLint("PrivateApi")
    private val shizukuDpmLock = Any()

    fun <T> withShizukuDevicePolicyManager(action: (DevicePolicyManager) -> T): T = synchronized(shizukuDpmLock) {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val field = manager.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        val originalService = field.get(manager) as IInterface
        val wrappedBinder = ShizukuBinderWrapper(originalService.asBinder())
        val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val proxiedService = asInterface.invoke(null, wrappedBinder)

        field.set(manager, proxiedService)
        try {
            action(manager)
        } finally {
            try {
                field.set(manager, originalService)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun DevicePolicyManager.forceUpdateUserSetupComplete(userId: Int) {
        val clazz = this::class.java
        runCatching {
            clazz.getDeclaredMethod("forceUpdateUserSetupComplete", Int::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }.invoke(this, userId)
        }.onFailure {
            runCatching {
                clazz.getDeclaredMethod("forceUpdateUserSetupComplete")
                    .also { it.isAccessible = true }.invoke(this)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun DevicePolicyManager.setActiveAdminCompat(receiver: ComponentName, refreshing: Boolean, userId: Int) {
        val clazz = this::class.java
        val method3 = clazz.methods.firstOrNull {
            it.name == "setActiveAdmin" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == java.lang.Integer.TYPE
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "setActiveAdmin" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == java.lang.Integer.TYPE
        }

        if (method3 != null) {
            method3.isAccessible = true
            method3.invoke(this, receiver, refreshing, userId)
        } else {
            val method2 = clazz.methods.firstOrNull {
                it.name == "setActiveAdmin" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == ComponentName::class.java &&
                        it.parameterTypes[1] == java.lang.Boolean.TYPE
            } ?: clazz.declaredMethods.firstOrNull {
                it.name == "setActiveAdmin" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == ComponentName::class.java &&
                        it.parameterTypes[1] == java.lang.Boolean.TYPE
            }
            method2?.isAccessible = true
            method2?.invoke(this, receiver, refreshing)
        }
    }

    @SuppressLint("PrivateApi")
    private fun DevicePolicyManager.setDeviceOwnerCompat(who: ComponentName, ownerName: String?, userId: Int): Boolean {
        val clazz = this::class.java

        val method2 = clazz.methods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE
        }
        if (method2 != null) {
            method2.isAccessible = true
            val res = method2.invoke(this, who, userId)
            return (res as? Boolean) ?: false
        }

        val method3 = clazz.methods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == java.lang.Integer.TYPE
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == java.lang.Integer.TYPE
        }
        if (method3 != null) {
            method3.isAccessible = true
            val res = method3.invoke(this, who, ownerName, userId)
            return (res as? Boolean) ?: false
        }

        val methodLegacy = clazz.methods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "setDeviceOwner" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
        }
        if (methodLegacy != null) {
            methodLegacy.isAccessible = true
            val res = methodLegacy.invoke(this, who.packageName, ownerName)
            return (res as? Boolean) ?: false
        }

        return false
    }

    fun bypassUserSetupComplete(userId: Int = getUserId()): Boolean {
        val current = ShizukuRunner.exec("settings", "--user", "$userId", "get", "secure", "user_setup_complete")
        if (current?.output?.trim() != "1") return false
        val put = ShizukuRunner.exec("settings", "--user", "$userId", "put", "secure", "user_setup_complete", "0")
        if (put == null || put.exitCode != 0) return false
        refreshUserSetupComplete(userId)
        return true
    }

    fun restoreUserSetupComplete(userId: Int = getUserId()) {
        ShizukuRunner.exec("settings", "--user", "$userId", "put", "secure", "user_setup_complete", "1")
        refreshUserSetupComplete(userId)
    }

    fun refreshUserSetupComplete(userId: Int = getUserId()) {
        runCatching {
            withShizukuDevicePolicyManager { manager ->
                manager.forceUpdateUserSetupComplete(userId)
            }
        }.onFailure {
            Timber.w(it, "forceUpdateUserSetupComplete failed (may not be supported on this Android version)")
        }
    }

    fun restoreStuckUserSetupComplete() {
        if (!Shizuku.pingBinder() || !checkShizukuPermission()) return
        val userId = getUserId()
        val setup = ShizukuRunner.exec("settings", "--user", "$userId", "get", "secure", "user_setup_complete")?.output?.trim()
        if (setup != "0") return
        val provisioned = ShizukuRunner.exec("settings", "--user", "$userId", "get", "global", "device_provisioned")?.output?.trim()
        if (provisioned != "1") return
        restoreUserSetupComplete(userId)
    }


    /** Gives the phone back before device owner is removed: unhides Settings/hidden apps and unsuspends everything Dechainer holds. */
    private fun releaseHeldApps() {
        try {
            SettingsLock.setLocked(false)
            val ctx = DechainerApplication.getInstance()
            val hiddenPrefs = ctx.getSharedPreferences("hidden_apps_prefs", Context.MODE_PRIVATE)
            hiddenPrefs.getStringSet("hidden_packages", emptySet()).orEmpty().forEach { pkg ->
                runCatching { dpm.setApplicationHidden(adminName, pkg, false) }
            }
            hiddenPrefs.edit().clear().apply()
            val manual = ctx.getSharedPreferences("manual_suspensions", Context.MODE_PRIVATE).all.keys
            val held = BlockSuspensionManager.heldPackages(ctx) + DowntimeRepository.getHeld(ctx) +
                SecurityManager.getActiveImpulseSuspension(ctx) + manual
            if (held.isNotEmpty()) dpm.setPackagesSuspended(adminName, held.toTypedArray(), false)
            DnsGuard.forget(ctx)
            AccessibilityWatchdog.setWanted(ctx, false)
            NotificationGuard.setListenerWanted(ctx, false)
            SecurityManager.cancelForcedRemoval(ctx)
            AppRepository.invalidateCache()
        } catch (e: Exception) {
            Timber.e(e, "Could not release held apps")
        }
    }

    /** Removes device-owner status, or requests it via Shizuku with setup bypass — returns the command output. */
    fun processDeviceOwnerPrivileges(remove: Boolean = false): String {
        if (remove) {
            if (isDeviceOwner()) {
                releaseHeldApps()
                dpm.clearDeviceOwnerApp(packageName)
            }
            return ""
        }

        val userId = getUserId()
        val bypassed = bypassUserSetupComplete(userId)
        var commandOutput = ""

        try {
            var success = false
            runCatching {
                withShizukuDevicePolicyManager { wrappedDpm ->
                    wrappedDpm.setActiveAdminCompat(adminName, true, userId)
                    success = wrappedDpm.setDeviceOwnerCompat(adminName, null, userId)
                }
            }.onFailure {
                Timber.w(it, "Direct binder setDeviceOwner failed, trying shell command")
            }

            if (!success && !dpm.isDeviceOwnerApp(packageName)) {
                val result = ShizukuRunner.exec(
                    "dpm", "set-device-owner", "--user", "$userId",
                    "$packageName/.DechainerDeviceAdminReceiver"
                )
                commandOutput = listOfNotNull(result?.output, result?.error)
                    .filter { it.isNotBlank() }.joinToString("\n")
            }
        } finally {
            if (bypassed) {
                restoreUserSetupComplete(userId)
            }
        }

        return commandOutput
    }

    /** Filtering DNS providers offered in the DNS settings, in the order they are tried on fallback. */
    val CLEAN_DNS_PRESETS: List<Pair<String, String>> = listOf(
        "Cloudflare Family" to DnsFailoverRule.CLEAN_HOSTS[0],
        "AdGuard Family" to DnsFailoverRule.CLEAN_HOSTS[1],
        "CleanBrowsing Adult" to DnsFailoverRule.CLEAN_HOSTS[2],
        "CleanBrowsing Family" to DnsFailoverRule.CLEAN_HOSTS[3]
    )

    internal fun applyPrivateDnsHostOnly(host: String): Int {
        if (!isDeviceOwner()) return DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        return runCatching { dpm.setGlobalPrivateDnsModeSpecifiedHost(adminName, host) }
            .getOrDefault(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)
    }

    /**
     * Sets the private DNS host. If the host is not answering and [autoFailover] is on (or if Android
     * rejects it with `HOST_NOT_SERVING`), the next clean preset is tried until one is accepted.
     */
    fun setPrivateDNS(host: String, autoFailover: Boolean = DnsGuard.isAutoFailover(context)): Int {
        var result = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        if (!isDeviceOwner()) return result
        val candidates = listOf(host) + CLEAN_DNS_PRESETS.map { it.second }.filter { it != host }
        for (candidate in candidates) {
            result = applyPrivateDnsHostOnly(candidate)
            if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                DnsGuard.remember(context, candidate, autoFailover)
                break
            }
            if (result != DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) break
            Timber.w("Private DNS host $candidate is not serving, trying the next one")
        }
        return result
    }

    fun setAutoPrivateDNS(): Int = DnsGuard.connectBestCleanDns(context).second

    fun startPrivateDnsGuard() {
        DnsGuard.observe(context)
    }

    fun getPrivateDNS(): String? = runCatching {
        if (!isDeviceOwner()) return@runCatching null
        if (dpm.getGlobalPrivateDnsMode(adminName) != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)
            return@runCatching null
        dpm.getGlobalPrivateDnsHost(adminName)
    }.getOrNull()

    fun getAllAccountsViaShizuku(): List<Pair<String, String>> {
        val output = ShizukuRunner.exec("dumpsys", "account")?.output ?: return emptyList()
        val regex = " {4}Account \\{name=(.*?), type=(.*?)\\}".toRegex()
        return output.lines().mapNotNull { line ->
            regex.find(line)?.let { Pair(it.groupValues[2], it.groupValues[1]) }
        }
    }

    fun getAppNameFromAccountType(context: Context, accountType: String): String {
        val am = AccountManager.get(context)
        val packManager = context.packageManager

        val auth = am.authenticatorTypes.find { it.type == accountType }

        return if (auth != null) {
            try {
                val appInfo = packManager.getApplicationInfo(auth.packageName, 0)
                packManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                accountType
            }
        } else {
            accountType
        }
    }

    private val ownerRegex = Regex("""admin=(?:ComponentInfo\{)?([\w.]+)/([\w.$]+)""")

    /** Parses `dpm list-owners` (all Android versions) into (package, receiver class), or null if none. */
    internal fun parseDeviceOwner(output: String): Pair<String, String>? {
        val m = ownerRegex.find(output) ?: return null
        val pkg = m.groupValues[1]
        val cls = m.groupValues[2].let { if (it.startsWith(".")) "$pkg$it" else it }
        return pkg to cls
    }

    fun getCurrentDeviceOwner(): Pair<String, String>? =
        ShizukuRunner.exec("dpm", "list-owners")?.output?.let { parseDeviceOwner(it) }

    fun getExtraUsersInfo(): List<String> {
        val output = ShizukuRunner.exec("pm", "list", "users")?.output ?: return emptyList()
        val regex = Regex("""UserInfo\{(\d+):([^:]+):""")
        return regex.findAll(output)
            .filter { it.groupValues[1].toInt() != 0 }
            .map { it.groupValues[2] }
            .toList()
    }

    fun getAccessibilityServices(): String =
        ShizukuRunner.exec("settings", "get", "secure", "enabled_accessibility_services")
            ?.output.orEmpty().replace("\n", "")

    fun isAccessibilityGranted(): Boolean = AccessibilityWatchdog.ServiceList.isListed(
        getAccessibilityServices(), packageName, DechainerAccessibilityService::class.java.name
    )

    fun changeAccessibilityPermission(grant: Boolean) {
        // Remember what the user chose: only a deliberate "off" stops the watchdog from bringing it back.
        AccessibilityWatchdog.setWanted(context, grant)
        val serviceClass = DechainerAccessibilityService::class.java.name
        val current = getAccessibilityServices()
        if (grant) AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(context)

        val updated = if (grant) {
            AccessibilityWatchdog.ServiceList.with(current, packageName, serviceClass)
        } else {
            AccessibilityWatchdog.ServiceList.without(current, packageName, serviceClass)
        }
        // Only Dechainer's own entry changes: other accessibility services (e.g. TalkBack) are kept.
        ShizukuRunner.exec(
            "settings", "put", "secure", "enabled_accessibility_services", updated.ifEmpty { "null" }
        )
        if (grant) ShizukuRunner.exec("settings", "put", "secure", "accessibility_enabled", "1")
    }
}
