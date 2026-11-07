package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.DechainerAccessibilityService.Companion.CapturedNode
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.viewmodels.RegionBlockerViewModel

@Composable
fun RegionBlockerScreen(viewModel: RegionBlockerViewModel = viewModel()) {
    val recoveryGate = rememberRecoveryGate()
    val capturedPackage = DechainerAccessibilityService.capturedPackage
    val capturedNodes = DechainerAccessibilityService.capturedNodes
    val counting = DechainerAccessibilityService.captureCountdown
    var pending by remember { mutableStateOf<CapturedNode?>(null) }
    var serviceOff by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.region_blocker),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                stringResource(R.string.region_blocker_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(
                enabled = !counting,
                onClick = { serviceOff = !viewModel.startCapture() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (counting) R.string.region_capturing else R.string.region_capture))
            }
            if (serviceOff) {
                Text(
                    stringResource(R.string.region_service_off),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (capturedPackage != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.region_pick_element, viewModel.appLabel(capturedPackage)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearCapture() }) {
                        Text(stringResource(R.string.region_discard_capture))
                    }
                }
                if (capturedNodes.isEmpty()) Text(stringResource(R.string.region_nothing_found))
            }
            items(capturedNodes) { node ->
                ListItem(
                    headlineContent = {
                        Text(
                            node.viewId?.substringAfter('/') ?: node.className.substringAfterLast('.'),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(node.text, "${node.width}×${node.height}px").joinToString(" • "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .clickable { pending = node }
                        .padding(start = (node.depth.coerceAtMost(8) * 6).dp)
                )
                HorizontalDivider()
            }
        }

        item {
            Text(
                stringResource(R.string.region_rules),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            if (viewModel.rules.isEmpty()) Text(stringResource(R.string.region_no_rules))
        }
        items(viewModel.rules, key = { it.id }) { rule ->
            ListItem(
                headlineContent = { Text(rule.label.ifBlank { rule.viewId ?: rule.text.orEmpty() }) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            viewModel.appLabel(rule.packageName),
                            rule.activityClass?.substringAfterLast('.')
                        ).joinToString(" • ")
                    )
                },
                trailingContent = {
                    IconButton(onClick = { recoveryGate.run { viewModel.removeRule(rule) } }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                }
            )
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    pending?.let { node ->
        var matchText by remember(node) { mutableStateOf(false) }
        var extendDown by remember(node) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.region_block_this)) },
            text = {
                Column {
                    Text(node.viewId ?: node.text.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { extendDown = !extendDown }
                            .padding(top = 12.dp)
                    ) {
                        Checkbox(checked = extendDown, onCheckedChange = { extendDown = it })
                        Text(
                            stringResource(R.string.region_extend_down),
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    if (node.viewId != null && node.text != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { matchText = !matchText }
                                .padding(top = 12.dp)
                        ) {
                            Checkbox(checked = matchText, onCheckedChange = { matchText = it })
                            Text(
                                stringResource(R.string.region_match_text, node.text),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    recoveryGate.change(loosening = false) { viewModel.addRule(node, matchText, extendDown) }
                    pending = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    RecoveryGateDialog(recoveryGate)
}
