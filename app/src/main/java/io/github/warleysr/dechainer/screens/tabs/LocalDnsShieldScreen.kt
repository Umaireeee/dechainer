package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.vpn.LocalDnsConfig
import io.github.warleysr.dechainer.vpn.LocalDnsShieldManager

@Composable
fun LocalDnsShieldScreen() {
    val context = LocalContext.current
    val gate = rememberTimelockGate()
    val status by LocalDnsShieldManager.status.collectAsState()
    var showAddDomainDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LocalDnsShieldManager.refreshState(context)
    }

    val cfg = status.config

    fun updateConfig(next: LocalDnsConfig, title: String, description: String) {
        val loosening = next.isLooserThan(cfg)
        gate.change(
            loosening = loosening,
            title = title,
            description = description,
            dedupeKey = "local_dns_shield",
            pendingAction = PendingAction.UpdateLocalDnsShield(next.toJsonString()),
            onTighten = { LocalDnsShieldManager.applyConfig(context, next) }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (cfg.enabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Shield, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dns_shield_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.dns_shield_card_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Badge(
                            containerColor = if (status.isRunning) Color(26, 163, 63)
                            else MaterialTheme.colorScheme.outline,
                            contentColor = Color.White
                        ) {
                            Text(
                                stringResource(
                                    if (status.isRunning) R.string.active
                                    else R.string.dns_shield_inactive
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.dns_shield_blocked_counter, status.blockedCount),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (status.upstreamServers.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.dns_shield_upstreams,
                                status.upstreamServers.joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.dns_shield_enable_title)) },
                supportingContent = { Text(stringResource(R.string.dns_shield_enable_desc)) },
                trailingContent = {
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { enable ->
                            updateConfig(
                                next = cfg.copy(enabled = enable),
                                title = context.getString(R.string.timelock_title_disable_dns_shield),
                                description = context.getString(R.string.dns_shield_title)
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
        }

        item {
            Text(
                stringResource(R.string.dns_shield_filters_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_adult_domains),
                subtitle = stringResource(R.string.dns_shield_adult_domains_desc),
                checked = cfg.blockAdultDomains,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(blockAdultDomains = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_adult_domains)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_adult_keywords),
                subtitle = stringResource(R.string.dns_shield_adult_keywords_desc),
                checked = cfg.blockAdultKeywords,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(blockAdultKeywords = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_adult_keywords)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_safesearch),
                subtitle = stringResource(R.string.dns_shield_safesearch_desc),
                checked = cfg.forceSafeSearch,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(forceSafeSearch = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_safesearch)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_youtube_restricted),
                subtitle = stringResource(R.string.dns_shield_youtube_restricted_desc),
                checked = cfg.forceYouTubeRestricted,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(forceYouTubeRestricted = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_youtube_restricted)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_block_doh),
                subtitle = stringResource(R.string.dns_shield_block_doh_desc),
                checked = cfg.blockDoHBypass,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(blockDoHBypass = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_block_doh)
                    )
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.dns_shield_block_tiktok),
                subtitle = stringResource(R.string.dns_shield_block_tiktok_desc),
                checked = cfg.blockTikTok,
                onCheckedChange = { v ->
                    updateConfig(
                        cfg.copy(blockTikTok = v),
                        context.getString(R.string.timelock_title_loosen_dns_shield),
                        context.getString(R.string.dns_shield_block_tiktok)
                    )
                }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.dns_shield_custom_domains),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = { showAddDomainDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add))
                }
            }
        }

        if (cfg.customBlockedDomains.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dns_shield_no_custom_domains),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(cfg.customBlockedDomains.sorted(), key = { "custom_$it" }) { domain ->
                ListItem(
                    headlineContent = { Text(domain) },
                    trailingContent = {
                        IconButton(onClick = {
                            updateConfig(
                                cfg.copy(customBlockedDomains = cfg.customBlockedDomains - domain),
                                context.getString(R.string.timelock_title_unblock_domain, domain),
                                context.getString(R.string.dns_shield_custom_domains)
                            )
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove))
                        }
                    }
                )
                HorizontalDivider()
            }
        }

        if (status.recentBlocked.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.dns_shield_recent_blocked),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            items(status.recentBlocked.take(10)) { entry ->
                ListItem(
                    headlineContent = { Text(entry.domain, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(entry.reason, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    if (showAddDomainDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDomainDialog = false },
            title = { Text(stringResource(R.string.dns_shield_add_domain_title)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.dns_shield_add_domain_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = LocalDnsConfig.normalizeDomain(input)
                    if (normalized.contains('.') && normalized.length >= 3) {
                        updateConfig(
                            cfg.copy(customBlockedDomains = cfg.customBlockedDomains + normalized),
                            context.getString(R.string.dns_shield_add_domain_title),
                            normalized
                        )
                    }
                    showAddDomainDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDomainDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    TimelockGateDialog(gate)
}

@Composable
internal fun ShieldToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
    HorizontalDivider()
}
