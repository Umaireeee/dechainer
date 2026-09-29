package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.UnlockDelay

@Composable
fun unlockDelayLabel(minutes: Int): String =
    if (minutes <= 0) stringResource(R.string.unlock_delay_off)
    else pluralStringResource(R.plurals.unlock_delay_minutes, minutes, minutes)

/** Pick the unlock delay: a preset, or any number of minutes up to a day. */
@Composable
fun UnlockDelayDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_delay)) },
        text = {
            Column {
                Text(stringResource(R.string.unlock_delay_desc), style = MaterialTheme.typography.bodySmall)
                UnlockDelay.PRESETS.forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(m) }
                    ) {
                        RadioButton(selected = m == current, onClick = { onPick(m) })
                        Text(unlockDelayLabel(m))
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.unlock_delay_custom)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(enabled = custom.isNotEmpty(), onClick = { onPick(custom.toInt()) }) {
                Text(stringResource(R.string.unlock_delay_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.unlock_wait_close)) }
        }
    )
}
