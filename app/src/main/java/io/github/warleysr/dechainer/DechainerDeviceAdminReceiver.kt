package io.github.warleysr.dechainer

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.NotificationGuard

class DechainerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        NotificationGuard.enforceAsync(context.applicationContext)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

}