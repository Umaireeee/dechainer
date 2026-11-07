package io.github.warleysr.dechainer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.data.RegionRuleRepository

/**
 * Debug builds only, used by the emulator tests.
 *  - `DEBUG_CAPTURE --el delay 8000`: start a screen capture like the Capture button does.
 *  - `DEBUG_SET_RULES --es b64 <base64 JSON>`: replace the saved region rules inside the running
 *    app, so the service picks them up through its preference listener as it would in real use.
 */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "io.github.warleysr.dechainer.DEBUG_CAPTURE" ->
                DechainerAccessibilityService.scheduleCapture(intent.getLongExtra("delay", 8000L))
            "io.github.warleysr.dechainer.DEBUG_SET_RULES" -> {
                val json = String(Base64.decode(intent.getStringExtra("b64").orEmpty(), Base64.DEFAULT))
                RegionRuleRepository.prefs(context).edit().putString(RegionRuleRepository.KEY_RULES, json).commit()
            }
        }
    }
}
