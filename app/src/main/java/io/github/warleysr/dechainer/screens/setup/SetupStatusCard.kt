package io.github.warleysr.dechainer.screens.setup

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.setup.SetupCheck
import io.github.warleysr.dechainer.setup.SetupItem
import io.github.warleysr.dechainer.setup.SetupProbe
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.viewmodels.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Setup status (blueprint 11): one card in Settings that lists every setup check with a tick or a
 * Fix button. It reads the phone again every few seconds while it is on screen, so coming back from
 * an Android settings page shows the new state.
 */
@Composable
fun SetupStatusCard(onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var checks by remember { mutableStateOf<List<SetupCheck>>(emptyList()) }
    // Probing the PackageManager and DevicePolicyManager is not free: keep it off the main thread.
    RepeatWhileVisible(2_000) { checks = withContext(Dispatchers.IO) { SetupProbe.read(ctx) } }

    CalmCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setup_status_title), style = MaterialTheme.typography.titleMedium)
            checks.forEach { check ->
                SetupRow(check) {
                    val intent: Intent? = SetupProbe.fixIntent(ctx, check.item)
                    when {
                        intent != null -> try { ctx.startActivity(intent) } catch (_: Exception) { }
                        check.item == SetupItem.DEVICE_OWNER -> onNavigate(Route.SETUP_DEVICE_OWNER)
                        check.item == SetupItem.AI_KEY -> onNavigate(Route.URGE_SETTINGS)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(check: SetupCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(labelOf(check.item)), style = MaterialTheme.typography.bodyLarge)
            if (!check.done && check.item == SetupItem.AI_KEY) {
                Text(stringResource(R.string.setup_ai_optional), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (check.done) {
            Icon(Icons.Outlined.CheckCircle, stringResource(R.string.setup_done), tint = MaterialTheme.colorScheme.primary)
        } else if (check.item != SetupItem.RECOVERY_CODE && check.item != SetupItem.RULES) {
            OutlinedButton(onClick = onFix) { Text(stringResource(R.string.setup_fix)) }
        }
    }
}

private fun labelOf(item: SetupItem): Int = when (item) {
    SetupItem.DEVICE_OWNER -> R.string.setup_item_device_owner
    SetupItem.NOTIFICATIONS -> R.string.setup_item_notifications
    SetupItem.FULL_SCREEN -> R.string.setup_item_full_screen
    SetupItem.USAGE_ACCESS -> R.string.setup_item_usage
    SetupItem.BATTERY -> R.string.setup_item_battery
    SetupItem.RECOVERY_CODE -> R.string.setup_item_recovery
    SetupItem.AI_KEY -> R.string.setup_item_ai
    SetupItem.RULES -> R.string.setup_item_rules
}
