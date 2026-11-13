package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.DowntimeManager

/** Runs a downtime sync when the next window starts or ends, and after boot, updates and clock changes. */
class DowntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // After a reboot or an update the phone may leave advanced blocking switched off.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AccessibilityWatchdog.onSystemEvent(context.applicationContext)
        }
        when (intent.action) {
            DowntimeManager.ACTION_TICK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> DowntimeManager.sync(context.applicationContext)
        }
    }
}
