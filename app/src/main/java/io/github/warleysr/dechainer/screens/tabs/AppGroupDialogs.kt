package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.screens.common.AppPickerDialog

@Composable
fun GroupsManagementDialog(
    groups: List<AppGroup>,
    onDismiss: () -> Unit,
    onCreateGroup: () -> Unit,
    onSelectGroup: (AppGroup) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_groups)) },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.no_groups), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                        items(groups, key = { it.id }) { group ->
                            ListItem(
                                modifier = Modifier.clickable { onSelectGroup(group) },
                                headlineContent = { Text(group.name) },
                                supportingContent = {
                                    Column {
                                        Text(
                                            stringResource(R.string.group_apps_count, group.packageNames.size),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        if (group.timeLimitMinutes > 0) {
                                            val h = group.timeLimitMinutes / 60
                                            val m = group.timeLimitMinutes % 60
                                            Text(
                                                stringResource(
                                                    R.string.limit,
                                                    "${if (h > 0) "${h}h " else ""}${if (m > 0) "${m}min" else ""}"
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (group.timeWindows.isNotEmpty()) {
                                            Text(
                                                stringResource(
                                                    R.string.time_windows_summary,
                                                    group.timeWindows.joinToString(", ") { it.formatted() }
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreateGroup) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_group))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_group)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun GroupTimeLimitDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableIntStateOf(initialMinutes / 60) }
    var minutes by remember { mutableIntStateOf(initialMinutes % 60) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberPickerWheel(
                        value = hours,
                        range = 0..23,
                        onValueChange = { hours = it },
                        label = stringResource(R.string.hours)
                    )
                    Text(
                        ":",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    NumberPickerWheel(
                        value = minutes,
                        range = 0..59,
                        onValueChange = { minutes = it },
                        label = stringResource(R.string.minutes)
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (hours == 0 && minutes == 0) {
                    Text(stringResource(R.string.none), style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(
                        "Total: ${hours}h ${minutes}min",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hours * 60 + minutes) }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun EditGroupDialog(
    group: AppGroup,
    allApps: List<AppItem>,
    onDismiss: () -> Unit,
    onSave: (name: String, limitMinutes: Int, windows: List<TimeWindow>, packageNames: Set<String>) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(group.name) }
    var limitMinutes by remember { mutableIntStateOf(group.timeLimitMinutes) }
    var windows by remember { mutableStateOf(group.timeWindows) }
    val selectedPackages = remember { mutableStateListOf(*group.packageNames.toTypedArray()) }
    var showTimeLimitDialog by remember { mutableStateOf(false) }
    var showTimeWindowsDialog by remember { mutableStateOf(false) }
    var showAppPickerDialog by remember { mutableStateOf(false) }

    val memberApps = remember(allApps, selectedPackages.toList()) {
        allApps.filter { it.packageName in selectedPackages }.sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.group_name)) }
            )
        },
        text = {
            Column {
                TextButton(onClick = { showTimeLimitDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Timer, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (limitMinutes > 0)
                            stringResource(R.string.limit, "${limitMinutes / 60}h ${limitMinutes % 60}min")
                        else stringResource(R.string.group_time_limit)
                    )
                }
                TextButton(onClick = { showTimeWindowsDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (windows.isNotEmpty()) windows.joinToString(", ") { it.formatted() }
                        else stringResource(R.string.group_time_windows)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.group_members), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { showAppPickerDialog = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add))
                    }
                }
                if (memberApps.isEmpty()) {
                    Text(stringResource(R.string.no_group_members), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(memberApps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = app.icon.toBitmap().asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    app.name,
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                                )
                                IconButton(onClick = { selectedPackages.remove(app.packageName) }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_group), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.trim().ifBlank { group.name }, limitMinutes, windows, selectedPackages.toSet())
            }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )

    if (showTimeLimitDialog) {
        GroupTimeLimitDialog(
            title = stringResource(R.string.group_time_limit_dialog_title, name.ifBlank { group.name }),
            initialMinutes = limitMinutes,
            onDismiss = { showTimeLimitDialog = false },
            onConfirm = {
                limitMinutes = it
                showTimeLimitDialog = false
            }
        )
    }

    if (showTimeWindowsDialog) {
        TimeWindowsDialog(
            title = stringResource(R.string.group_time_windows_dialog_title, name.ifBlank { group.name }),
            initialWindows = windows,
            onDismiss = { showTimeWindowsDialog = false },
            onConfirm = {
                windows = it
                showTimeWindowsDialog = false
            }
        )
    }

    if (showAppPickerDialog) {
        AppPickerDialog(
            apps = allApps,
            isLoading = false,
            isSelected = { it in selectedPackages },
            onToggle = { pkg ->
                if (pkg in selectedPackages) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
            },
            onDismiss = { showAppPickerDialog = false }
        )
    }
}

@Composable
fun AppGroupPickerDialog(
    app: AppItem,
    groups: List<AppGroup>,
    currentGroupId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onCreateNew: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_group_dialog_title, app.name)) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = currentGroupId == null, onClick = { onSelect(null) })
                    Text(stringResource(R.string.no_group))
                }
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(group.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentGroupId == group.id, onClick = { onSelect(group.id) })
                        Text(group.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_group))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
