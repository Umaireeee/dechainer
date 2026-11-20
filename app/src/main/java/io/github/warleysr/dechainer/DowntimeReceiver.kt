package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.DowntimeManager

/** Handles system boot, update, and clock-change broadcasts for downtime, blocks, and watchdog recovery. */
class DowntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DowntimeManager.sync(app)
                DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
                AccessibilityWatchdog.onSystemEvent(app)
                ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(app, abandonStale = true)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                DowntimeManager.sync(app)
                DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
            }
        }
    }
}
