package io.github.warleysr.dechainer

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.lock.LockEngine

class DechainerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device Owner was just granted: apply the always-on policies (date and time lock, Force
        // stop and Clear data off) now, instead of at the next alarm.
        val pending = goAsync()
        val ctx = context.applicationContext
        Thread {
            try {
                LockEngine.sync(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

}
