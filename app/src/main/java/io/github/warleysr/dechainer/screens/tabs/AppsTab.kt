package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.NoDeviceOwnerPrivileges
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.isLooserLimit
import io.github.warleysr.dechainer.screens.common.isLooserRestrictions
import io.github.warleysr.dechainer.screens.common.isLooserWindows
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.models.TimeLimit
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.warleysr.dechainer.data.UsageStatsRepository
import io.github.warleysr.dechainer.models.AppSection
import io.github.warleysr.dechainer.models.AppSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.RestrictionEntry
import android.os.Bundle
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.window.Dialog

@Composable
fun AppsTab(
    deviceOwnerViewModel: DeviceOwnerViewModel = viewModel(),
    appsViewModel: AppsViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    if (!deviceOwnerViewModel.isDeviceOwner()) {
        NoDeviceOwnerPrivileges(navViewModel)
    } else {
        AppsScreen(appsViewModel, deviceOwnerViewModel, navViewModel)
    }
}

@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    deviceOwnerViewModel: DeviceOwnerViewModel,
    navViewModel: NavigationViewModel = viewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var showTimeLimitDialog by remember { mutableStateOf<AppItem?>(null) }
    var showRestrictionsDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGroupPickerDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGroupsManagementDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf<AppGroup?>(null) }
    var showBulkGroupDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    val selection = remember { mutableStateListOf<String>() }
    val collapsed = remember {
        mutableStateMapOf(
            AppSection.RARELY_USED to true,
            AppSection.NEVER_USED to true,
            AppSection.SYSTEM_TOOLS to true
        )
    }
    val recoveryGate = rememberRecoveryGate()
    var showMenu by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting) { selection.clear() }

    // Weekly usage ranks the list. null = usage access not granted (yet). Re-read whenever the
    // screen comes back to the front, so granting access in Settings shows up right away.
    val context = LocalContext.current
    var weeklyUsage by remember { mutableStateOf<Map<String, Long>?>(null) }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val scope = CoroutineScope(Dispatchers.Main)
        fun refresh() {
            scope.launch {
                weeklyUsage = withContext(Dispatchers.IO) {
                    UsageStatsRepository.tryGrantViaShizuku(context)
                    UsageStatsRepository.weeklyForegroundMillis(context)
                }
            }
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            scope.cancel()
        }
    }

    val restrictedGroupPackages = remember(viewModel.groups) {
        viewModel.groups.filter { it.timeLimit.isSet || it.timeWindows.isNotEmpty() }
            .flatMap { it.packageNames }
            .toSet()
    }

    val searching = searchQuery.isNotBlank()
    val sections = remember(viewModel.apps, viewModel.groups, searchQuery, filter, weeklyUsage) {
        val matching = viewModel.apps.filter { app ->
            (app.name.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)) &&
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.LIMITED -> app.timeLimit.isSet || app.timeWindows.any { it.enabled } ||
                        app.packageName in restrictedGroupPackages
                    AppFilter.SUSPENDED -> app.isSuspended
                    AppFilter.SYSTEM -> app.isSystem
                }
        }
        AppSections.build(matching, weeklyUsage, restrictedGroupPackages)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Column {
                if (selecting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        IconButton(onClick = { selection.clear() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel))
                        }
                        Text(
                            stringResource(R.string.apps_selected, selection.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val packages = selection.toList()
                            recoveryGate.change(loosening = false) {
                                packages.forEach { viewModel.suspendApp(it, true) }
                            }
                            selection.clear()
                        }) { Icon(Icons.Default.Pause, stringResource(R.string.suspend)) }
                        IconButton(onClick = { showBulkGroupDialog = true }) {
                            Icon(Icons.Default.Group, stringResource(R.string.app_group))
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                            placeholder = { Text(stringResource(R.string.search_apps)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true
                        )
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, null)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manage_groups)) },
                                    leadingIcon = { Icon(Icons.Default.Group, null) },
                                    onClick = {
                                        showGroupsManagementDialog = true
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(AppFilter.entries) { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(stringResource(option.labelRes)) }
                            )
                        }
                    }
                }
            }
        }

        if (viewModel.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (weeklyUsage == null) {
                    item(key = "usage_banner") {
                        UsageAccessBanner(onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        })
                    }
                }
                if (sections.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.apps_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp)
                        )
                    }
                }
                sections.forEach { (section, apps) ->
                    // A search should find an app wherever it is, so it opens every section.
                    val isCollapsed = collapsed[section] == true && !searching
                    item(key = "header_${section.name}") {
                        SectionHeader(
                            section = section,
                            count = apps.size,
                            collapsible = section in COLLAPSIBLE_SECTIONS,
                            collapsed = isCollapsed,
                            onToggle = { collapsed[section] = !isCollapsed }
                        )
                    }
                    if (!isCollapsed) {
                        items(apps, key = { "${section.name}:${it.packageName}" }) { app ->
                            AppRow(
                                app = app,
                                viewModel = viewModel,
                                weeklyMillis = weeklyUsage?.get(app.packageName),
                                selecting = selecting,
                                selected = app.packageName in selection,
                                onClick = {
                                    if (selecting) toggleSelection(selection, app.packageName)
                                    else selectedApp = app
                                },
                                onLongClick = { if (!selecting) selection.add(app.packageName) },
                                onToggleSuspend = {
                                    recoveryGate.change(loosening = app.isSuspended) {
                                        viewModel.suspendApp(app.packageName, !app.isSuspended)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        AppActionDialog(
            app = app,
            onDismiss = { selectedApp = null },
            onBlock = {
                recoveryGate.change(loosening = app.isHidden) { viewModel.blockApp(app.packageName, !app.isHidden) }
                selectedApp = null
            },
            onToggleUninstall = {
                recoveryGate.change(loosening = app.isUninstallBlocked) { viewModel.setUninstallBlocked(app.packageName, !app.isUninstallBlocked) }
                selectedApp = null
            },
            onSuspend = {
                recoveryGate.change(loosening = app.isSuspended) { viewModel.suspendApp(app.packageName, !app.isSuspended) }
                selectedApp = null
            },
            onSetTimeLimit = {
                showTimeLimitDialog = app
            },
            onSetTimeWindows = {
                navViewModel.pendingScheduleKey = "app:${app.packageName}"
                selectedApp = null
                navViewModel.navigateTo("schedule")
            },
            onSetGroup = {
                showGroupPickerDialog = app
            },
            currentGroupName = viewModel.groupFor(app.packageName)?.name,
            onManageRestrictions = {
                showRestrictionsDialog = app
            }
        )
    }

    showTimeLimitDialog?.let { app ->
        TimeLimitDialog(
            app = app,
            onDismiss = { showTimeLimitDialog = null },
            onConfirm = { limit, reopeningSeconds ->
                recoveryGate.change(isLooserLimit(app.timeLimit, limit) || reopeningSeconds < app.reopeningSeconds) {
                    viewModel.setAppTimeLimit(app.packageName, limit)
                    viewModel.setAppReopenTime(app.packageName, reopeningSeconds)
                }
                showTimeLimitDialog = null
            }
        )
    }

    showRestrictionsDialog?.let { app ->
        AppRestrictionsDialog(
            app = app,
            viewModel = deviceOwnerViewModel,
            onDismiss = { showRestrictionsDialog = null },
            onSave = { restrictions ->
                val current = deviceOwnerViewModel.getApplicationRestrictions(app.packageName)
                recoveryGate.change(isLooserRestrictions(current, restrictions)) {
                    deviceOwnerViewModel.setApplicationRestrictions(app.packageName, restrictions)
                }
                showRestrictionsDialog = null
            }
        )
    }

    showGroupPickerDialog?.let { app ->
        AppGroupPickerDialog(
            app = app,
            groups = viewModel.groups,
            currentGroupId = viewModel.groupFor(app.packageName)?.id,
            onDismiss = { showGroupPickerDialog = null },
            onSelect = { groupId ->
                // Leaving a group (to none or to another) takes its restrictions away.
                val leaving = viewModel.groupFor(app.packageName)?.id.let { it != null && it != groupId }
                recoveryGate.change(leaving) { viewModel.setPackageGroup(app.packageName, groupId) }
                showGroupPickerDialog = null
            },
            onCreateNew = {
                showGroupPickerDialog = null
                showCreateGroupDialog = true
            }
        )
    }

    if (showGroupsManagementDialog) {
        GroupsManagementDialog(
            groups = viewModel.groups,
            onDismiss = { showGroupsManagementDialog = false },
            onCreateGroup = {
                showGroupsManagementDialog = false
                showCreateGroupDialog = true
            },
            onSelectGroup = { group ->
                showGroupsManagementDialog = false
                showEditGroupDialog = group
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name ->
                // An empty group restricts nothing, so creating one needs no session.
                viewModel.createGroup(name)
                showCreateGroupDialog = false
            }
        )
    }

    showEditGroupDialog?.let { group ->
        EditGroupDialog(
            group = group,
            allApps = viewModel.apps,
            onDismiss = { showEditGroupDialog = null },
            onSave = { name, limit, windows, packageNames ->
                val loosening = isLooserLimit(group.timeLimit, limit) ||
                    isLooserWindows(group.timeWindows, windows) ||
                    !packageNames.containsAll(group.packageNames)
                recoveryGate.change(loosening) {
                    if (name != group.name) viewModel.renameGroup(group.id, name)
                    viewModel.setGroupTimeLimit(group.id, limit)
                    viewModel.setGroupTimeWindows(group.id, windows)
                    viewModel.setGroupPackages(group.id, packageNames)
                }
                showEditGroupDialog = null
            },
            onOpenSchedule = {
                navViewModel.pendingScheduleKey = "group:${group.id}"
                showEditGroupDialog = null
                navViewModel.navigateTo("schedule")
            },
            onDelete = {
                recoveryGate.run { viewModel.deleteGroup(group.id) }
                showEditGroupDialog = null
            }
        )
    }

    if (showBulkGroupDialog) {
        BulkGroupDialog(
            count = selection.size,
            groups = viewModel.groups,
            onDismiss = { showBulkGroupDialog = false },
            onSelect = { groupId ->
                val packages = selection.toList()
                // Moving an app out of its current group takes that group's rules away.
                val leaving = packages.any { pkg ->
                    viewModel.groupFor(pkg)?.id.let { it != null && it != groupId }
                }
                recoveryGate.change(leaving) { packages.forEach { viewModel.setPackageGroup(it, groupId) } }
                showBulkGroupDialog = false
                selection.clear()
            },
            onCreateNew = {
                showBulkGroupDialog = false
                showCreateGroupDialog = true
            }
        )
    }

    RecoveryGateDialog(recoveryGate)
}


private enum class AppFilter(val labelRes: Int) {
    ALL(R.string.apps_filter_all),
    LIMITED(R.string.apps_filter_limited),
    SUSPENDED(R.string.apps_filter_suspended),
    SYSTEM(R.string.apps_filter_system)
}

private val COLLAPSIBLE_SECTIONS = setOf(AppSection.RARELY_USED, AppSection.NEVER_USED, AppSection.SYSTEM_TOOLS)

private fun toggleSelection(selection: MutableList<String>, packageName: String) {
    if (!selection.remove(packageName)) selection.add(packageName)
}

@Composable
private fun UsageAccessBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.apps_usage_access_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.apps_usage_access_desc), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.apps_usage_access_action)) }
        }
    }
}

@Composable
private fun SectionHeader(
    section: AppSection,
    count: Int,
    collapsible: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val title = when (section) {
        AppSection.CONTROLLED -> R.string.apps_section_controlled
        AppSection.MOST_USED -> R.string.apps_section_most_used
        AppSection.RARELY_USED -> R.string.apps_section_rarely_used
        AppSection.NEVER_USED -> R.string.apps_section_never_used
        AppSection.SYSTEM_TOOLS -> R.string.apps_section_system_tools
        AppSection.ALL_APPS -> R.string.apps_section_all
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${stringResource(title)} · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (collapsible) {
            Icon(
                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    app: AppItem,
    viewModel: AppsViewModel,
    weeklyMillis: Long?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSuspend: () -> Unit
) {
    val group = remember(app.packageName, viewModel.groups) { viewModel.groupFor(app.packageName) }

    // One line of usage, one line of rules: enough to see what is going on without opening the app.
    val usageLine = weeklyMillis?.let {
        if (it >= 60_000) stringResource(R.string.apps_week_usage, formatDuration(it))
        else stringResource(R.string.apps_no_use)
    }
    val rules = buildList {
        group?.let { add(stringResource(R.string.group_label, it.name)) }
        if (app.timeLimit.isSet) {
            add(
                stringResource(
                    R.string.apps_limit_used,
                    timeLimitSummary(app.timeLimit, compact = true),
                    formatDuration(viewModel.getAppUsage(app.packageName, inMinutes = true) * 60_000)
                )
            )
        }
        if (group != null && group.timeLimit.isSet) {
            add(
                stringResource(
                    R.string.apps_limit_used,
                    timeLimitSummary(group.timeLimit, compact = true),
                    formatDuration(viewModel.getGroupUsage(group.id, inMinutes = true) * 60_000)
                )
            )
        }
        val today = TimeWindow.allowedSpans(
            listOf(group?.timeWindows.orEmpty(), app.timeWindows),
            java.time.LocalDate.now().dayOfWeek.value
        )
        if (today != null) {
            add(
                if (today.isEmpty()) stringResource(R.string.apps_closed_today)
                else stringResource(R.string.apps_schedule_today, today.joinToString(", ") { it.formatted() })
            )
        }
        if (app.isHidden) add(stringResource(R.string.blocked))
        if (app.isUninstallBlocked) add(stringResource(R.string.protected_label))
        if (app.hasExplicitContent) add(stringResource(R.string.explicit_content))
    }

    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (app.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.apps_tag_system),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        supportingContent = if (usageLine != null || rules.isNotEmpty()) {
            {
                Column {
                    if (usageLine != null) Text(usageLine, style = MaterialTheme.typography.bodySmall)
                    if (rules.isNotEmpty()) {
                        Text(
                            rules.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        } else null,
        leadingContent = {
            val iconBitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
            Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        },
        trailingContent = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                // One tap to block a distraction (or let it go).
                Switch(checked = app.isSuspended, onCheckedChange = { onToggleSuspend() })
            }
        }
    )
}

@Composable
private fun BulkGroupDialog(
    count: Int,
    groups: List<AppGroup>,
    onSelect: (groupId: String?) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_bulk_group_title, count)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.no_group),
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 12.dp)
                )
                groups.forEach { group ->
                    Text(
                        group.name,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(group.id) }.padding(vertical = 12.dp)
                    )
                }
                TextButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_group))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun AppActionDialog(
    app: AppItem,
    onDismiss: () -> Unit,
    onBlock: () -> Unit,
    onToggleUninstall: () -> Unit,
    onSuspend: () -> Unit,
    onSetTimeLimit: () -> Unit,
    onSetTimeWindows: () -> Unit,
    onSetGroup: () -> Unit,
    currentGroupName: String?,
    onManageRestrictions: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val iconBitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                AppActionItem(
                    icon = Icons.Default.Group,
                    label = currentGroupName?.let { stringResource(R.string.group_label, it) } ?: stringResource(R.string.app_group),
                    onClick = onSetGroup
                )
                AppActionItem(
                    icon = Icons.Default.Timer,
                    label = stringResource(R.string.set_time_limit),
                    onClick = onSetTimeLimit
                )
                AppActionItem(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.time_windows),
                    onClick = onSetTimeWindows
                )
                AppActionItem(
                    icon = Icons.Default.Block,
                    label = stringResource(R.string.manage_app_restrictions),
                    onClick = onManageRestrictions
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                AppActionItem(
                    icon = if (app.isSuspended) Icons.Default.PlayArrow else Icons.Default.Pause,
                    label = if (app.isSuspended) stringResource(R.string.unsuspend) else stringResource(R.string.suspend),
                    onClick = onSuspend
                )
                AppActionItem(
                    icon = if (app.isUninstallBlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    label = if (app.isUninstallBlocked) stringResource(R.string.allow_uninstall) else stringResource(R.string.prevent_uninstall),
                    onClick = onToggleUninstall
                )
                AppActionItem(
                    icon = if (app.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    label = if (app.isHidden) stringResource(R.string.unblock) else stringResource(R.string.block),
                    onClick = onBlock
                )
            }
        }
    }
}

@Composable
private fun AppActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun TimeLimitDialog(
    app: AppItem,
    onDismiss: () -> Unit,
    onConfirm: (TimeLimit, Int) -> Unit
) {
    val limitState = rememberTimeLimitEditorState(app.timeLimit)
    var reopeningSeconds by remember { mutableIntStateOf(app.reopeningSeconds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_time_limit_dialog_title, app.name)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                TimeLimitEditor(limitState)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.reopen_time))
                SecondInputField(
                    initialSeconds = reopeningSeconds,
                    onValueChange = { reopeningSeconds = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(limitState.toTimeLimit(), reopeningSeconds) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun NumberPickerWheel(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier.width(70.dp)
) {
    val items = range.toList()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.indexOf(value))
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerIndex = listState.firstVisibleItemIndex
            if (centerIndex in items.indices) {
                currentOnValueChange(items[centerIndex])
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    MaterialTheme.shapes.medium
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
            )
            
            LazyColumn(
                state = listState,
                flingBehavior = snapFlingBehavior,
                contentPadding = PaddingValues(vertical = 40.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(items) { item ->
                    val isSelected = item == value
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.toString().padStart(2, '0'),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontSize = if (isSelected) 22.sp else 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SecondInputField(
    initialSeconds: Int = 0,
    onValueChange: (Int) -> Unit
) {
    var seconds by remember { mutableIntStateOf(initialSeconds) }
    var textFieldValue by remember { mutableStateOf(seconds.toString()) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        SmallIncrementButton(label = "-15") {
            seconds = (seconds - 15).coerceAtLeast(0)
            textFieldValue = seconds.toString()
            onValueChange(seconds)
        }

        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                    textFieldValue = newValue
                    val parsed = newValue.toIntOrNull() ?: 0
                    seconds = parsed
                    onValueChange(parsed)
                }
            },
            modifier = Modifier
                .width(120.dp)
                .padding(horizontal = 8.dp),
            label = { Text(stringResource(R.string.seconds), fontSize = 12.sp) },
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        SmallIncrementButton(label = "+15") {
            seconds += 15
            textFieldValue = seconds.toString()
            onValueChange(seconds)
        }
    }
}

@Composable
fun SmallIncrementButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.height(40.dp)
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}
