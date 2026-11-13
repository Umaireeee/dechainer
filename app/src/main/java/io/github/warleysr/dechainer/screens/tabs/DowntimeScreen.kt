package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.DowntimeRepository
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.models.Downtime
import io.github.warleysr.dechainer.models.DowntimeRule
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.viewmodels.AppsViewModel

private val DOWNTIME_PRESETS = listOf(
    Preset(R.string.downtime_preset_bedtime, TimeWindow(22 * 60, 7 * 60)),
    Preset(R.string.downtime_preset_work, TimeWindow(9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5))),
    Preset(R.string.downtime_preset_weekend, TimeWindow(8 * 60, 20 * 60, setOf(6, 7)))
)

/** Lists the downtime rules: apps that are suspended while a window runs and given back after. */
@Composable
fun DowntimeScreen(viewModel: AppsViewModel = viewModel()) {
    val recoveryGate = rememberRecoveryGate()
    var rules by remember { mutableStateOf(DowntimeRepository.getRules()) }
    var editing by remember { mutableStateOf<DowntimeRule?>(null) }

    fun save(newRules: List<DowntimeRule>) {
        val old = rules
        recoveryGate.change(Downtime.isLooser(old, newRules, viewModel.groups)) {
            DowntimeRepository.save(newRules)
            rules = newRules
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Bedtime, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.downtime_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.downtime_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rules, key = { it.id }) { rule ->
                    DowntimeCard(
                        rule = rule,
                        groups = viewModel.groups,
                        onOpen = { editing = rule },
                        onToggle = { index, enabled ->
                            val windows = rule.windows.mapIndexed { i, w -> if (i == index) w.copy(enabled = enabled) else w }
                            save(rules.map { if (it.id == rule.id) rule.copy(windows = windows) else it })
                        }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { editing = DowntimeRule(name = "") },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.downtime_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    editing?.let { draft ->
        val isNew = rules.none { it.id == draft.id }
        DowntimeEditor(
            rule = draft,
            isNew = isNew,
            apps = viewModel.apps.filter { it.isLaunchable },
            groups = viewModel.groups,
            onDismiss = { editing = null },
            onSave = { saved ->
                save(if (isNew) rules + saved else rules.map { if (it.id == saved.id) saved else it })
                editing = null
            },
            onDelete = {
                // Removing downtime gives apps back, so it needs the recovery code.
                recoveryGate.run {
                    DowntimeRepository.save(rules.filterNot { it.id == draft.id })
                    rules = rules.filterNot { it.id == draft.id }
                }
                editing = null
            }
        )
    }

    RecoveryGateDialog(recoveryGate)
}

@Composable
private fun DowntimeCard(
    rule: DowntimeRule,
    groups: List<AppGroup>,
    onOpen: () -> Unit,
    onToggle: (index: Int, enabled: Boolean) -> Unit
) {
    val groupNames = groups.filter { it.id in rule.groupIds }.map { it.name }
    val summary = buildList {
        addAll(groupNames)
        if (rule.packageNames.isNotEmpty()) add(stringResource(R.string.schedule_group_apps, rule.packageNames.size))
    }.joinToString(" · ")

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bedtime, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rule.name.ifBlank { stringResource(R.string.downtime_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            rule.windows.forEachIndexed { index, window ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            window.formatted(),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (window.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        Text(daysSummary(window.days), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = window.enabled, onCheckedChange = { onToggle(index, it) })
                }
            }
        }
    }
}

@Composable
private fun DowntimeEditor(
    rule: DowntimeRule,
    isNew: Boolean,
    apps: List<AppItem>,
    groups: List<AppGroup>,
    onDismiss: () -> Unit,
    onSave: (DowntimeRule) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(rule.name) }
    var packages by remember { mutableStateOf(rule.packageNames) }
    var groupIds by remember { mutableStateOf(rule.groupIds) }
    val windows = remember { mutableStateListOf(*rule.windows.toTypedArray()) }
    var picking by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    fun isValid(w: TimeWindow) = w.startMinute != w.endMinute && w.days.isNotEmpty()
    val canSave = (packages.isNotEmpty() || groupIds.isNotEmpty()) && windows.isNotEmpty() && windows.all(::isValid)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    Text(
                        stringResource(if (isNew) R.string.downtime_add else R.string.downtime_edit),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = canSave,
                        onClick = {
                            if (!canSave) { showErrors = true; return@Button }
                            onSave(rule.copy(name = name.trim(), packageNames = packages, groupIds = groupIds, windows = windows.toList()))
                        }
                    ) { Text(stringResource(R.string.confirm)) }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.downtime_editor_hint), style = MaterialTheme.typography.bodyMedium)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.downtime_name)) },
                        placeholder = { Text(stringResource(R.string.downtime_default_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Group, null)
                        Spacer(Modifier.width(8.dp))
                        val count = packages.size + groupIds.size
                        Text(
                            if (count == 0) stringResource(R.string.downtime_choose_apps)
                            else stringResource(R.string.downtime_chosen, groupIds.size, packages.size)
                        )
                    }
                    if (showErrors && packages.isEmpty() && groupIds.isEmpty()) {
                        Text(
                            stringResource(R.string.downtime_error_no_apps),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Text(stringResource(R.string.schedule_presets), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DOWNTIME_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { windows.add(preset.window) },
                                label = { Text(stringResource(preset.labelRes)) }
                            )
                        }
                    }

                    windows.forEachIndexed { index, window ->
                        SlotCard(
                            window = window,
                            showError = showErrors && !isValid(window),
                            onChange = { windows[index] = it },
                            onDelete = { windows.removeAt(index) }
                        )
                    }

                    OutlinedButton(
                        onClick = { windows.add(TimeWindow(22 * 60, 7 * 60)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.schedule_add_slot))
                    }

                    if (!isNew) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.downtime_delete))
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        AppsAndGroupsPickerDialog(
            apps = apps,
            groups = groups,
            selectedPackages = packages,
            selectedGroups = groupIds,
            onConfirm = { p, g -> packages = p; groupIds = g; picking = false },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun AppsAndGroupsPickerDialog(
    apps: List<AppItem>,
    groups: List<AppGroup>,
    selectedPackages: Set<String>,
    selectedGroups: Set<String>,
    onConfirm: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf(selectedPackages) }
    var groupIds by remember { mutableStateOf(selectedGroups) }
    val filteredGroups = remember(query, groups) { groups.filter { it.name.contains(query, ignoreCase = true) } }
    val filteredApps = remember(query, apps) {
        apps.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downtime_choose_apps)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(filteredGroups, key = { "group:${it.id}" }) { group ->
                        val checked = group.id in groupIds
                        PickRow(
                            checked = checked,
                            onToggle = { groupIds = if (checked) groupIds - group.id else groupIds + group.id },
                            icon = { Icon(Icons.Filled.Group, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
                            title = group.name,
                            subtitle = stringResource(R.string.schedule_group_apps, group.packageNames.size)
                        )
                    }
                    items(filteredApps, key = { "app:${it.packageName}" }) { app ->
                        val checked = app.packageName in packages
                        PickRow(
                            checked = checked,
                            onToggle = { packages = if (checked) packages - app.packageName else packages + app.packageName },
                            icon = { AppIcon(app) },
                            title = app.name,
                            subtitle = null
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(packages, groupIds) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PickRow(checked: Boolean, onToggle: () -> Unit, icon: @Composable () -> Unit, title: String, subtitle: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
