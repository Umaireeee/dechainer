package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import io.github.warleysr.dechainer.screens.common.groupedRowColors
import io.github.warleysr.dechainer.screens.common.SectionHeader
import io.github.warleysr.dechainer.screens.common.Chevron
import io.github.warleysr.dechainer.screens.common.IconTile
import io.github.warleysr.dechainer.screens.common.GroupedRow
import io.github.warleysr.dechainer.screens.common.GroupPos
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.NoAdultContent
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Web
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
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.UnlockDelayDialog
import io.github.warleysr.dechainer.screens.common.unlockDelayLabel
import io.github.warleysr.dechainer.data.Blocker
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.security.UnlockDelay
import io.github.warleysr.dechainer.screens.common.RecoveryGenerateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import io.github.warleysr.dechainer.viewmodels.Route
import kotlinx.coroutines.CoroutineScope
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ConfigTab(
    viewModel: DeviceOwnerViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    var showDnsDialog by remember { mutableStateOf(false) }
    var dnsErrorRes by remember { mutableStateOf<Int?>(null) }
    var isApplyingDns by remember { mutableStateOf(false) }
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showUnlockDelayDialog by remember { mutableStateOf(false) }
    val recoveryGate = rememberRecoveryGate()
    var showStartForcedRemovalDialog by remember { mutableStateOf(false) }
    var showCancelForcedRemovalDialog by remember { mutableStateOf(false) }
    var showFinishForcedRemovalDialog by remember { mutableStateOf(false) }
    var confirmForcedRemoval by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlockDelay by remember { mutableIntStateOf(SecurityManager.getUnlockDelayMinutes(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val shizukuNotRunningMsg = stringResource(R.string.shizuku_not_running)
    val ownerPrivilegesFirstMsg = stringResource(R.string.get_owner_privileges_first)

    var shuffleKeyboard by remember { mutableStateOf(SecurityManager.isShuffleKeyboardEnabled(context)) }
    var entryLock by remember { mutableStateOf(SecurityManager.isEntryLockEnabled(context)) }
    var showNewPatternDialog by remember { mutableStateOf(false) }

    var forcedRemovalRemaining by remember { mutableLongStateOf(SecurityManager.getForcedRemovalRemainingTime(context)) }
    RepeatWhileVisible(60_000) {
        forcedRemovalRemaining = SecurityManager.getForcedRemovalRemainingTime(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                io.github.warleysr.dechainer.screens.setup.SetupStatusCard(
                    onNavigate = { navViewModel.navigateTo(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item { io.github.warleysr.dechainer.screens.common.FullScreenHint() }
            item { SectionHeader(stringResource(R.string.config_section_protection)) }
            item {
                GroupedRow(GroupPos.Top) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.device_owner_state)) },
                    supportingContent = { Text(stringResource(R.string.device_owner_description)) },
                    leadingContent = { IconTile(Icons.Outlined.Adb) },
                    trailingContent = {
                        val owner = viewModel.isDeviceOwner()
                        // Theme colours, so the badge sits in the palette instead of shouting over it.
                        val badgeColor = if (owner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        val badgeContent = if (owner) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
                        val badgeText = if (owner) stringResource(R.string.granted) else stringResource(R.string.not_granted)
                        Badge(containerColor = badgeColor, contentColor = badgeContent) {
                            Text(badgeText, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    modifier = Modifier.clickable(onClick = { navViewModel.navigateTo(Route.SETUP_DEVICE_OWNER) })
                ) }
            }
            item {
                GroupedRow(GroupPos.Middle) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.dns_settings)) },
                    supportingContent = { 
                        Text( stringResource(R.string.dns_description))
                    },
                    leadingContent = { IconTile(Icons.Outlined.Dns) },
                    trailingContent = { Chevron() },
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
                ) }
            }
            item {
                GroupedRow(GroupPos.Bottom) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.protections)) },
                    supportingContent = { Text(stringResource(R.string.protections_desc)) },
                    leadingContent = { IconTile(Icons.Outlined.Shield) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable {
                        if (!viewModel.isDeviceOwner()) {
                            scope.launch { snackbarHostState.showSnackbar(ownerPrivilegesFirstMsg) }
                            return@clickable
                        }
                        navViewModel.navigateTo(Route.RESTRICTIONS)
                    }
                ) }
            }
            item { SectionHeader(stringResource(R.string.config_section_security)) }
            item {
                GroupedRow(GroupPos.Top) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.change_recovery_code)) },
                    leadingContent = { IconTile(Icons.Outlined.VpnKey) },
                    trailingContent = { Chevron() },
                    supportingContent = { Text(stringResource(R.string.change_recovery_code_desc)) },
                    modifier = Modifier.clickable {
                        recoveryGate.run { showRecoveryDialog = true }
                    }
                ) }
            }
            item {
                GroupedRow(GroupPos.Middle) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.unlock_delay)) },
                    leadingContent = { IconTile(Icons.Outlined.Timer) },
                    trailingContent = { Chevron() },
                    supportingContent = { Text(unlockDelayLabel(unlockDelay)) },
                    modifier = Modifier.clickable { showUnlockDelayDialog = true }
                ) }
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

                GroupedRow(GroupPos.Bottom) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.forced_removal)) },
                    supportingContent = { Text(stringResource(R.string.forced_removal_desc)) },
                    leadingContent = { IconTile(Icons.Outlined.LockClock) },
                    trailingContent = forcedRemovalText?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } },
                    modifier = Modifier.clickable {
                        if (forcedRemovalRemaining < 0) showStartForcedRemovalDialog = true
                        else if (forcedRemovalRemaining == 0L) showFinishForcedRemovalDialog = true
                        else showCancelForcedRemovalDialog = true
                    }
                ) }
            }
            item { SectionHeader(stringResource(R.string.config_section_app)) }
            item {
                GroupedRow(GroupPos.Top) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.urge_settings_title)) },
                    supportingContent = { Text(stringResource(R.string.urge_settings_desc)) },
                    leadingContent = { IconTile(Icons.Outlined.Shield) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable { navViewModel.navigateTo(Route.URGE_SETTINGS) }
                ) }
            }
            item {
                GroupedRow(GroupPos.Middle) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.entry_lock_title)) },
                    supportingContent = { Text(stringResource(R.string.entry_lock_desc)) },
                    leadingContent = { IconTile(Icons.Outlined.Lock) },
                    trailingContent = {
                        Switch(
                            checked = entryLock,
                            onCheckedChange = { on ->
                                // Turning it on tightens, so it is one tap (a pattern is drawn first if
                                // there is none). Turning it off needs the code.
                                if (on) {
                                    if (SecurityManager.hasEntryPattern(context)) {
                                        entryLock = true
                                        SecurityManager.setEntryLockEnabled(context, true)
                                    } else {
                                        showNewPatternDialog = true
                                    }
                                } else {
                                    recoveryGate.run {
                                        entryLock = false
                                        SecurityManager.setEntryLockEnabled(context, false)
                                    }
                                }
                            }
                        )
                    }
                ) }
            }
            item {
                GroupedRow(GroupPos.Middle) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.entry_change_title)) },
                    supportingContent = { Text(stringResource(R.string.entry_change_desc)) },
                    leadingContent = { IconTile(Icons.Outlined.VpnKey) },
                    trailingContent = { Chevron() },
                    // A new opening pattern replaces the old one, so it needs the code like any loosening.
                    modifier = Modifier.clickable { recoveryGate.run { showNewPatternDialog = true } }
                ) }
            }
            item {
                val keyboardLabel = if (shuffleKeyboard)
                    stringResource(R.string.keyboard_shuffle)
                else
                    stringResource(R.string.keyboard_normal)
                GroupedRow(GroupPos.Bottom) { ListItem(
                    colors = groupedRowColors(),
                    headlineContent = { Text(stringResource(R.string.keyboard_type)) },
                    supportingContent = { Text(keyboardLabel) },
                    leadingContent = { IconTile(Icons.Outlined.Keyboard) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable { showKeyboardDialog = true }
                ) }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
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
                    // Throwing the finished wait away is its own, clearly named button: "Close" below
                    // only closes this, so a stray tap can't cost four days.
                    TextButton(
                        onClick = {
                            SecurityManager.cancelForcedRemoval(context)
                            forcedRemovalRemaining = -1L
                            showFinishForcedRemovalDialog = false
                            confirmForcedRemoval = false
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.forced_removal_cancel_action), color = MaterialTheme.colorScheme.error)
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
                    showFinishForcedRemovalDialog = false
                    confirmForcedRemoval = false
                }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (showDnsDialog) {
        val currentDns = viewModel.getPrivateDNS()
        val externalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        DnsSelectionDialog(
            currentDns = currentDns,
            errorRes = dnsErrorRes,
            isLoading = isApplyingDns,
            onDismiss = { if (!isApplyingDns) showDnsDialog = false },
            onApply = { host ->
                recoveryGate.run {
                    dnsErrorRes = null
                    isApplyingDns = true
                    scope.launch {
                        val result = withTimeoutOrNull(15000.milliseconds) {
                            val deferred = externalScope.async {
                                if (host == DNS_OFF) viewModel.turnOffDnsFilter() else viewModel.setPrivateDNS(host)
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
            }
        )
    }

    if (showNewPatternDialog) {
        io.github.warleysr.dechainer.screens.common.ChangePatternDialog(
            onDone = {
                showNewPatternDialog = false
                entryLock = true
                SecurityManager.setEntryLockEnabled(context, true)
            },
            onDismiss = { showNewPatternDialog = false }
        )
    }

    if (showKeyboardDialog) {
        KeyboardTypeDialog(
            currentShuffle = shuffleKeyboard,
            onDismiss = { showKeyboardDialog = false },
            onApply = { isShuffle ->
                recoveryGate.run {
                    shuffleKeyboard = isShuffle
                    SecurityManager.setShuffleKeyboardEnabled(context, isShuffle)
                    showKeyboardDialog = false
                }
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

    if (showUnlockDelayDialog) {
        UnlockDelayDialog(
            current = unlockDelay,
            onPick = { picked ->
                val new = UnlockDelay.clampMinutes(picked)
                showUnlockDelayDialog = false
                val apply = {
                    SecurityManager.setUnlockDelayMinutes(context, new)
                    unlockDelay = new
                }
                // Longer takes effect at once. Shorter or off is loosening: it waits out the
                // current delay like any other change.
                if (UnlockDelay.changeNeedsUnlock(unlockDelay, new)) recoveryGate.run { apply() } else apply()
            },
            onDismiss = { showUnlockDelayDialog = false }
        )
    }

    RecoveryGateDialog(recoveryGate)
}

@Composable
fun DnsSelectionDialog(
    currentDns: String?,
    errorRes: Int? = null,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val options = listOf(
        // All four block adult sites. Which ones work depends on the network, so the dialog checks
        // each from here (✓ / ✕) rather than claiming one is best. The free CleanBrowsing tier is
        // throttled. For full control, use Custom with a NextDNS ID.
        "Cloudflare Family · adult sites and malware" to "family.cloudflare-dns.com",
        "AdGuard Family · adult sites and ads; SafeSearch" to "family.adguard-dns.com",
        "CleanBrowsing Adult · adult sites; Reddit allowed" to "adult-filter-dns.cleanbrowsing.org",
        "CleanBrowsing Family · strictest, but throttled; blocks VPNs, Reddit" to "family-filter-dns.cleanbrowsing.org"
    )
    
    var selectedOption by remember { 
        mutableStateOf(
            when {
                options.any { it.second == currentDns } -> currentDns
                currentDns != null -> "custom"
                else -> DNS_OFF
            }
        )
    }
    var customHost by remember { mutableStateOf(if (selectedOption == "custom") currentDns ?: "" else "") }

    // Reachability from this network, checked when the dialog opens: null while checking. A
    // provider this phone can't reach on port 853 (the Private DNS port) won't work here, so
    // this shows which choices can actually work before you pick one.
    var reachable by remember { mutableStateOf<Map<String, Boolean?>>(options.associate { it.second to null }) }
    LaunchedEffect(Unit) {
        options.forEach { (_, host) ->
            launch {
                val ok = withContext(Dispatchers.IO) { canReachDoT(host) }
                reachable = reachable + (host to ok)
            }
        }
    }
    // An error belongs to the choice that caused it: picking another option hides it.
    val erroredChoice = remember(errorRes) { selectedOption }
    var unreachableTapped by remember(selectedOption) { mutableStateOf(false) }

    val isCustomValid = remember(customHost) {
        customHost.matches(Regex("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}\$"))
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.dns_settings)) },
        text = {
            Column {
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
                            Text(name, fontWeight = FontWeight.Bold)
                            Text(host, style = MaterialTheme.typography.bodySmall)
                        }
                        when (reachable[host]) {
                            null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            true -> Text("✓", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
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
                // Off: the way out when a network blocks Private DNS. Still behind the recovery code.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { selectedOption = DNS_OFF }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedOption == DNS_OFF, onClick = { selectedOption = DNS_OFF }, enabled = !isLoading)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.dns_off), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.dns_off_desc), style = MaterialTheme.typography.bodySmall)
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
                    // Nothing reachable: the network is blocking Private DNS itself.
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
                    val finalHost = when(selectedOption) {
                        "custom" -> customHost
                        else -> selectedOption
                    }
                    // Known unreachable from this network: say so, instead of letting Android's
                    // check fail with an error that blames the server.
                    if (reachable[finalHost] == false) unreachableTapped = true
                    else onApply(finalHost!!)
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

/**
 * Whether [host] completes a Private DNS (DNS-over-TLS, port 853) handshake within 2.5 seconds
 * from this network. Many networks that break Private DNS do so by blocking this port, or the
 * server is too far away to answer in time.
 */
private fun canReachDoT(host: String): Boolean = try {
    // A full TLS handshake with the provider's name, like Android's own check: a captive portal
    // or proxy can accept a bare connection on any port, which would show a false ✓. No ALPN is
    // offered, since Android doesn't send one and some servers reject unknown values.
    val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
    (factory.createSocket() as javax.net.ssl.SSLSocket).use { socket ->
        socket.soTimeout = 2_500
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(host)) }
        socket.connect(java.net.InetSocketAddress(host, 853), 2_500)
        socket.startHandshake()
    }
    true
} catch (_: Exception) {
    false
}

/** The dialog's value for "no filtering". Not a hostname, so it can't collide with one. */
const val DNS_OFF = "off"

