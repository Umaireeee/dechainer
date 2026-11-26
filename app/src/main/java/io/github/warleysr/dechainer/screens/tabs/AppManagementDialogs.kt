package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.AppTimeWindows
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.isLooserLimit
import io.github.warleysr.dechainer.screens.common.isLooserRestrictions
import io.github.warleysr.dechainer.screens.common.isLooserWindows
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel

@Composable
fun AppManagementDialogs(
    selectedApp: AppItem?,
    onDismissSelectedApp: () -> Unit,
    viewModel: AppsViewModel = viewModel(),
    deviceOwnerViewModel: DeviceOwnerViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    val context = LocalContext.current
    var showTimeLimitDialog by remember { mutableStateOf<AppItem?>(null) }
    var showRestrictionsDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGroupPickerDialog by remember { mutableStateOf<AppItem?>(null) }
    var showGroupsManagementDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf<AppGroup?>(null) }
    val gate = rememberTimelockGate()

    selectedApp?.let { app ->
        AppActionDialog(
            app = app,
            onDismiss = onDismissSelectedApp,
            onBlock = {
                gate.change(
                    loosening = app.isHidden,
                    title = context.getString(R.string.timelock_title_unhide_app, app.name),
                    description = app.packageName,
                    dedupeKey = "unhide:${app.packageName}",
                    pendingAction = PendingAction.UnhideApp(app.packageName)
                ) { viewModel.blockApp(app.packageName, !app.isHidden) }
                onDismissSelectedApp()
            },
            onToggleUninstall = {
                gate.change(
                    loosening = app.isUninstallBlocked,
                    title = context.getString(R.string.timelock_title_allow_uninstall, app.name),
                    description = app.packageName,
                    dedupeKey = "allow_uninstall:${app.packageName}",
                    pendingAction = PendingAction.AllowUninstallApp(app.packageName)
                ) { viewModel.setUninstallBlocked(app.packageName, !app.isUninstallBlocked) }
                onDismissSelectedApp()
            },
            onSuspend = {
                gate.change(
                    loosening = app.isSuspended,
                    title = context.getString(R.string.timelock_title_unsuspend_app, app.name),
                    description = app.packageName,
                    dedupeKey = "unsuspend:${app.packageName}",
                    pendingAction = PendingAction.UnsuspendApp(app.packageName)
                ) { viewModel.suspendApp(app.packageName, !app.isSuspended) }
                onDismissSelectedApp()
            },
            onSetTimeLimit = {
                showTimeLimitDialog = app
                onDismissSelectedApp()
            },
            onSetTimeWindows = {
                navViewModel.pendingScheduleKey = "app:${app.packageName}"
                onDismissSelectedApp()
                navViewModel.navigateTo("schedule")
            },
            onSetGroup = {
                showGroupPickerDialog = app
                onDismissSelectedApp()
            },
            currentGroupName = viewModel.groupFor(app.packageName)?.name,
            onManageRestrictions = {
                showRestrictionsDialog = app
                onDismissSelectedApp()
            }
        )
    }

    showTimeLimitDialog?.let { app ->
        TimeLimitDialog(
            app = app,
            onDismiss = { showTimeLimitDialog = null },
            onConfirm = { limit, reopeningSeconds ->
                val liveApp = viewModel.apps.firstOrNull { it.packageName == app.packageName } ?: app
                val loosening = isLooserLimit(liveApp.timeLimit, limit) || reopeningSeconds < liveApp.reopeningSeconds
                gate.change(
                    loosening = loosening,
                    title = context.getString(R.string.timelock_title_loosen_limit, app.name),
                    description = app.packageName,
                    dedupeKey = "app_limit:${app.packageName}",
                    pendingAction = PendingAction.SetAppTimeLimit(
                        packageName = app.packageName,
                        limitEncoded = TimelockManager.encodeTimeLimit(limit),
                        reopenSeconds = reopeningSeconds
                    )
                ) {
                    viewModel.setAppReopenTime(app.packageName, reopeningSeconds)
                    viewModel.setAppTimeLimit(app.packageName, limit)
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
                val loosening = isLooserRestrictions(current, restrictions)
                gate.change(
                    loosening = loosening,
                    title = context.getString(R.string.timelock_title_loosen_app_restrictions, app.name),
                    description = app.packageName,
                    dedupeKey = "app_restrictions:${app.packageName}",
                    pendingAction = PendingAction.SetAppRestrictions(
                        packageName = app.packageName,
                        bundleJson = TimelockManager.encodeBundle(restrictions)
                    )
                ) {
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
                val leaving = viewModel.groupFor(app.packageName)?.id.let { it != null && it != groupId }
                gate.change(
                    loosening = leaving,
                    title = context.getString(R.string.timelock_title_leave_group, app.name),
                    description = app.packageName,
                    dedupeKey = "app_group:${app.packageName}",
                    pendingAction = PendingAction.SetAppGroup(app.packageName, groupId)
                ) { viewModel.setPackageGroup(app.packageName, groupId) }
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
                val stolenFromOtherGroup = (packageNames - group.packageNames).any { pkg ->
                    viewModel.groupFor(pkg)?.let { it.id != group.id } == true
                }
                val loosening = isLooserLimit(group.timeLimit, limit) ||
                    isLooserWindows(group.timeWindows, windows) ||
                    !packageNames.containsAll(group.packageNames) ||
                    stolenFromOtherGroup
                gate.change(
                    loosening = loosening,
                    title = context.getString(R.string.timelock_title_loosen_group, group.name),
                    description = name,
                    dedupeKey = "edit_group:${group.id}",
                    pendingAction = PendingAction.UpdateGroup(
                        groupId = group.id,
                        name = name,
                        limitEncoded = TimelockManager.encodeTimeLimit(limit),
                        windowsEncoded = AppTimeWindows.encode(windows),
                        packageNames = packageNames.sorted()
                    )
                ) {
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
                gate.requestUnlock(
                    title = context.getString(R.string.timelock_title_delete_group, group.name),
                    description = group.name,
                    dedupeKey = "delete_group:${group.id}",
                    pendingAction = PendingAction.DeleteGroup(group.id)
                )
                showEditGroupDialog = null
            }
        )
    }

    TimelockGateDialog(gate)
}
