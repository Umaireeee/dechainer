package io.github.warleysr.dechainer.activities

import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.screens.challenges.MathChallenge
import io.github.warleysr.dechainer.screens.challenges.WordChallenge
import io.github.warleysr.dechainer.security.SecurityManager

/**
 * Entry gate of the app. Authentication is deliberately *not* triggered on open: the panic button
 * has to stay one tap away at all times, so the biometric prompt only runs when the user actually
 * asks to get in. Order is therefore: open -> "access app" -> biometrics -> challenge (if any).
 */
@Composable
fun LockScreen(onAuthenticated: () -> Unit) {
    var challengeMode by remember { mutableStateOf<SecurityManager.EntryChallenge?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun proceedAfterAuthentication() {
        authError = null
        val mode = SecurityManager.getEntryChallenge(context)
        if (mode == SecurityManager.EntryChallenge.OFF) onAuthenticated() else challengeMode = mode
    }

    val launcher = rememberAuthenticationLauncher(
        resultCallback = object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) {
                when (result) {
                    is AuthenticationResult.Success -> proceedAfterAuthentication()
                    is AuthenticationResult.Error -> authError = result.errString.toString()
                    else -> {}
                }
            }

            override fun onAuthAttemptFailed() {
                authError = context.getString(R.string.biometric_error)
            }
        }
    )

    fun launchAuthentication() {
        val biometricManager = BiometricManager.from(context)
        val canUseBiometric = biometricManager.canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        val canUseDeviceCredential = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

        when {
            canUseBiometric -> {
                val request = AuthenticationRequest.biometricRequest(
                    title = context.getString(R.string.biometric_title)
                ) {
                    setSubtitle(context.getString(R.string.biometric_subtitle))
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

            // No biometrics and no device credential configured: nothing to verify against.
            else -> proceedAfterAuthentication()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (challengeMode != null) {
            when (challengeMode) {
                SecurityManager.EntryChallenge.NORMAL -> MathChallenge(onSuccess = onAuthenticated)
                SecurityManager.EntryChallenge.HARD -> WordChallenge(onSuccess = onAuthenticated)
                else -> onAuthenticated()
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (DeviceOwnerRepository.isDeviceOwner()) {
                // The urge lock: ten minutes, only the alarm clock works. The rule lives in the engine;
                // the main screen takes over as soon as it is stored.
                BigActionButton(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.having_impulses),
                    subtitle = stringResource(R.string.having_impulses_subtitle),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    height = 96.dp,
                    onClick = { Thread { LockEngine.startUrgeLock(context) }.start() }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            BigActionButton(
                icon = Icons.Outlined.LockOpen,
                title = stringResource(R.string.access_app),
                subtitle = stringResource(R.string.access_app_subtitle),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                height = 88.dp,
                onClick = { launchAuthentication() }
            )

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
