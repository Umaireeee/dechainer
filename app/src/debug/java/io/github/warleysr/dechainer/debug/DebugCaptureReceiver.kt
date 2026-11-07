package io.github.warleysr.dechainer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.DechainerAccessibilityService

/** `adb shell am broadcast -a io.github.warleysr.dechainer.DEBUG_CAPTURE --el delay 8000 -p <pkg>` */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DechainerAccessibilityService.scheduleCapture(intent.getLongExtra("delay", 8000L))
    }
}
