package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.SecretEntry
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.security.AppLock
import io.github.warleysr.dechainer.security.AppLockKind
import io.github.warleysr.dechainer.security.UnlockResult
import kotlinx.coroutines.delay

/**
 * The app lock's own screen: the PIN pad or the pattern grid. A wrong try says how many free tries
 * are left; too many make the app wait, with a countdown. "I forgot it" goes through the recovery
 * code (and its unlock delay) and removes the lock. [onCancel] is null when there is nowhere to go
 * back to; Home and the Urge button are never behind this screen, so there is always a way to start
 * an urge.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val kind = AppLock.kind(ctx)
    if (kind == null) {
        LaunchedEffect(Unit) { onUnlocked() }
        return
    }
    var waitMs by remember { mutableLongStateOf(AppLock.waitRemainingMs(ctx)) }
    var freeLeft by remember { mutableStateOf<Int?>(null) }
    var tooShort by remember { mutableStateOf(false) }
    var frozenMsg by remember { mutableStateOf(false) }
    val gate = rememberRecoveryGate()

    // The countdown only runs while there is something to wait for.
    LaunchedEffect(waitMs > 0L) {
        while (waitMs > 0L) {
            delay(1000)
            waitMs = AppLock.waitRemainingMs(ctx)
            if (waitMs == 0L) freeLeft = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(if (kind == AppLockKind.PIN) R.string.applock_title_pin else R.string.applock_title_pattern),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        val seconds = ((waitMs + 999) / 1000).toInt()
        val message = when {
            waitMs > 0L -> stringResource(R.string.applock_wait, "%d:%02d".format(seconds / 60, seconds % 60))
            tooShort -> stringResource(R.string.applock_too_short)
            frozenMsg -> stringResource(R.string.applock_frozen)
            freeLeft != null -> if (freeLeft == 0) stringResource(R.string.applock_wrong_last) else pluralStringResource(R.plurals.applock_wrong, freeLeft!!, freeLeft!!)
            else -> stringResource(R.string.applock_hint)
        }
        val isError = waitMs > 0L || tooShort || frozenMsg || freeLeft != null
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp)
        )
        Spacer(Modifier.height(16.dp))
        SecretEntry(
            kind = kind,
            enabled = waitMs == 0L,
            onSecret = { secret ->
                tooShort = false
                frozenMsg = false
                when (val r = AppLock.tryUnlock(ctx, secret)) {
                    UnlockResult.Unlocked -> onUnlocked()
                    is UnlockResult.Wrong -> { freeLeft = r.freeTriesLeft; waitMs = AppLock.waitRemainingMs(ctx) }
                    is UnlockResult.WaitFirst -> waitMs = r.waitMs
                }
            },
            onTooShort = { tooShort = true }
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = {
            gate.run {
                if (AppLock.remove(ctx)) onUnlocked() else frozenMsg = true
            }
        }) { Text(stringResource(R.string.applock_forgot)) }
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.applock_not_now)) }
    }
    RecoveryGateDialog(gate)
}
