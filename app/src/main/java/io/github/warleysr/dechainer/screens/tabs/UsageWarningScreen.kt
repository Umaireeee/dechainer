package io.github.warleysr.dechainer.screens.tabs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.UsageWarningSettings
import io.github.warleysr.dechainer.models.UsageAlertStage
import io.github.warleysr.dechainer.models.UsageAlertType
import io.github.warleysr.dechainer.viewmodels.UsageWarningViewModel

private fun hasNotificationPermission(context: android.content.Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageWarningScreen(viewModel: UsageWarningViewModel = viewModel()) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.enabled && !hasNotificationPermission(context)) {
            permissionDenied = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        viewModel.updateEnabled(granted)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.usage_warning_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.usage_warning_enable)) },
                supportingContent = {
                    if (permissionDenied) {
                        Text(
                            stringResource(R.string.usage_warning_permission_denied),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                leadingContent = { Icon(Icons.Outlined.NotificationsActive, null) },
                trailingContent = {
                    Switch(
                        checked = viewModel.enabled,
                        onCheckedChange = { checked ->
                            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                permissionDenied = false
                                viewModel.updateEnabled(checked)
                            }
                        }
                    )
                }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        item {
            Text(
                stringResource(R.string.usage_warning_stages_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                stringResource(R.string.usage_warning_stages_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (viewModel.stages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.usage_warning_no_stages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(viewModel.stages.sortedByDescending { it.minutesBefore }, key = { it.id }) { stage ->
            UsageAlertStageRow(
                stage = stage,
                enabled = viewModel.enabled,
                onMinutesChange = { viewModel.updateStageMinutes(stage.id, it) },
                onTypeChange = { viewModel.updateStageType(stage.id, it) },
                onVibrationCountChange = { viewModel.updateStageVibrationCount(stage.id, it) },
                onRemove = { viewModel.removeStage(stage.id) }
            )
        }

        item {
            Row(modifier = Modifier.padding(16.dp)) {
                TextButton(enabled = viewModel.enabled, onClick = { viewModel.addStage() }) {
                    Text(stringResource(R.string.usage_warning_add_stage))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageAlertStageRow(
    stage: UsageAlertStage,
    enabled: Boolean,
    onMinutesChange: (Float) -> Unit,
    onTypeChange: (UsageAlertType) -> Unit,
    onVibrationCountChange: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var minutesText by remember(stage.id, stage.minutesBefore) {
                    mutableStateOf(formatMinutes(stage.minutesBefore))
                }
                OutlinedTextField(
                    value = minutesText,
                    enabled = enabled,
                    onValueChange = { newValue ->
                        if (newValue.matches(Regex("^\\d{0,3}([.,]\\d{0,2})?$"))) {
                            minutesText = newValue
                            newValue.replace(',', '.').toFloatOrNull()?.let {
                                if (it in UsageWarningRange) onMinutesChange(it)
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.usage_warning_stage_minutes_label)) },
                    modifier = Modifier.width(120.dp),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Spacer(Modifier.weight(1f))
                IconButton(enabled = enabled, onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete, stringResource(R.string.usage_warning_remove_stage),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    UsageAlertType.SILENT to stringResource(R.string.usage_warning_stage_type_silent),
                    UsageAlertType.SOUND to stringResource(R.string.usage_warning_stage_type_sound),
                    UsageAlertType.VIBRATE to stringResource(R.string.usage_warning_stage_type_vibrate)
                )
                options.forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = stage.type == type,
                        onClick = { onTypeChange(type) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) { Text(label) }
                }
            }

            if (stage.type == UsageAlertType.VIBRATE) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.usage_warning_stage_vibration_count),
                        modifier = Modifier.weight(1f)
                    )
                    var countText by remember(stage.id, stage.vibrationCount) {
                        mutableStateOf(stage.vibrationCount.toString())
                    }
                    OutlinedTextField(
                        value = countText,
                        enabled = enabled,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() } && newValue.length <= 2) {
                                countText = newValue
                                newValue.toIntOrNull()?.let {
                                    if (it in 1..UsageWarningSettings.MAX_VIBRATION_COUNT) {
                                        onVibrationCountChange(it)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.width(72.dp),
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        }
    }
}

private val UsageWarningRange = UsageWarningSettings.MIN_MINUTES_BEFORE..UsageWarningSettings.MAX_MINUTES_BEFORE

private fun formatMinutes(minutes: Float): String =
    if (minutes == minutes.toInt().toFloat()) minutes.toInt().toString()
    else minutes.toString()
