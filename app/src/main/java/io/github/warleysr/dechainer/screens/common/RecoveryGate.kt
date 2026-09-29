package io.github.warleysr.dechainer.screens.common

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.delay

/**
 * Gates a sensitive action behind the recovery-code dialog, unless a recovery session is already
 * active or no recovery code has been set yet (first run).
 *
 * With an unlock delay set, a correct code starts a countdown instead of unlocking at once. The
 * countdown shows here; closing it keeps it running in the background, and the next gated action
 * after it finishes simply goes through.
 */
class RecoveryGate(private val context: Context) {
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    private var pendingCancel: (() -> Unit)? = null

    var isWaiting by mutableStateOf(false)
        private set

    val isDialogVisible: Boolean get() = pendingAction != null

    /** Whether a recovery session is active — screens use this to enable/disable gated controls. */
    val isSessionActive: Boolean get() = SecurityManager.syncDelayedSession(context)

    /** [onCancel] runs if the user dismisses the dialog instead of confirming. */
    fun run(onCancel: (() -> Unit)? = null, action: () -> Unit) {
        val storedCode = SecurityManager.getRecoveryCode(context)
        if (storedCode == null || SecurityManager.syncDelayedSession(context)) {
            action()
            return
        }
        pendingAction = action
        pendingCancel = onCancel
        // A countdown already running: show it, rather than asking for the code again.
        isWaiting = SecurityManager.isWaitingForUnlock(context)
    }

    fun confirm(code: String): Boolean {
        val storedCode = SecurityManager.getRecoveryCode(context) ?: return true
        if (!SecurityManager.validateRecoveryCode(code, storedCode)) return false
        if (SecurityManager.beginUnlock(context)) {
            pendingAction?.invoke()
            clear()
        } else {
            isWaiting = true
        }
        return true
    }

    fun remainingWaitMs(): Long = SecurityManager.remainingUnlockWaitMs(context)

    /** Called by the countdown: runs the waiting action the moment the delay is over. */
    fun tick() {
        if (SecurityManager.syncDelayedSession(context)) {
            pendingAction?.invoke()
            clear()
        }
    }

    fun cancelWait() {
        SecurityManager.cancelUnlock(context)
        dismiss()
    }

    fun dismiss() {
        pendingCancel?.invoke()
        clear()
    }

    private fun clear() {
        pendingAction = null
        pendingCancel = null
        isWaiting = false
    }
}

@Composable
fun rememberRecoveryGate(): RecoveryGate {
    val context = LocalContext.current
    return remember { RecoveryGate(context) }
}

@Composable
fun RecoveryGateDialog(gate: RecoveryGate) {
    if (!gate.isDialogVisible) return
    if (gate.isWaiting) {
        UnlockWaitDialog(gate)
    } else {
        RecoveryConfirmDialog(
            onConfirm = { code -> gate.confirm(code) },
            onDismiss = { gate.dismiss() }
        )
    }
}

@Composable
private fun UnlockWaitDialog(gate: RecoveryGate) {
    var remaining by remember { mutableLongStateOf(gate.remainingWaitMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            remaining = gate.remainingWaitMs()
            if (remaining <= 0L) {
                gate.tick()
                break
            }
            delay(1000)
        }
    }
    AlertDialog(
        // Closing only hides the countdown; it keeps running.
        onDismissRequest = { gate.dismiss() },
        title = { Text(stringResource(R.string.unlock_wait_title)) },
        text = { Text(stringResource(R.string.unlock_wait_text, formatWait(remaining))) },
        confirmButton = {
            TextButton(onClick = { gate.dismiss() }) { Text(stringResource(R.string.unlock_wait_hide)) }
        },
        dismissButton = {
            TextButton(onClick = { gate.cancelWait() }) { Text(stringResource(R.string.unlock_wait_cancel)) }
        }
    )
}

private fun formatWait(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
