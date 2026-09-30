package io.github.warleysr.dechainer.screens.common

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.FullScreenAlerts
import io.github.warleysr.dechainer.ui.theme.CalmCard

/**
 * A one-time note in Settings: on Android 14+ the full-screen "Did you do the work?" alert needs a
 * permission. Without it the question still appears on the notification, with its buttons.
 */
@Composable
fun FullScreenHint() {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(FullScreenAlerts.isHintDismissed(context)) }
    if (!FullScreenAlerts.shouldHint(Build.VERSION.SDK_INT, FullScreenAlerts.isAllowed(context), dismissed)) return
    CalmCard(modifier = Modifier.fillMaxWidth().padding(16.dp), highlighted = true) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.full_screen_hint_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.full_screen_hint_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row {
                TextButton(onClick = {
                    try { context.startActivity(FullScreenAlerts.settingsIntent(context)) } catch (_: Exception) { }
                }) { Text(stringResource(R.string.full_screen_hint_open)) }
                TextButton(onClick = {
                    FullScreenAlerts.dismissHint(context)
                    dismissed = true
                }) { Text(stringResource(R.string.full_screen_hint_dismiss)) }
            }
        }
    }
}
