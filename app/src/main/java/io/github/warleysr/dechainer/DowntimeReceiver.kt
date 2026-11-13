package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.DowntimeManager

/** Runs a downtime sync when the next window starts or ends, and after boot, updates and clock changes. */
class DowntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DowntimeManager.ACTION_TICK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> DowntimeManager.sync(context.applicationContext)
        }
    }
}
