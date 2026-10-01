package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.lock.SettingsFreeze
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.security.SecurityManager

/**
 * The challenge in front of Déchaîner itself (the old "impulse lock" setting without its panic
 * button, which is now the urge lock). Changing it takes the recovery code, and it is frozen on a
 * punishment day: the screen reads the stored value back after each tap, so a refused write shows as
 * what is really in force.
 */
@Composable
fun EntryChallengeScreen() {
    val context = LocalContext.current
    val recoveryGate = rememberRecoveryGate()
    var mode by remember { mutableStateOf(SecurityManager.getEntryChallenge(context)) }
    val sessionActive = recoveryGate.isSessionActive
    val frozen = remember { SettingsFreeze.isFrozen(context) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.entry_challenge_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
            if (frozen) {
                Text(
                    stringResource(R.string.entry_challenge_frozen),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            SecurityManager.EntryChallenge.entries.forEach { option ->
                OptionRow(
                    selected = mode == option,
                    enabled = sessionActive && !frozen,
                    title = when (option) {
                        SecurityManager.EntryChallenge.OFF -> stringResource(R.string.entry_challenge_off)
                        SecurityManager.EntryChallenge.NORMAL -> stringResource(R.string.entry_challenge_normal)
                        SecurityManager.EntryChallenge.HARD -> stringResource(R.string.entry_challenge_hard)
                    },
                    supporting = when (option) {
                        SecurityManager.EntryChallenge.OFF -> stringResource(R.string.entry_challenge_off_desc)
                        SecurityManager.EntryChallenge.NORMAL -> stringResource(R.string.entry_challenge_normal_desc)
                        SecurityManager.EntryChallenge.HARD -> stringResource(R.string.entry_challenge_hard_desc)
                    },
                    onClick = {
                        SecurityManager.setEntryChallenge(context, option)
                        mode = SecurityManager.getEntryChallenge(context)
                    }
                )
            }
        }
        if (!sessionActive && !frozen) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { recoveryGate.run {} }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Start
                ) { Text(stringResource(R.string.settings_locked)) }
            }
        }
    }

    RecoveryGateDialog(recoveryGate)
}

@Composable
private fun OptionRow(selected: Boolean, enabled: Boolean, title: String, supporting: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }
}
