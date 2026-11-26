package io.github.warleysr.dechainer.screens.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

@Composable
fun SetupDeviceOwnerPrivileges(viewModel: DeviceOwnerViewModel = viewModel()) {
    val context = LocalContext.current
    var shizukuInstalled by remember { mutableStateOf(viewModel.isShizukuInstalled()) }
    var shizukuRunning by remember { mutableStateOf(Shizuku.pingBinder()) }
    var commandResult by remember { mutableStateOf<String?>(null) }
    val gate = rememberTimelockGate()

    LaunchedEffect(viewModel.isDeviceOwner()) {
        while (!viewModel.isDeviceOwner() && (!shizukuInstalled || !shizukuRunning)) {
            if (!shizukuInstalled)
                shizukuInstalled = viewModel.isShizukuInstalled()

            if (shizukuInstalled && !shizukuRunning)
                shizukuRunning = Shizuku.pingBinder()

            delay(1000)
        }
    }

    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            viewModel.isDeviceOwner() -> DeviceOwnerRemoveContent(
                onRemoveAction = {
                    gate.requestUnlock(
                        title = context.getString(R.string.timelock_title_remove_device_owner),
                        description = context.getString(R.string.remove_privileges_description),
                        dedupeKey = "remove_device_owner",
                        pendingAction = PendingAction.RemoveDeviceOwner
                    )
                }
            )
            !shizukuInstalled -> ShizukuNotInstalledCard(viewModel)
            !shizukuRunning -> ShizukuNotRunningCard(viewModel)
            !viewModel.isShizukuPermissionGranted() -> ShizukuNoPermissionCard()
            else -> DeviceOwnerGrantContent(
                viewModel = viewModel,
                onResult = { result -> commandResult = result }
            )
        }
    }

    commandResult?.let { result ->
        CommandResultDialog(
            result = result,
            onDismiss = { commandResult = null }
        )
    }

    TimelockGateDialog(gate)
}

@Composable
private fun ShizukuNotInstalledCard(viewModel: DeviceOwnerViewModel) {
    SetupStepCard(
        text = stringResource(R.string.shizuku_not_installed),
        buttonText = stringResource(R.string.install_shizuku),
        buttonIcon = Icons.Outlined.InstallMobile,
        onClick = { viewModel.installShizuku() }
    )
}

@Composable
private fun ShizukuNotRunningCard(viewModel: DeviceOwnerViewModel) {
    SetupStepCard(
        text = stringResource(R.string.shizuku_not_running),
        buttonText = stringResource(R.string.setup_shizuku),
        buttonIcon = Icons.Outlined.Link,
        onClick = { viewModel.openShizukuSetupGuide() }
    )
}

@Composable
private fun ShizukuNoPermissionCard() {
    SetupStepCard(
        text = stringResource(R.string.no_permission_shizuku),
        buttonText = stringResource(R.string.ask_shizuku_permission),
        buttonIcon = Icons.Outlined.QuestionMark,
        onClick = { Shizuku.requestPermission(911) }
    )
}

@Composable
private fun DeviceOwnerGrantContent(
    viewModel: DeviceOwnerViewModel,
    onResult: (String) -> Unit
) {
    val accounts = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(Unit) {
        accounts.addAll(viewModel.getAllAccountsViaShizuku())
    }

    val extraUsers = remember { viewModel.getExtraUsersInfo() }
    var isLoading by remember { mutableStateOf(false) }

    when {
        extraUsers.isNotEmpty() -> {
            UserWarningDialog(extraUsers = extraUsers)
        }

        else -> {
            val currentDeviceOwner = viewModel.getCurrentDeviceOwner()
            if (currentDeviceOwner != null) {
                ExistingOwnerDialog(
                    ownerPackage = currentDeviceOwner.first
                )
            } else {
                SetupStepCard(
                    text = if (accounts.isNotEmpty()) {
                        stringResource(R.string.privileges_description_accounts_detected, accounts.size)
                    } else {
                        stringResource(R.string.privileges_description)
                    },
                    buttonText = stringResource(R.string.get_owner_privileges),
                    buttonIcon = Icons.Outlined.Adb,
                    loading = isLoading,
                    onClick = {
                        isLoading = true
                        viewModel.processDeviceOwnerPrivileges { output ->
                            isLoading = false
                            if (output.isNotBlank()) {
                                onResult(output)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceOwnerRemoveContent(onRemoveAction: () -> Unit) {
    SetupStepCard(
        text = stringResource(R.string.remove_privileges_description),
        buttonText = stringResource(R.string.remove_privileges),
        buttonIcon = Icons.Outlined.DeleteForever,
        onClick = onRemoveAction
    )
}
