package io.github.warleysr.dechainer.setup

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.warleysr.dechainer.data.FullScreenAlerts
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.security.SecurityManager

/** Reads the phone for the setup checks and says where each Fix goes. */
object SetupProbe {
    fun read(ctx: Context): List<SetupCheck> {
        val xiaomi = SetupStatus.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)
        return SetupItem.entries
            .filter { SetupStatus.applies(it, Build.VERSION.SDK_INT, xiaomi) }
            .map { SetupCheck(it, done(ctx, it)) }
    }

    private fun done(ctx: Context, item: SetupItem): Boolean = try {
        when (item) {
            SetupItem.DEVICE_OWNER ->
                ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName)
            SetupItem.NOTIFICATIONS -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            SetupItem.FULL_SCREEN -> FullScreenAlerts.isAllowed(ctx)
            SetupItem.USAGE_ACCESS -> TimeLimits.hasUsageAccess(ctx)
            SetupItem.BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            SetupItem.RECOVERY_CODE -> SecurityManager.hasRecoveryCode(ctx)
        }
    } catch (_: Exception) {
        false
    }

    /**
     * The Android settings page that fixes [item], or null where the fix is inside this app (the Device
     * Owner steps) and the screen navigates there itself.
     */
    fun fixIntent(ctx: Context, item: SetupItem): Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (item) {
            SetupItem.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            SetupItem.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            SetupItem.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            // The battery and Autostart switches live under the app's own page on these phones.
            SetupItem.BATTERY -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
