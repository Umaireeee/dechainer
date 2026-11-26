package io.github.warleysr.dechainer.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.ApkUpdateDialog
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme

/**
 * Handles direct "Open with Déchaîner" taps on downloaded `.apk`, `.apks`, `.xapk`, or `.apkm`
 * files so updating installed sideloaded apps takes one tap without hunting in the file picker.
 */
class ApkUpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent?.data
        if (uri == null || SecurityManager.getImpulseBlockRemainingTime(this) > 0L) {
            if (SecurityManager.getImpulseBlockRemainingTime(this) > 0L) {
                Toast.makeText(this, R.string.impulse_timer_active, Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            DechainerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ApkUpdateDialog(
                            uri = uri,
                            showToastOnQueue = true,
                            onFinish = { finish() }
                        )
                    }
                }
            }
        }
    }
}
