package io.github.warleysr.dechainer.screens.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.CountdownStatus
import io.github.warleysr.dechainer.security.EnqueueResult
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.security.PendingUnlockRequest
import io.github.warleysr.dechainer.security.TimelockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private data class QueuedPrompt(
    val dedupeKey: String,
    val title: String,
    val description: String,
    val action: PendingAction,
    val existing: PendingUnlockRequest?,
    val onCancel: (() -> Unit)?
)

/**
 * Gates loosening changes behind a 7-day timelock queue while letting tightening changes apply
 * immediately after a 5-second undo window ([PendingChange]).
 */
class TimelockGate(
    context: Context,
    private val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val showToastOnQueue: Boolean = false
) {
    private val appContext = context.applicationContext
    private var prompt by mutableStateOf<QueuedPrompt?>(null)

    val isDialogVisible: Boolean get() = prompt != null

    fun change(
        loosening: Boolean,
        title: String,
        description: String,
        dedupeKey: String,
        pendingAction: PendingAction,
        onCancel: (() -> Unit)? = null,
        onTighten: () -> Unit
    ) {
        if (!loosening) {
            PendingChange.schedule(
                appContext.getString(R.string.restriction_pending),
                appContext.getString(R.string.undo),
                onCancel,
                onTighten
            )
        } else {
            requestUnlock(title, description, dedupeKey, pendingAction, onCancel)
        }
    }

    fun requestUnlock(
        title: String,
        description: String,
        dedupeKey: String,
        pendingAction: PendingAction,
        onCancel: (() -> Unit)? = null
    ) {
        val existing = TimelockManager.findByDedupeKey(appContext, dedupeKey)
        prompt = QueuedPrompt(
            dedupeKey = dedupeKey,
            title = title,
            description = description,
            action = pendingAction,
            existing = existing,
            onCancel = onCancel
        )
    }

    fun confirmQueue(): EnqueueResult? {
        val current = prompt ?: return null
        prompt = null
        val result = TimelockManager.enqueue(
            context = appContext,
            dedupeKey = current.dedupeKey,
            title = current.title,
            description = current.description,
            action = current.action
        )
        current.onCancel?.invoke()
        val msg = when (result) {
            is EnqueueResult.Queued -> appContext.getString(R.string.timelock_queued_snackbar)
            is EnqueueResult.AlreadyPending -> {
                val rem = when (val st = result.existing.status) {
                    is CountdownStatus.Waiting -> TimelockManager.formatRemaining(st.remainingMs)
                    is CountdownStatus.Ready -> appContext.getString(R.string.timelock_ready_now)
                    CountdownStatus.Expired -> ""
                }
                appContext.getString(R.string.timelock_already_pending_snackbar, rem)
            }
        }
        if (showToastOnQueue) {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        } else {
            uiScope.launch {
                PendingChange.snackbarHost.currentSnackbarData?.dismiss()
                PendingChange.snackbarHost.showSnackbar(msg, duration = SnackbarDuration.Short)
            }
        }
        return result
    }

    fun cancelExistingAndDismiss() {
        val current = prompt ?: return
        prompt = null
        current.existing?.let { TimelockManager.cancel(appContext, it.id) }
        current.onCancel?.invoke()
    }

    fun dismiss() {
        val current = prompt ?: return
        prompt = null
        current.onCancel?.invoke()
    }

    internal fun currentPromptTitle(): String? = prompt?.title
    internal fun currentPromptExisting(): PendingUnlockRequest? = prompt?.existing
}

@Composable
fun rememberTimelockGate(showToastOnQueue: Boolean = false): TimelockGate {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(showToastOnQueue) { TimelockGate(context, scope, showToastOnQueue) }
}

@Composable
fun TimelockGateDialog(gate: TimelockGate) {
    val title = gate.currentPromptTitle() ?: return
    val existing = gate.currentPromptExisting()
    if (existing != null) {
        val statusText = when (val status = existing.status) {
            is CountdownStatus.Waiting -> stringResource(
                R.string.timelock_existing_waiting,
                TimelockManager.formatRemaining(status.remainingMs)
            )
            is CountdownStatus.Ready -> stringResource(
                R.string.timelock_existing_ready,
                TimelockManager.formatRemaining(status.claimRemainingMs)
            )
            CountdownStatus.Expired -> stringResource(R.string.timelock_ready_now)
        }
        AlertDialog(
            onDismissRequest = { gate.dismiss() },
            title = { Text(stringResource(R.string.timelock_already_pending_title)) },
            text = {
                Column {
                    Text(existing.title, fontWeight = FontWeight.Bold)
                    if (existing.description.isNotBlank()) {
                        Text(existing.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { gate.dismiss() }) {
                    Text(stringResource(R.string.close))
                }
            },
            dismissButton = {
                TextButton(onClick = { gate.cancelExistingAndDismiss() }) {
                    Text(
                        stringResource(R.string.timelock_cancel_request),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = { gate.dismiss() },
            title = { Text(stringResource(R.string.timelock_confirm_title)) },
            text = {
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.timelock_confirm_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { gate.confirmQueue() }) {
                    Text(stringResource(R.string.timelock_queue_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { gate.dismiss() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
