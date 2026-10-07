package io.github.warleysr.dechainer.screens.urge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.urge.UrgeSettings

/**
 * Urge settings (blueprint D5 and 6.2): the personal reason shown while breathing, and how long the
 * urge lock lasts. There is no AI now.
 */
@Composable
fun UrgeSettingsScreen() {
    val context = LocalContext.current
    val urge = remember { UrgeSettings(context) }

    var message by remember { mutableStateOf<Int?>(null) }

    var reason by remember { mutableStateOf(urge.reason) }
    var minutes by remember { mutableFloatStateOf(urge.durationMinutes.toFloat()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

        // ---- the reason ----
        Header(R.string.urge_reason_section)
        Hint(R.string.urge_reason_hint)
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text(stringResource(R.string.urge_reason_field)) },
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            urge.setReason(reason)
            message = R.string.urge_saved
            reason = urge.reason
        }

        // ---- how long the lock lasts ----
        Spacer(Modifier.height(16.dp))
        Header(R.string.urge_length_section)
        Hint(R.string.urge_length_hint)
        Text(
            stringResource(R.string.urge_length_value, minutes.toInt()),
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = minutes,
            onValueChange = { minutes = it },
            valueRange = Rules.URGE_LOCK_MIN_MINUTES.toFloat()..Rules.URGE_LOCK_MAX_MINUTES.toFloat(),
            steps = Rules.URGE_LOCK_MAX_MINUTES - Rules.URGE_LOCK_MIN_MINUTES - 1,
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            urge.setDurationMinutes(minutes.toInt())
            minutes = urge.durationMinutes.toFloat()
            message = R.string.urge_saved
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(res: Int) {
    Text(
        stringResource(res).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun Hint(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.urge_save)) }
}
