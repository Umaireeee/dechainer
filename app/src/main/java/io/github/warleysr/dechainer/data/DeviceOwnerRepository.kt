package io.github.warleysr.dechainer.data

import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.utils.ShizukuRunner
import rikka.shizuku.Shizuku

/** Device-owner setup/state: Shizuku shell commands plus the [DevicePolicyManager] calls they unlock. */
object DeviceOwnerRepository {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val context = DechainerApplication.getInstance()
    private val dpm get() = DeviceAdmin.policyManager
    private val adminName get() = DeviceAdmin.component
    private val packageName = context.packageName

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun installShizuku() {
        val intent = try {
            Intent(Intent.ACTION_VIEW, "market://details?id=$SHIZUKU_PACKAGE".toUri())
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        } catch (e: ActivityNotFoundException) {
            Intent(
                Intent.ACTION_VIEW,
                "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE".toUri(),
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        }
        context.startActivity(intent)
    }

    fun openShizukuSetupGuide() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "https://shizuku.rikka.app/guide/setup/".toUri(),
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
    }

    fun checkShizukuPermission(): Boolean {
        if (Shizuku.isPreV11()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    /** Removes device-owner status, or requests it via Shizuku's `dpm set-device-owner` — returns the resulting state. */
    fun processDeviceOwnerPrivileges(remove: Boolean = false): Boolean {
        if (remove && dpm.isAdminActive(adminName)) {
            // Android does not un-hide apps when a Device Owner goes away, and nothing could
            // afterwards. Bring back everything Déchaîner hid first.
            Blocker.releaseAllHidden(context, dpm, adminName)
            // Same for the brick's home-screen takeover: undo it while it still can be undone,
            // so your own launcher is the home screen afterwards, not the timer.
            try {
                dpm.clearPackagePersistentPreferredActivities(adminName, packageName)
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(context, "io.github.warleysr.dechainer.activities.BrickHome"),
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) { }
            dpm.clearDeviceOwnerApp(packageName)
            return false
        }

        ShizukuRunner.command(
            command = "dpm set-device-owner $packageName/.DechainerDeviceAdminReceiver",
            listener = object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    println("Output: $output Done: $done")
                }
                override fun onCommandError(error: String) {
                    Log.e("Shizuku", error)
                }
            })
        return dpm.isDeviceOwnerApp(packageName)
    }

    fun setPrivateDNS(host: String): Int {
        return dpm.setGlobalPrivateDnsModeSpecifiedHost(adminName, host)
    }

    /**
     * "Automatic" Private DNS: encrypted where the network allows, plain otherwise, and no
     * filtering. The closest thing to off a device owner can set.
     */
    /**
     * Prepares pinning for the brick: Déchaîner and the dialers may run pinned (so incoming calls
     * still show), and the pinned phone keeps the status bar, the notification shade with Quick
     * Settings (airplane mode, mobile data, hotspot) and the power menu. Home, recents and every
     * other app are out of reach.
     */
    fun prepareBrick(context: android.content.Context, allowed: Set<String> = emptySet()) {
        // Déchaîner, the dialers, the apps you allowed and the urge journal (opened from its Quick
        // Settings tile when an urge hits mid-block): a pinned phone opens only these.
        val pkgs = mutableSetOf(context.packageName, RideLock.JOURNAL_PACKAGE)
        pkgs += allowed
        try {
            val telecom = context.getSystemService(android.content.Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            telecom.defaultDialerPackage?.let { pkgs += it }
            telecom.systemDialerPackage?.let { pkgs += it }
        } catch (_: Exception) { }
        dpm.setLockTaskPackages(adminName, pkgs.toTypedArray())
        // Android only allows the notification shade (Quick Settings) together with the Home
        // button feature: asked for alone, it's rejected with an error, and the pin never started.
        // The Home button shows, but can't open the launcher: it isn't on the list above.
        try {
            dpm.setLockTaskFeatures(
                adminName,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
        } catch (e: Exception) {
            // Stricter over nothing: no shade, but the phone still pins.
            timber.log.Timber.w(e, "Brick features refused; pinning without the shade")
            try {
                dpm.setLockTaskFeatures(
                    adminName,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            } catch (_: Exception) { }
        }
    }

    fun setPrivateDnsAutomatic(): Int = dpm.setGlobalPrivateDnsModeOpportunistic(adminName)

    fun getPrivateDNS(): String? {
        if (dpm.getGlobalPrivateDnsMode(adminName) != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)
            return null
        return dpm.getGlobalPrivateDnsHost(adminName)
    }

    fun getAllAccountsViaShizuku(): List<Pair<String, String>> {
        val accounts = mutableListOf<Pair<String, String>>()
        try {
            ShizukuRunner.command(
                command = "dumpsys account",
                listener = object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        val regex = " {4}Account \\{name=(.*?), type=(.*?)\\}".toRegex()

                        output.lines().forEach { line ->
                            val match = regex.find(line)
                            if (match != null) {
                                val accountName = match.groupValues[1]
                                val accountType = match.groupValues[2]
                                accounts.add(Pair(accountType, accountName))
                            }
                        }

                        println("Output: \n$output")
                    }
                    override fun onCommandError(error: String) {
                        Log.e("Shizuku", error)
                    }
                })

        } catch (e: Exception) {
            Log.e("ShizukuError", "Erro ao buscar contas", e)
        }
        return accounts
    }

    fun getAppNameFromAccountType(context: Context, accountType: String): String {
        val am = AccountManager.get(context)
        val packManager = context.packageManager

        val authenticators = am.authenticatorTypes

        val auth = authenticators.find { it.type == accountType }

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

    fun getCurrentDeviceOwner(): Pair<String, String>? {
        return try {
            var dpmOutput = ""
            ShizukuRunner.command(
                command = "dpm list-owners",
                listener = object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        println("Output: $output Done: $done")
                        dpmOutput = output
                    }

                    override fun onCommandError(error: String) {
                        Log.e("Shizuku", error)
                    }
                })
            val componentPath = dpmOutput
                .substringAfter("admin=", "")
                .substringBefore(",", "")

            if (componentPath.isEmpty() || !componentPath.contains("/")) return null

            val parts = componentPath.split("/")
            val packageName = parts[0].trim()
            var receiverName = parts[1].trim()

            if (receiverName.startsWith(".")) {
                receiverName = "$packageName/$receiverName"
            }

            Pair(packageName, receiverName)
        } catch (e: Exception) {
            null
        }
    }

    fun getExtraUsersInfo(): List<String> {
        val users = mutableListOf<String>()

        ShizukuRunner.command(
            command = "pm list users",
            listener = object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    println("Output: $output Done: $done")

                    val regex = Regex("""UserInfo\{(\d+):([^:]+):""")

                    val matches = regex.findAll(output)
                    for (match in matches) {
                        val id = match.groupValues[1].toInt()
                        val name = match.groupValues[2]

                        if (id != 0)
                            users.add(name)
                    }
                }

                override fun onCommandError(error: String) {
                    Log.e("Shizuku", error)
                }
            })

        return users
    }
}
