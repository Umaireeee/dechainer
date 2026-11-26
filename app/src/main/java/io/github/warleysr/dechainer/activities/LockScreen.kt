package io.github.warleysr.dechainer.activities

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shop
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import io.github.warleysr.dechainer.data.UpdatePassState
import io.github.warleysr.dechainer.screens.common.ApkUpdateDialog
import io.github.warleysr.dechainer.screens.common.UsageLimitsOverview
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Entry gate of the app. Authentication is deliberately *not* triggered on open: the panic button
 * has to stay one tap away at all times, so the biometric prompt only runs when the user actually
 * asks to get in. Order is therefore: open -> "access app" -> biometrics. Loosening restrictions
 * is gated by the 7-day timelock queue (see TimelockGate).
 */
@Composable
fun LockScreen(isOwner: Boolean, onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    var authError by remember { mutableStateOf<String?>(null) }
    var impulseRemaining by remember {
        mutableLongStateOf(SecurityManager.getImpulseBlockRemainingTime(context))
    }
    var showingLimits by remember { mutableStateOf(false) }

    val passState by PlayStoreUpdateManager.state.collectAsState()
    val apkUpdatesAvailable = remember(passState.active) {
        ApkUpdateInstaller.isInstallRestricted(context)
    }
    var showingUpdateHub by remember { mutableStateOf(false) }
    var pickedApk by rememberSaveable { mutableStateOf<Uri?>(null) }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickedApk = uri
    }

    fun proceedAfterAuthentication() {
        val remaining = SecurityManager.getImpulseBlockRemainingTime(context)
        if (remaining > 0) {
            impulseRemaining = remaining
            return
        }
        authError = null
        onAuthenticated()
    }

    val launcher = rememberAuthenticationLauncher(
        resultCallback = object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) {
                when (result) {
                    is AuthenticationResult.Success -> proceedAfterAuthentication()
                    is AuthenticationResult.Error -> {
                        if (result.errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            result.errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            result.errorCode != BiometricPrompt.ERROR_CANCELED
                        ) {
                            authError = result.errString.toString()
                        }
                    }
                    else -> {}
                }
            }

            override fun onAuthAttemptFailed() {
                authError = context.getString(R.string.biometric_error)
            }
        }
    )

    fun launchAuthentication() {
        val remaining = SecurityManager.getImpulseBlockRemainingTime(context)
        if (remaining > 0) {
            impulseRemaining = remaining
            return
        }

        val biometricManager = BiometricManager.from(context)
        val canUseBiometric = biometricManager.canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        val canUseDeviceCredential = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

        when {
            canUseBiometric -> {
                val request = if (canUseDeviceCredential) {
                    AuthenticationRequest.biometricRequest(
                        title = context.getString(R.string.biometric_title),
                        AuthenticationRequest.Biometric.Fallback.DeviceCredential
                    ) {
                        setMinStrength(AuthenticationRequest.Biometric.Strength.Class3())
                        setSubtitle(context.getString(R.string.biometric_subtitle))
                    }
                } else {
                    AuthenticationRequest.biometricRequest(
                        title = context.getString(R.string.biometric_title)
                    ) {
                        setMinStrength(AuthenticationRequest.Biometric.Strength.Class3())
                        setSubtitle(context.getString(R.string.biometric_subtitle))
                    }
                }
                launcher.launch(request)
            }

            canUseDeviceCredential -> {
                val request = AuthenticationRequest.credentialRequest(
                    title = context.getString(R.string.biometric_title)
                ) {
                    setSubtitle(context.getString(R.string.biometric_subtitle))
                }
                launcher.launch(request)
            }

            else -> proceedAfterAuthentication()
        }
    }

    LaunchedEffect(impulseRemaining > 0, passState.active) {
        while (true) {
            impulseRemaining = SecurityManager.getImpulseBlockRemainingTime(context)
            PlayStoreUpdateManager.getRemainingMillis(context)
            delay(if (impulseRemaining > 0 || passState.active) 1.seconds else 10.seconds)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {

        if (showingLimits) {
            BackHandler { showingLimits = false }
            UsageLimitsOverview(onClose = { showingLimits = false })
            return@Surface
        }

        val secondaryButtons: @Composable (showAccess: Boolean) -> Unit = { showAccess ->
            if (showAccess) {
                SmallActionButton(
                    icon = Icons.Outlined.LockOpen,
                    title = stringResource(R.string.access_app),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { launchAuthentication() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isOwner) {
                if (showAccess) Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    QuietActionButton(
                        icon = Icons.Outlined.HourglassBottom,
                        title = stringResource(R.string.view_usage_limits),
                        onClick = { showingLimits = true },
                        modifier = Modifier.weight(1f)
                    )
                    if (showAccess && apkUpdatesAvailable) {
                        QuietActionButton(
                            icon = Icons.Outlined.SystemUpdate,
                            title = stringResource(R.string.apk_update_button),
                            onClick = { showingUpdateHub = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (impulseRemaining > 0) {
                    ImpulseCountdown(impulseRemaining)
                    Spacer(modifier = Modifier.height(16.dp))
                    secondaryButtons(false)
                } else {
                    if (isOwner && passState.active) {
                        UpdatePassCard(
                            state = passState,
                            onOpenPlayStore = {
                                if (!PlayStoreUpdateManager.openPlayStoreUpdates(context)) {
                                    Toast.makeText(context, R.string.update_pass_play_store_missing, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onFinish = { PlayStoreUpdateManager.endPass(context) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (isOwner) {
                        BigActionButton(
                            icon = Icons.Filled.Warning,
                            title = stringResource(R.string.having_impulses),
                            subtitle = stringResource(R.string.having_impulses_subtitle),
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            height = 104.dp,
                            onClick = {
                                PlayStoreUpdateManager.endPass(context)
                                SecurityManager.startImpulseBlock(context)
                                impulseRemaining = SecurityManager.getImpulseBlockRemainingTime(context)
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    secondaryButtons(true)

                    if (authError != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            authError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (showingUpdateHub) {
            UpdateAppsHubDialog(
                onStartPlayStorePass = {
                    showingUpdateHub = false
                    if (PlayStoreUpdateManager.startPass(context)) {
                        if (!PlayStoreUpdateManager.openPlayStoreUpdates(context)) {
                            Toast.makeText(context, R.string.update_pass_play_store_missing, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, R.string.apk_update_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onPickApk = {
                    showingUpdateHub = false
                    apkPicker.launch(arrayOf("*/*"))
                },
                onDismiss = { showingUpdateHub = false }
            )
        }

        pickedApk?.let { uri ->
            key(uri) { ApkUpdateDialog(uri = uri, onFinish = { pickedApk = null }) }
        }
    }
}

@Composable
private fun UpdatePassCard(
    state: UpdatePassState,
    onOpenPlayStore: () -> Unit,
    onFinish: () -> Unit
) {
    val totalSeconds = (state.remainingMs + 999L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val countdown = "%02d:%02d".format(minutes, seconds)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.update_pass_active_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.update_pass_active_desc, countdown),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (state.blockedNewInstalls > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.update_pass_blocked_count, state.blockedNewInstalls),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_pass_finish_relock), maxLines = 1)
                }
                Button(onClick = onOpenPlayStore, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_pass_open_play_store), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun UpdateAppsHubDialog(
    onStartPlayStorePass: () -> Unit,
    onPickApk: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_hub_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onStartPlayStorePass)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Shop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.update_hub_play_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.update_hub_play_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickApk)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.update_hub_apk_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.update_hub_apk_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SmallActionButton(
    icon: ImageVector,
    title: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier.height(64.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuietActionButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier.height(56.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BigActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    height: Dp,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ImpulseCountdown(remainingMillis: Long) {
    val totalSeconds = (remainingMillis + 999) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val countdown = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.impulse_timer_active),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = countdown,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
