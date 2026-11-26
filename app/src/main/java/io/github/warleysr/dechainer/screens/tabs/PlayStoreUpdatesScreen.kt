package io.github.warleysr.dechainer.screens.tabs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.PlayStoreUpdateConfig
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction

private val WINDOW_PRESETS = listOf(
    1 * 60 to 4 * 60,
    2 * 60 to 5 * 60,
    3 * 60 to 6 * 60,
    2 * 60 to 4 * 60,
    23 * 60 to 2 * 60
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayStoreUpdatesScreen() {
    val context = LocalContext.current
    val gate = rememberTimelockGate()
    val status by PlayStoreUpdateManager.status.collectAsState()
    val cfg = status.config
    val accessibilityRunning = DechainerAccessibilityService.isRunning

    LaunchedEffect(Unit) {
        PlayStoreUpdateManager.refreshStatus(context)
    }

    fun updateConfig(next: PlayStoreUpdateConfig, title: String, description: String) {
        val loosening = next.isLooserThan(cfg)
        gate.change(
            loosening = loosening,
            title = title,
            description = description,
            dedupeKey = "play_store_update_policy",
            pendingAction = PendingAction.UpdatePlayStorePolicy(next.toJsonString()),
            onTighten = { PlayStoreUpdateManager.applyConfig(context, next) }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (cfg.blockPlayStoreUi || cfg.silentAutoUpdateEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.play_updates_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.play_updates_card_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val stateLine = when {
                        status.windowCurrentlyOpen ->
                            stringResource(R.string.play_updates_status_window_open, cfg.formatWindow())
                        cfg.blockPlayStoreUi && cfg.silentAutoUpdateEnabled ->
                            stringResource(R.string.play_updates_status_armed, cfg.formatWindow())
                        cfg.blockPlayStoreUi ->
                            stringResource(R.string.play_updates_status_ui_blocked_only)
                        cfg.silentAutoUpdateEnabled ->
                            stringResource(R.string.play_updates_status_window_only, cfg.formatWindow())
                        else ->
                            stringResource(R.string.play_updates_status_off)
                    }
                    Text(stateLine, style = MaterialTheme.typography.labelLarge)
                    if (status.rolledBackCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.play_updates_rolled_back_count, status.rolledBackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (cfg.blockPlayStoreUi && !accessibilityRunning) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.play_updates_accessibility_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (status.playStoreSuspended && cfg.silentAutoUpdateEnabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.play_updates_suspended_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (cfg.blockPlayStoreUi && accessibilityRunning) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                PlayStoreUpdateManager.unsuspendPlayStoreForBackgroundSync(context)
                            }) {
                                Text(stringResource(R.string.play_updates_unsuspend_btn))
                            }
                        }
                    }
                }
            }
        }

        if (!cfg.blockPlayStoreUi) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.play_updates_setup_tip_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.play_updates_setup_tip_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            if (!PlayStoreUpdateManager.openPlayStoreSettings(context)) {
                                Toast.makeText(
                                    context,
                                    R.string.play_updates_open_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) {
                            Text(stringResource(R.string.play_updates_open_once_btn))
                        }
                    }
                }
            }
        }

        item {
            ShieldToggleRow(
                title = stringResource(R.string.play_updates_block_ui_title),
                subtitle = stringResource(R.string.play_updates_block_ui_desc),
                checked = cfg.blockPlayStoreUi,
                onCheckedChange = { enable ->
                    updateConfig(
                        cfg.copy(blockPlayStoreUi = enable),
                        context.getString(R.string.timelock_title_disable_play_ui_block),
                        context.getString(R.string.play_updates_block_ui_title)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.play_updates_silent_window_title),
                subtitle = stringResource(R.string.play_updates_silent_window_desc),
                checked = cfg.silentAutoUpdateEnabled,
                onCheckedChange = { enable ->
                    updateConfig(
                        cfg.copy(silentAutoUpdateEnabled = enable),
                        context.getString(R.string.timelock_title_loosen_play_updates),
                        context.getString(R.string.play_updates_silent_window_title)
                    )
                }
            )
        }

        if (cfg.silentAutoUpdateEnabled) {
            item {
                ShieldToggleRow(
                    title = stringResource(R.string.play_updates_screen_off_title),
                    subtitle = stringResource(R.string.play_updates_screen_off_desc),
                    checked = cfg.screenOffOnly,
                    onCheckedChange = { requireScreenOff ->
                        updateConfig(
                            cfg.copy(screenOffOnly = requireScreenOff),
                            context.getString(R.string.timelock_title_loosen_play_updates),
                            context.getString(R.string.play_updates_screen_off_title)
                        )
                    }
                )
            }

            item {
                Text(
                    stringResource(R.string.play_updates_window_schedule_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WINDOW_PRESETS.forEach { (startMin, endMin) ->
                        val label = "%02d:%02d–%02d:%02d".format(
                            startMin / 60, startMin % 60,
                            endMin / 60, endMin % 60
                        )
                        val selected = cfg.windowStartMinute == startMin && cfg.windowEndMinute == endMin
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    updateConfig(
                                        cfg.copy(windowStartMinute = startMin, windowEndMinute = endMin),
                                        context.getString(R.string.timelock_title_loosen_play_updates),
                                        label
                                    )
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.play_updates_sideload_note),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    TimelockGateDialog(gate)
}
