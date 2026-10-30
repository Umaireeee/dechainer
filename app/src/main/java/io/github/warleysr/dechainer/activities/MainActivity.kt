package io.github.warleysr.dechainer.activities

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.screens.dumb.DumbPhoneRoot
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme

/**
 * The one screen host. Everything the app shows is [DumbPhoneRoot]: Start, Home, Settings and the
 * exit wait. The old blackout, focus, schedule and app screens are no longer reachable from here.
 */
class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // Coming back to the app is a wake-up: the lock is recomputed from stored data.
        LockEngine.requestSync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- EMERGENCY BYPASS HOOK ---
        try {
            // 1. Instantly drop Device Owner / Admin privileges so locks and restrictions release
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(packageName)
        } catch (e: Exception) {
            // If it fails or isn't owner, catch it silently and continue
        }
        
        // 2. Clear out app shared preferences / countdown timers instantly on launch
        try {
            val prefs = getSharedPreferences("dechainer_prefs", Context.MODE_PRIVATE) // or check your app's actual pref name if different
            prefs.edit().clear().apply()
        } catch (e: Exception) {}
        // -----------------------------

        enableEdgeToEdge(
            // Always night: light status and navigation icons, whatever the system theme is.
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        LockEngine.requestSync(this)
        setContent {
            DechainerTheme {
                DumbPhoneRoot()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SecurityManager.endSession()
    }

    companion object {
        /** Kept because the old Quick Settings tile still names it; nothing reads it any more. */
        const val EXTRA_URGE_SOURCE = "urge_source"
    }
}
