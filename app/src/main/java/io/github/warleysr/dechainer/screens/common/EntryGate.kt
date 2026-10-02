package io.github.warleysr.dechainer.screens.common

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import io.github.warleysr.dechainer.R

/** Whether the phone has a PIN, pattern, password or biometric to ask for. */
fun deviceHasScreenLock(context: android.content.Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/**
 * The screen shown before anything else when Déchaîner is opened. It asks for the phone's own
 * screen lock through Android's confirm-credential screen (no extra permission, no extra
 * dependency) and calls [onUnlocked] on success. Cancelling leaves this screen up with a button to
 * try again; nothing behind it is drawn.
 */
@Composable
fun EntryGate(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    // Asked once per showing; a rotation does not ask again, and "Unlock" asks on demand.
    var asked by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.entry_gate_prompt_title)
    val subtitle = stringResource(R.string.entry_gate_prompt_text)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onUnlocked()
    }
    fun ask() {
        val km = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) {
            // Nothing to ask for any more (the screen lock was removed): do not trap the owner.
            onUnlocked()
        } else {
            launcher.launch(intent)
        }
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            ask()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                stringResource(R.string.entry_gate_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.entry_gate_text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = { ask() }) { Text(stringResource(R.string.entry_gate_unlock)) }
        }
    }
}
