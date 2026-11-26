package io.github.warleysr.dechainer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.CleanResetMigration
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.NotificationGuard
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.vpn.LocalDnsShieldManager

/** Handles system boot, update, notification block, and clock-change broadcasts. */
class DowntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                CleanResetMigration.runIfNeeded(app)
                TimelockManager.syncAll(app)
                DowntimeManager.sync(app)
                DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
                AccessibilityWatchdog.onSystemEvent(app)
                NotificationGuard.enforceAll(app)
                DnsGuard.enforce(app)
                LocalDnsShieldManager.enforce(app)
                BrowserHardeningManager.reapplyIfEnabled(app)
                PlayStoreUpdateManager.sync(app)
                ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(app, abandonStale = true)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                TimelockManager.syncAll(app)
                DowntimeManager.sync(app)
                DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
                PlayStoreUpdateManager.sync(app)
            }

            NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED -> {
                if (NotificationGuard.shouldHandleBlockBroadcast(intent)) {
                    NotificationGuard.enforceAsync(app)
                }
            }
        }
    }
}
