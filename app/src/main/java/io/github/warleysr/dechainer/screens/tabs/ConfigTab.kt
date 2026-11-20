package io.github.warleysr.dechainer.screens.tabs
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.outlined.Dns
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DnsFailoverRule
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.NotificationGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.NotificationsActive
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    var showDnsDialog by remember { mutableStateOf(false) }
    var dnsErrorRes by remember { mutableStateOf<Int?>(null) }
    var isApplyingDns by remember { mutableStateOf(false) }
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
    val ownerPrivilegesFirstMsg = stringResource(R.string.get_owner_privileges_first)

    var shuffleKeyboard by remember { mutableStateOf(SecurityManager.isShuffleKeyboardEnabled(context)) }

    val advancedBlocking = DechainerAccessibilityService.isRunning
    var hasSecureGuard by remember { mutableStateOf(AccessibilityWatchdog.hasPermission(context)) }
    var hasShizuku by remember { mutableStateOf(DeviceOwnerRepository.checkShizukuPermission()) }
    var notificationAccess by remember { mutableStateOf(NotificationGuard.isListenerEnabled(context)) }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val refreshGuards = {
            hasShizuku = DeviceOwnerRepository.checkShizukuPermission()
            hasSecureGuard = AccessibilityWatchdog.hasPermission(context)
            notificationAccess = NotificationGuard.isListenerEnabled(context)
            if (!hasSecureGuard && hasShizuku) {
                scope.launch(Dispatchers.IO) {
                    AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(context)
                    val granted = AccessibilityWatchdog.hasPermission(context)
                    withContext(Dispatchers.Main) { hasSecureGuard = granted }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshGuards()
        }
        refreshGuards()
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    var forcedRemovalRemaining by remember { mutableLongStateOf(SecurityManager.getForcedRemovalRemainingTime(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            forcedRemovalRemaining = SecurityManager.getForcedRemovalRemainingTime(context)
            kotlinx.coroutines.delay(60000.milliseconds)
        }
    }

    val dnsStatus by DnsGuard.status.collectAsState()
    LaunchedEffect(viewModel.isDeviceOwner()) {
        if (viewModel.isDeviceOwner()) {
            DnsGuard.refreshReachabilityAsync(context)
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
                val accessibilityStatusText = when {
                    advancedBlocking && hasSecureGuard -> stringResource(R.string.activity_blocker_protected)
                    advancedBlocking -> stringResource(R.string.activity_blocker_unprotected)
                    else -> stringResource(R.string.activity_blocker_description)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.activity_blocker)) },
                    supportingContent = { Text(accessibilityStatusText) },
                    leadingContent = { Icon(Icons.Outlined.Accessibility, "") },
                    modifier = Modifier.clickable(enabled = advancedBlocking && !hasSecureGuard) {
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) {
                                AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(context)
                            }
                            hasSecureGuard = granted
                            if (!granted) snackbarHostState.showSnackbar(shizukuNotRunningMsg)
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = advancedBlocking,
                            onCheckedChange = { enable ->
                                val openSettings = {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } catch (_: ActivityNotFoundException) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(shizukuNotRunningMsg)
                                        }
                                    }
                                }
                                if (enable) {
                                    scope.launch {
                                        val enabledViaShizuku = withContext(Dispatchers.IO) {
                                            if (Shizuku.pingBinder() && DeviceOwnerRepository.checkShizukuPermission()) {
                                                AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(context)
                                                DeviceOwnerRepository.changeAccessibilityPermission(true)
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        hasSecureGuard = AccessibilityWatchdog.hasPermission(context)
                                        if (!enabledViaShizuku) openSettings()
                                    }
                                } else {
                                    recoveryGate.run {
                                        DechainerAccessibilityService.prepareServiceDisable()
                                        AccessibilityWatchdog.setWanted(context, false)
                                        scope.launch {
                                            val disabledViaShizuku = withContext(Dispatchers.IO) {
                                                if (Shizuku.pingBinder() && DeviceOwnerRepository.checkShizukuPermission()) {
                                                    DeviceOwnerRepository.changeAccessibilityPermission(false)
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            if (!disabledViaShizuku) openSettings()
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                val notifStatusText = when {
                    notificationAccess && hasShizuku -> stringResource(R.string.notification_access_protected)
                    notificationAccess -> stringResource(R.string.notification_access_manual)
                    else -> stringResource(R.string.notification_access_desc)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notification_access_title)) },
                    supportingContent = { Text(notifStatusText) },
                    leadingContent = { Icon(Icons.Outlined.NotificationsActive, "") },
                    trailingContent = {
                        Switch(
                            checked = notificationAccess,
                            onCheckedChange = { enable ->
                                val openListenerSettings = {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    } catch (_: ActivityNotFoundException) {
                                        scope.launch { snackbarHostState.showSnackbar(shizukuNotRunningMsg) }
                                    }
                                }
                                if (enable) {
                                    scope.launch {
                                        val enabledViaShizuku = withContext(Dispatchers.IO) {
                                            NotificationGuard.enableListenerViaShizuku(context)
                                        }
                                        hasShizuku = DeviceOwnerRepository.checkShizukuPermission()
                                        hasSecureGuard = AccessibilityWatchdog.hasPermission(context)
                                        notificationAccess = NotificationGuard.isListenerEnabled(context)
                                        if (!enabledViaShizuku) openListenerSettings()
                                    }
                                } else {
                                    recoveryGate.run {
                                        scope.launch {
                                            val disabledViaShizuku = withContext(Dispatchers.IO) {
                                                NotificationGuard.disableListenerViaShizuku(context)
                                            }
                                            notificationAccess = NotificationGuard.isListenerEnabled(context)
                                            if (!disabledViaShizuku) openListenerSettings()
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dns_settings)) },
                    supportingContent = { Text(dnsSummaryText(dnsStatus)) },
                    leadingContent = { Icon(Icons.Outlined.Dns, "") },
                    modifier = Modifier.clickable {
                        if (!viewModel.isDeviceOwner()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(ownerPrivilegesFirstMsg)
                            }
                            return@clickable
                        }
                        dnsErrorRes = null
                        showDnsDialog = true
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
                        showFinishForcedRemovalDialog = false
                        confirmForcedRemoval = false
                        viewModel.removeDeviceOwner {
                            SecurityManager.cancelForcedRemoval(context)
                            forcedRemovalRemaining = -1L
                            (context as? android.app.Activity)?.recreate()
                        }
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

    if (showDnsDialog) {
        val currentDns = viewModel.getPrivateDNS()
        val currentAuto = DnsGuard.isAutoFailover(context)
        val externalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        DnsSelectionDialog(
            currentDns = currentDns,
            currentAutoFailover = currentAuto,
            errorRes = dnsErrorRes,
            isLoading = isApplyingDns,
            onDismiss = { if (!isApplyingDns) showDnsDialog = false },
            onApply = { host, autoFailover ->
                val runApply = {
                    dnsErrorRes = null
                    isApplyingDns = true
                    scope.launch {
                        val result = withTimeoutOrNull(15000.milliseconds) {
                            val deferred = externalScope.async {
                                if (host == DNS_AUTO) viewModel.setAutoPrivateDNS()
                                else viewModel.setPrivateDNS(host, autoFailover)
                            }
                            deferred.await()
                        }
                        isApplyingDns = false
                        if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                            showDnsDialog = false
                        } else {
                            dnsErrorRes = when (result) {
                                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> R.string.dns_error_not_serving
                                else -> R.string.dns_error_failure
                            }
                        }
                    }
                }
                if (currentDns == null) runApply() else recoveryGate.run { runApply() }
            }
        )
    }


    if (showKeyboardDialog) {
        KeyboardTypeDialog(
            currentShuffle = shuffleKeyboard,
            onDismiss = { showKeyboardDialog = false },
            onApply = { isShuffle ->
                if (isShuffle == shuffleKeyboard) {
                    showKeyboardDialog = false
                    return@KeyboardTypeDialog
                }
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
        "pt" to "PortuguÃªs"
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

private const val DNS_AUTO = "auto"

@Composable
private fun dnsDisplayName(host: String): String =
    DeviceOwnerRepository.CLEAN_DNS_PRESETS.firstOrNull { it.second.equals(host, ignoreCase = true) }?.first
        ?: host

@Composable
private fun dnsSummaryText(status: DnsGuard.DnsStatus): String {
    val active = status.activeHost ?: return stringResource(R.string.dns_description)
    val base = if (status.autoFailover) {
        stringResource(R.string.dns_status_auto_tag, dnsDisplayName(active))
    } else {
        dnsDisplayName(active)
    }
    return when (status.reachable[active]) {
        true -> stringResource(R.string.dns_status_active_working, base)
        false -> stringResource(R.string.dns_status_active_unreachable, base)
        null -> stringResource(R.string.dns_status_active_checking, base)
    }
}

@Composable
fun DnsSelectionDialog(
    currentDns: String?,
    currentAutoFailover: Boolean = false,
    errorRes: Int? = null,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (String, Boolean) -> Unit
) {
    val options = DeviceOwnerRepository.CLEAN_DNS_PRESETS

    var selectedOption by remember {
        mutableStateOf(
            when {
                currentAutoFailover && DnsFailoverRule.isCleanHost(currentDns) -> DNS_AUTO
                options.any { it.second == currentDns } -> currentDns
                currentDns != null -> "custom"
                else -> DNS_AUTO
            }
        )
    }
    var autoSwitch by remember { mutableStateOf(currentAutoFailover || currentDns == null) }
    var customHost by remember { mutableStateOf(if (selectedOption == "custom") currentDns ?: "" else "") }

    var probeTrigger by remember { mutableIntStateOf(0) }
    var reachable by remember { mutableStateOf<Map<String, Boolean?>>(options.associate { it.second to null }) }
    LaunchedEffect(probeTrigger) {
        reachable = options.associate { it.second to null }
        options.forEach { (_, host) ->
            launch {
                val ok = withContext(Dispatchers.IO) { DnsGuard.canReachDoT(host) }
                reachable = reachable + (host to ok)
            }
        }
    }
    val erroredChoice = remember(errorRes) { selectedOption }
    var unreachableTapped by remember(selectedOption) { mutableStateOf(false) }

    val isCustomValid = remember(customHost) {
        customHost.matches(Regex("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}\$"))
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dns_settings))
                TextButton(
                    enabled = !isLoading,
                    onClick = { probeTrigger++ }
                ) {
                    Text(stringResource(R.string.dns_recheck))
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { selectedOption = DNS_AUTO }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedOption == DNS_AUTO,
                        onClick = { selectedOption = DNS_AUTO },
                        enabled = !isLoading
                    )
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(stringResource(R.string.dns_auto), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.dns_auto_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    val anyWorking = reachable.values.any { it == true }
                    val allChecked = reachable.values.none { it == null }
                    when {
                        anyWorking -> Text("✓", color = Color(26, 163, 63), fontWeight = FontWeight.Bold)
                        allChecked -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        else -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                options.forEach { (name, host) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { selectedOption = host }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOption == host,
                            onClick = { selectedOption = host },
                            enabled = !isLoading
                        )
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                                if (host.equals(currentDns, ignoreCase = true)) {
                                    Text(
                                        text = stringResource(R.string.dns_badge_connected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(host, style = MaterialTheme.typography.bodySmall)
                        }
                        when (reachable[host]) {
                            null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            true -> Text("✓", color = Color(26, 163, 63), fontWeight = FontWeight.Bold)
                            false -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { selectedOption = "custom" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedOption == "custom",
                        onClick = { selectedOption = "custom" },
                        enabled = !isLoading
                    )
                    Text(stringResource(R.string.custom), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                if (selectedOption == "custom") {
                    OutlinedTextField(
                        value = customHost,
                        onValueChange = { customHost = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        placeholder = { Text(stringResource(R.string.dns_host_hint)) },
                        singleLine = true,
                        enabled = !isLoading,
                        isError = !isCustomValid && customHost.isNotEmpty(),
                        supportingText = {
                            if (!isCustomValid && customHost.isNotEmpty()) {
                                Text(stringResource(R.string.invalid_dns_host))
                            }
                        }
                    )
                }
                if (selectedOption != DNS_AUTO) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { autoSwitch = !autoSwitch }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = autoSwitch,
                            onCheckedChange = { autoSwitch = it },
                            enabled = !isLoading
                        )
                        Text(
                            text = stringResource(R.string.dns_auto_switch_label),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                if (unreachableTapped) {
                    Text(
                        text = stringResource(R.string.dns_error_unreachable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (reachable.values.all { it == false }) {
                    Text(
                        text = stringResource(R.string.dns_all_unreachable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (errorRes != null && selectedOption == erroredChoice) {
                    Text(
                        text = stringResource(errorRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            val canApply = selectedOption != null && (selectedOption != "custom" || isCustomValid) && !isLoading
            TextButton(
                enabled = canApply,
                onClick = {
                    val finalHost = when (selectedOption) {
                        "custom" -> customHost
                        else -> selectedOption
                    }!!
                    val effectiveAuto = finalHost == DNS_AUTO || autoSwitch
                    if (finalHost != DNS_AUTO && reachable[finalHost] == false) {
                        unreachableTapped = true
                    } else {
                        onApply(finalHost, effectiveAuto)
                    }
                }
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.apply))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
