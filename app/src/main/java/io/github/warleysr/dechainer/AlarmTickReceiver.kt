package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.BlockSuspensionManager
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.MusicLimitTracker

/** Internal non-exported receiver for exact enforcement alarms. */
class AlarmTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            DowntimeManager.ACTION_TICK -> DowntimeManager.sync(app)
            BlockSuspensionManager.ACTION_TICK -> BlockSuspensionManager.sync(app)
            MusicLimitTracker.ACTION_TICK -> MusicLimitTracker.onAlarmTick(app)
            DechainerAccessibilityService.ACTION_IMPULSE_RELEASE ->
                DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
        }
    }
}
