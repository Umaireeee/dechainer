package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.PendingChange
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.viewmodels.ActivityBlockerViewModel
import io.github.warleysr.dechainer.viewmodels.GroupedActivityLog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityBlockerScreen(
    viewModel: ActivityBlockerViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    val gate = rememberTimelockGate()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.blocked_activities),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }

        items(viewModel.blockedActivities, key = { "blocked_$it" }) { className ->
            ListItem(
                headlineContent = { Text(className) },
                trailingContent = {
                    IconButton(onClick = {
                        gate.requestUnlock(
                            title = context.getString(R.string.timelock_title_unblock_activity, className),
                            description = className,
                            dedupeKey = "unblock_activity:$className",
                            pendingAction = PendingAction.RemoveBlockedActivity(className)
                        )
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                }
            )
            HorizontalDivider()
        }

        item {
            Text(
                stringResource(R.string.accessed_activities),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        val groupedLogs = viewModel.getGroupedAccessedActivities()
        items(groupedLogs, key = { "group_${it.packageName}" }) { groupedLog ->
            ActivityLogAccordion(groupedLog)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showAddDialog) {
        var activityName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_activity)) },
            text = {
                OutlinedTextField(
                    value = activityName,
                    onValueChange = { activityName = it },
                    label = { Text(stringResource(R.string.activity_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = activityName.trim()
                    if (clean.isNotEmpty()) {
                        PendingChange.schedule(
                            message = context.getString(R.string.restriction_pending),
                            undoLabel = context.getString(R.string.undo),
                            action = { viewModel.addBlockedActivity(clean) }
                        )
                    }
                    showAddDialog = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    TimelockGateDialog(gate)
}

@Composable
fun ActivityLogAccordion(groupedLog: GroupedActivityLog) {
    var expanded by remember(groupedLog.packageName) { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val iconPx = with(LocalDensity.current) { 32.dp.toPx().roundToInt().coerceAtLeast(1) }
    val iconBitmap = remember(groupedLog.packageName, iconPx) {
        runCatching { groupedLog.icon.toBitmap(width = iconPx, height = iconPx).asImageBitmap() }.getOrNull()
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(groupedLog.appName, style = MaterialTheme.typography.titleMedium)
                    Text(groupedLog.packageName, style = MaterialTheme.typography.labelSmall)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider()
                    groupedLog.activities.forEach { log ->
                        val scopedRule = "${log.packageName}/${log.className}"
                        ListItem(
                            headlineContent = {
                                Text(
                                    log.className,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(scopedRule))
                                }) {
                                    Text(stringResource(R.string.copy))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
