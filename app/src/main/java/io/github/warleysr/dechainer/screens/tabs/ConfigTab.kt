package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.RecoveryGenerateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.utils.LocaleUtils
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ConfigTab(
    viewModel: DeviceOwnerViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    val recoveryGate = rememberRecoveryGate()
    var showStartForcedRemovalDialog by remember { mutableStateOf(false) }
    var showCancelForcedRemovalDialog by remember { mutableStateOf(false) }
    var showFinishForcedRemovalDialog by remember { mutableStateOf(false) }
    var confirmForcedRemoval by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val shizukuNotRunningMsg = stringResource(R.string.shizuku_not_running)
    val advancedBlockingRequiredMsg = stringResource(R.string.advanced_blocking_required)

    var shuffleKeyboard by remember { mutableStateOf(SecurityManager.isShuffleKeyboardEnabled(context)) }

    val advancedBlocking = DechainerAccessibilityService.isRunning

    var forcedRemovalRemaining by remember { mutableLongStateOf(SecurityManager.getForcedRemovalRemainingTime(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            forcedRemovalRemaining = SecurityManager.getForcedRemovalRemainingTime(context)
            kotlinx.coroutines.delay(60000.milliseconds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.device_owner_state)) },
                    supportingContent = { Text(stringResource(R.string.device_owner_description)) },
                    leadingContent = { Icon(Icons.Outlined.Adb, "") },
                    trailingContent = {
                        val owner = viewModel.isDeviceOwner()
                        val badgeColor = if (owner) Color(26, 163, 63) else Color.Red
                        val badgeText = if (owner) stringResource(R.string.granted) else stringResource(R.string.not_granted)
                        Badge(containerColor = badgeColor, contentColor = Color.White) {
                            Text(badgeText, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    modifier = Modifier.clickable(onClick = { navViewModel.navigateTo("setup_device_owner") })
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                Text(
                    stringResource(R.string.config_section_protection),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.restrictions)) },
                    supportingContent = { Text(stringResource(R.string.system_restrictions_desc)) },
                    leadingContent = { Icon(Icons.Outlined.Block, "") },
                    modifier = Modifier.clickable { navViewModel.navigateTo("restrictions") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.activity_blocker)) },
                    supportingContent = { Text(stringResource(R.string.activity_blocker_description)) },
                    leadingContent = { Icon(Icons.Outlined.Accessibility, "") },
                    trailingContent = {
                        Switch(advancedBlocking, onCheckedChange = { checked ->
                            if (!Shizuku.pingBinder()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(shizukuNotRunningMsg)
                                }
                                return@Switch
                            }
                            recoveryGate.change(loosening = !checked) {
                                DechainerAccessibilityService.prepareServiceDisable()
                                viewModel.changeAccessibilityPermission(checked)
                            }
                        })
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.blocked_activities)) },
                    supportingContent = { Text(stringResource(R.string.blocked_activities_desc)) },
                    leadingContent = { Icon(Icons.Outlined.Block, "") },
                    modifier = Modifier.clickable {
                        if (!advancedBlocking) {
                            scope.launch {
                                snackbarHostState.showSnackbar(advancedBlockingRequiredMsg)
                            }
                            return@clickable
                        }
                        navViewModel.navigateTo("activity_blocker")
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.impulse_lock)) },
                    supportingContent = { Text(stringResource(R.string.impulse_lock_desc)) },
                    leadingContent = { Icon(Icons.Outlined.LockClock, "") },
                    modifier = Modifier.clickable { navViewModel.navigateTo("impulse_lock") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                Text(
                    stringResource(R.string.config_section_security),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                val keyboardLabel = if (shuffleKeyboard)
                    stringResource(R.string.keyboard_shuffle)
                else
                    stringResource(R.string.keyboard_normal)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.keyboard_type)) },
                    supportingContent = { Text(keyboardLabel) },
                    leadingContent = { Icon(Icons.Outlined.Keyboard, "") },
                    modifier = Modifier.clickable { showKeyboardDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.change_recovery_code)) },
                    leadingContent = { Icon(Icons.Outlined.VpnKey, "") },
                    supportingContent = { Text(stringResource(R.string.change_recovery_code_desc)) },
                    modifier = Modifier.clickable {
                        recoveryGate.run { showRecoveryDialog = true }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                val forcedRemovalText = if (forcedRemovalRemaining > 0) {
                    val hours = (forcedRemovalRemaining / (1000 * 60 * 60)).toInt()
                    val minutes = ((forcedRemovalRemaining / (1000 * 60)) % 60).toInt()
                    if (hours > 0) stringResource(R.string.time_hours_minutes, hours, minutes)
                    else stringResource(R.string.time_minutes, minutes)
                } else if (forcedRemovalRemaining == 0L) {
                    stringResource(R.string.active)
                } else null

                ListItem(
                    headlineContent = { Text(stringResource(R.string.forced_removal)) },
                    supportingContent = { Text(stringResource(R.string.forced_removal_desc)) },
                    leadingContent = { Icon(Icons.Outlined.LockClock, "") },
                    trailingContent = forcedRemovalText?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } },
                    modifier = Modifier.clickable {
                        if (forcedRemovalRemaining < 0) showStartForcedRemovalDialog = true
                        else if (forcedRemovalRemaining == 0L) showFinishForcedRemovalDialog = true
                        else showCancelForcedRemovalDialog = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
                    item {
                Text(
                    stringResource(R.string.config_section_general),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.language_settings)) },
                    supportingContent = { Text(stringResource(R.string.language_description)) },
                    leadingContent = { Icon(Icons.Outlined.Language, "") },
                    modifier = Modifier.clickable { showLanguageDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showStartForcedRemovalDialog) {
        AlertDialog(
            onDismissRequest = { showStartForcedRemovalDialog = false },
            title = { Text(stringResource(R.string.forced_removal_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.forced_removal_dialog_text))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SecurityManager.startForcedRemoval(context)
                        forcedRemovalRemaining = SecurityManager.getForcedRemovalRemainingTime(context)
                        showStartForcedRemovalDialog = false
                        confirmForcedRemoval = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartForcedRemovalDialog = false; confirmForcedRemoval = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showCancelForcedRemovalDialog) {
        AlertDialog(
            onDismissRequest = { showCancelForcedRemovalDialog = false },
            title = { Text(stringResource(R.string.forced_removal_cancel_dialog_title)) },
            text = { Text(stringResource(R.string.forced_removal_cancel_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    SecurityManager.cancelForcedRemoval(context)
                    forcedRemovalRemaining = -1L
                    showCancelForcedRemovalDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelForcedRemovalDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (showFinishForcedRemovalDialog) {
        AlertDialog(
            onDismissRequest = { 
                showFinishForcedRemovalDialog = false
                confirmForcedRemoval = false
            },
            title = { Text(stringResource(R.string.forced_removal_finish_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.forced_removal_finish_dialog_text))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmForcedRemoval = !confirmForcedRemoval }
                            .padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = confirmForcedRemoval, onCheckedChange = { confirmForcedRemoval = it })
                        Text(stringResource(R.string.forced_removal_confirm_checkbox), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmForcedRemoval,
                    onClick = {
                        viewModel.removeDeviceOwner()
                        showFinishForcedRemovalDialog = false
                        confirmForcedRemoval = false
                        (context as? android.app.Activity)?.recreate()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    SecurityManager.cancelForcedRemoval(context)
                    forcedRemovalRemaining = -1L
                    showFinishForcedRemovalDialog = false
                    confirmForcedRemoval = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            onDismiss = { showLanguageDialog = false },
            onApply = { languageCode ->
                LocaleUtils.setLocale(context, languageCode)
                showLanguageDialog = false
            }
        )
    }

    if (showKeyboardDialog) {
        KeyboardTypeDialog(
            currentShuffle = shuffleKeyboard,
            onDismiss = { showKeyboardDialog = false },
            onApply = { isShuffle ->
                val loosening = shuffleKeyboard && !isShuffle
                recoveryGate.change(loosening) {
                    shuffleKeyboard = isShuffle
                    SecurityManager.setShuffleKeyboardEnabled(context, isShuffle)
                    showKeyboardDialog = false
                }
                if (!loosening) showKeyboardDialog = false
            }
        )
    }

    if (showRecoveryDialog) {
        RecoveryGenerateDialog(
            onDismiss = { showRecoveryDialog = false },
            onConfirm = { code ->
                SecurityManager.saveRecoveryCode(context, code)
                showRecoveryDialog = false
            }
        )
    }

    RecoveryGateDialog(recoveryGate)
}

@Composable
fun LanguageSelectionDialog(
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val context = LocalContext.current
    val currentLocale = LocaleUtils.getLocale(context)
    val options = listOf(
        "en" to "English",
        "pt" to "Português"
    )
    
    var selectedOption by remember { 
        mutableStateOf(if (currentLocale.startsWith("pt")) "pt" else "en")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_settings)) },
        text = {
            Column {
                options.forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = code }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedOption == code, onClick = { selectedOption = code })
                        Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(selectedOption) }
            ) {
                Text(stringResource(R.string.apply))
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
fun KeyboardTypeDialog(
    currentShuffle: Boolean,
    onDismiss: () -> Unit,
    onApply: (Boolean) -> Unit
) {
    var selectedShuffle by remember { mutableStateOf(currentShuffle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keyboard_type)) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedShuffle = false }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !selectedShuffle, onClick = { selectedShuffle = false })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.keyboard_normal), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.keyboard_normal_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedShuffle = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedShuffle, onClick = { selectedShuffle = true })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.keyboard_shuffle), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.keyboard_shuffle_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(selectedShuffle) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}


