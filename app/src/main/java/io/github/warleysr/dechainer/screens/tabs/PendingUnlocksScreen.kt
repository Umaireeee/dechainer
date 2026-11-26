package io.github.warleysr.dechainer.screens.tabs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.TetrisUnlock
import io.github.warleysr.dechainer.security.CountdownStatus
import io.github.warleysr.dechainer.security.PendingUnlockRequest
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.RestrictionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PendingUnlocksScreen(
    appsViewModel: AppsViewModel = viewModel(),
    restrictionsViewModel: RestrictionsViewModel = viewModel(),
    deviceOwnerViewModel: DeviceOwnerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requests by TimelockManager.requests.collectAsState()
    var challengingRequest by remember { mutableStateOf<PendingUnlockRequest?>(null) }
    var applyingIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(requests.isNotEmpty()) {
        if (requests.isEmpty()) return@LaunchedEffect
        while (true) {
            TimelockManager.syncAll(context)
            delay(30_000L)
        }
    }

    fun applyRequest(req: PendingUnlockRequest) {
        if (req.id in applyingIds) return
        applyingIds = applyingIds + req.id
        scope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    TimelockManager.applyIfReady(context, req.id)
                }
                if (applied) {
                    appsViewModel.loadApps()
                    appsViewModel.loadGroups()
                    restrictionsViewModel.loadRestrictions()
                    deviceOwnerViewModel.refreshStatus()
                    Toast.makeText(context, R.string.timelock_applied_toast, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.timelock_apply_failed_toast, Toast.LENGTH_SHORT).show()
                }
            } finally {
                applyingIds = applyingIds - req.id
            }
        }
    }

    val ready = requests.filter { it.status is CountdownStatus.Ready }
    val waiting = requests.filter { it.status is CountdownStatus.Waiting }

    if (requests.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.LockClock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.timelock_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.timelock_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ready.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.timelock_section_ready),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(ready, key = { it.id }) { req ->
                    val status = req.status as CountdownStatus.Ready
                    PendingUnlockCard(
                        request = req,
                        isReady = true,
                        statusLabel = stringResource(
                            R.string.timelock_claim_window_left,
                            TimelockManager.formatRemaining(status.claimRemainingMs)
                        ),
                        progress = 1f,
                        isApplying = req.id in applyingIds,
                        onApply = {
                            if (SecurityManager.isTetrisUnlockEnabled(context)) {
                                challengingRequest = req
                            } else {
                                applyRequest(req)
                            }
                        },
                        onCancel = { TimelockManager.cancel(context, req.id) }
                    )
                }
            }

            if (waiting.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.timelock_section_waiting),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (ready.isNotEmpty()) 8.dp else 0.dp)
                    )
                }
                items(waiting, key = { it.id }) { req ->
                    val status = req.status as CountdownStatus.Waiting
                    PendingUnlockCard(
                        request = req,
                        isReady = false,
                        statusLabel = stringResource(
                            R.string.timelock_wait_left,
                            TimelockManager.formatRemaining(status.remainingMs)
                        ),
                        progress = req.countdown.progressFraction(),
                        isApplying = false,
                        onApply = {},
                        onCancel = { TimelockManager.cancel(context, req.id) }
                    )
                }
            }
        }
    }

    challengingRequest?.let { req ->
        Dialog(
            onDismissRequest = { challengingRequest = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                TetrisUnlock(
                    onDone = {
                        challengingRequest = null
                        applyRequest(req)
                    },
                    onGiveUp = { challengingRequest = null }
                )
            }
        }
    }
}

@Composable
private fun PendingUnlockCard(
    request: PendingUnlockRequest,
    isReady: Boolean,
    statusLabel: String,
    progress: Float,
    isApplying: Boolean = false,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isReady) Icons.Outlined.CheckCircle else Icons.Outlined.HourglassEmpty,
                    contentDescription = null,
                    tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(request.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (request.description.isNotBlank()) {
                        Text(request.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!isReady) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.timelock_cancel_request))
                }
                if (isReady) {
                    Button(enabled = !isApplying, onClick = onApply) {
                        Text(stringResource(R.string.apply))
                    }
                }
            }
        }
    }
}
