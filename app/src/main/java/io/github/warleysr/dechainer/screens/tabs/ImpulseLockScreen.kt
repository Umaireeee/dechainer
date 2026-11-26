package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.AppPickerDialog
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.viewmodels.ImpulseLockViewModel
import kotlin.math.roundToInt

@Composable
fun ImpulseLockScreen(viewModel: ImpulseLockViewModel = viewModel()) {
    val context = LocalContext.current
    var showAppSelectionDialog by remember { mutableStateOf(false) }
    val gate = rememberTimelockGate()
    val accessibilityActive = DechainerAccessibilityService.isRunning

    LaunchedEffect(Unit) {
        viewModel.refreshFromPrefs()
    }

    var sliderTetrisMinutes by remember(viewModel.tetrisMinutes) {
        mutableIntStateOf(viewModel.tetrisMinutes)
    }
    var sliderDurationMinutes by remember(viewModel.durationMinutes) {
        mutableIntStateOf(viewModel.durationMinutes)
    }

    fun pendingActionFor(
        action: SecurityManager.ImpulseAction = viewModel.action,
        durationMinutes: Int = viewModel.durationMinutes,
        suspendedApps: Set<String> = viewModel.suspendedApps,
        tetrisUnlock: Boolean = viewModel.tetrisUnlock,
        tetrisMinutes: Int = viewModel.tetrisMinutes
    ) = PendingAction.UpdateImpulseLock(
        actionName = action.name,
        durationMinutes = durationMinutes,
        suspendedApps = suspendedApps.sorted(),
        tetrisUnlock = tetrisUnlock,
        tetrisMinutes = tetrisMinutes
    )

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.impulse_lock_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        // --- Unlock delay: Tetris before applying a ready 7-day unlock ---
        item {
            SectionHeader(
                title = stringResource(R.string.challenge_option_tetris),
                description = stringResource(R.string.challenge_option_tetris_desc)
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.challenge_option_tetris)) },
                trailingContent = {
                    Switch(
                        checked = viewModel.tetrisUnlock,
                        onCheckedChange = { enable ->
                            val loosening = viewModel.tetrisUnlock && !enable
                            gate.change(
                                loosening = loosening,
                                title = context.getString(R.string.timelock_title_loosen_impulse),
                                description = context.getString(R.string.challenge_option_tetris),
                                dedupeKey = "impulse_lock_settings",
                                pendingAction = pendingActionFor(tetrisUnlock = enable),
                                onTighten = { viewModel.updateTetrisUnlock(enable) }
                            )
                        }
                    )
                }
            )
            if (viewModel.tetrisUnlock) {
                Text(
                    stringResource(R.string.tetris_duration),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    formatDuration(sliderTetrisMinutes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = sliderTetrisMinutes.toFloat(),
                    onValueChange = { sliderTetrisMinutes = it.roundToInt() },
                    onValueChangeFinished = {
                        val target = sliderTetrisMinutes
                        if (target != viewModel.tetrisMinutes) {
                            val loosening = target < viewModel.tetrisMinutes
                            gate.change(
                                loosening = loosening,
                                title = context.getString(R.string.timelock_title_loosen_impulse),
                                description = context.getString(R.string.tetris_duration),
                                dedupeKey = "impulse_lock_settings",
                                pendingAction = pendingActionFor(tetrisMinutes = target),
                                onCancel = { sliderTetrisMinutes = viewModel.tetrisMinutes },
                                onTighten = { viewModel.updateTetrisMinutes(target) }
                            )
                        }
                    },
                    valueRange = SecurityManager.TETRIS_MIN_MINUTES.toFloat()..
                        SecurityManager.TETRIS_MAX_MINUTES.toFloat(),
                    steps = SecurityManager.TETRIS_MAX_MINUTES - SecurityManager.TETRIS_MIN_MINUTES - 1,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // --- What the panic button does ---
        item {
            SectionHeader(
                title = stringResource(R.string.impulse_action_section),
                description = stringResource(R.string.impulse_action_desc)
            )
            SecurityManager.ImpulseAction.entries.forEach { action ->
                OptionRow(
                    selected = viewModel.action == action,
                    title = when (action) {
                        SecurityManager.ImpulseAction.TIMER_ONLY ->
                            stringResource(R.string.impulse_action_timer_only)
                        SecurityManager.ImpulseAction.TIMER_AND_SUSPEND ->
                            stringResource(R.string.impulse_action_suspend_apps)
                    },
                    supporting = when (action) {
                        SecurityManager.ImpulseAction.TIMER_ONLY ->
                            stringResource(R.string.impulse_action_timer_only_desc)
                        SecurityManager.ImpulseAction.TIMER_AND_SUSPEND ->
                            stringResource(R.string.impulse_action_suspend_apps_desc)
                    },
                    onClick = {
                        if (action != viewModel.action) {
                            val loosening = viewModel.action == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND &&
                                action == SecurityManager.ImpulseAction.TIMER_ONLY
                            gate.change(
                                loosening = loosening,
                                title = context.getString(R.string.timelock_title_loosen_impulse),
                                description = context.getString(R.string.impulse_action_section),
                                dedupeKey = "impulse_lock_settings",
                                pendingAction = pendingActionFor(action = action),
                                onTighten = { viewModel.updateAction(action) }
                            )
                        }
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // --- How long the block lasts ---
        item {
            SectionHeader(
                title = stringResource(R.string.impulse_duration_section),
                description = stringResource(R.string.impulse_duration_desc)
            )
            Text(
                formatDuration(sliderDurationMinutes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Slider(
                value = sliderDurationMinutes.toFloat(),
                onValueChange = { sliderDurationMinutes = it.roundToInt() },
                onValueChangeFinished = {
                    val target = sliderDurationMinutes
                    if (target != viewModel.durationMinutes) {
                        val loosening = target < viewModel.durationMinutes
                        gate.change(
                            loosening = loosening,
                            title = context.getString(R.string.timelock_title_loosen_impulse),
                            description = context.getString(R.string.impulse_duration_section),
                            dedupeKey = "impulse_lock_settings",
                            pendingAction = pendingActionFor(durationMinutes = target),
                            onCancel = { sliderDurationMinutes = viewModel.durationMinutes },
                            onTighten = { viewModel.updateDurationMinutes(target) }
                        )
                    }
                },
                valueRange = SecurityManager.IMPULSE_MIN_DURATION_MINUTES.toFloat()..
                    SecurityManager.IMPULSE_MAX_DURATION_MINUTES.toFloat(),
                steps = ((SecurityManager.IMPULSE_MAX_DURATION_MINUTES -
                    SecurityManager.IMPULSE_MIN_DURATION_MINUTES) / 15) - 1,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatDuration(SecurityManager.IMPULSE_MIN_DURATION_MINUTES),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    formatDuration(SecurityManager.IMPULSE_MAX_DURATION_MINUTES),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // --- Apps to suspend, only relevant for the second action ---
        if (viewModel.action == SecurityManager.ImpulseAction.TIMER_AND_SUSPEND) {
            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.impulse_apps_section)) },
                    supportingContent = {
                        val count = viewModel.suspendedApps.size
                        Text(
                            if (count == 0) stringResource(R.string.no_apps)
                            else stringResource(R.string.visual_blocking_apps_selected, count)
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Apps, null) },
                    trailingContent = {
                        Button(onClick = { showAppSelectionDialog = true }) {
                            Text(stringResource(R.string.select_apps))
                        }
                    }
                )
                if (!accessibilityActive) {
                    Text(
                        stringResource(R.string.impulse_apps_requires_accessibility),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAppSelectionDialog) {
        AppPickerDialog(
            apps = viewModel.apps,
            isLoading = viewModel.isLoadingApps,
            isSelected = { viewModel.suspendedApps.contains(it) },
            onToggle = { pkg ->
                val removing = pkg in viewModel.suspendedApps
                if (removing) {
                    showAppSelectionDialog = false
                    gate.requestUnlock(
                        title = context.getString(R.string.timelock_title_loosen_impulse),
                        description = pkg,
                        dedupeKey = "impulse_lock_settings",
                        pendingAction = pendingActionFor(suspendedApps = viewModel.suspendedApps - pkg)
                    )
                } else {
                    viewModel.toggleAppSelection(pkg)
                }
            },
            onDismiss = { showAppSelectionDialog = false }
        )
    }

    TimelockGateDialog(gate)
}

@Composable
private fun SectionHeader(title: String, description: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun OptionRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    supporting: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.time_minutes, remaining)
        remaining == 0 -> stringResource(R.string.time_hours, hours)
        else -> stringResource(R.string.time_hours_minutes, hours, remaining)
    }
}
