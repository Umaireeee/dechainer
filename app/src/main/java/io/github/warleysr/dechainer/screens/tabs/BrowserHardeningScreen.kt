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
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.BrowserHardeningConfig
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.DetectedBrowser
import io.github.warleysr.dechainer.screens.common.PendingChange
import io.github.warleysr.dechainer.screens.common.TimelockGateDialog
import io.github.warleysr.dechainer.screens.common.rememberTimelockGate
import io.github.warleysr.dechainer.security.PendingAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowserHardeningScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberTimelockGate()
    val cfg by BrowserHardeningManager.config.collectAsState()
    var browsers by remember { mutableStateOf<List<DetectedBrowser>>(emptyList()) }
    var showAddUrlDialog by remember { mutableStateOf(false) }

    fun refreshBrowsers() {
        scope.launch {
            BrowserHardeningManager.getConfig(context)
            browsers = withContext(Dispatchers.IO) { BrowserHardeningManager.detectBrowsers(context) }
        }
    }

    LaunchedEffect(Unit) {
        refreshBrowsers()
    }

    val managedBrowsers = browsers.filter { it.isManaged }
    val activeUnmanaged = browsers.filter { !it.isManaged && !it.isSuspended && !it.isHidden }
    val hasBrave = managedBrowsers.any { it.isBrave }

    val undoMsg = stringResource(R.string.restriction_pending)
    val undoLabel = stringResource(R.string.undo)

    fun updateConfig(title: String, description: String, transform: (BrowserHardeningConfig) -> BrowserHardeningConfig) {
        val prev = BrowserHardeningManager.getConfig(context)
        val next = transform(prev)
        if (next == prev) return
        if (next.isLooserThan(prev)) {
            gate.requestUnlock(
                title = title,
                description = description,
                dedupeKey = "browser_hardening",
                pendingAction = PendingAction.UpdateBrowserHardening(next.toJsonString())
            )
        } else {
            BrowserHardeningManager.applyConfig(context, next)
            refreshBrowsers()
            if (prev.enabled || next.enabled) {
                PendingChange.offerUndo(undoMsg, undoLabel) {
                    BrowserHardeningManager.applyConfig(context, prev)
                    refreshBrowsers()
                }
            }
        }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.browser_hardening_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.browser_hardening_card_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val names = managedBrowsers.joinToString(", ") { b ->
                        if (cfg.enabled || b.appliedPolicyCount > 0) "${b.label} (${b.appliedPolicyCount})"
                        else b.label
                    }
                    Text(
                        if (names.isNotEmpty()) stringResource(R.string.browser_hardening_detected, names)
                        else stringResource(R.string.browser_hardening_none_detected),
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (managedBrowsers.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.browser_hardening_restart_note),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (cfg.enabled) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                BrowserHardeningManager.reapplyNow(context)
                                refreshBrowsers()
                            }) {
                                Text(stringResource(R.string.browser_hardening_reapply_btn))
                            }
                        }
                    }
                }
            }
        }

        if (activeUnmanaged.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.browser_unmanaged_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(
                                R.string.browser_unmanaged_desc,
                                activeUnmanaged.joinToString(", ") { it.label }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            PendingChange.schedule(
                                message = context.getString(R.string.restriction_pending),
                                undoLabel = context.getString(R.string.undo),
                                action = {
                                    scope.launch(Dispatchers.IO) {
                                        activeUnmanaged.forEach { AppRepository.setAppSuspended(it.packageName, true) }
                                        refreshBrowsers()
                                    }
                                }
                            )
                        }) {
                            Text(stringResource(R.string.browser_unmanaged_suspend_btn))
                        }
                    }
                }
            }
        }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_hardening_enable_title)) },
                supportingContent = { Text(stringResource(R.string.browser_hardening_enable_desc)) },
                trailingContent = {
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { enable ->
                            updateConfig(
                                context.getString(R.string.timelock_title_disable_browser_hardening),
                                context.getString(R.string.browser_hardening_title)
                            ) { it.copy(enabled = enable) }
                        }
                    )
                }
            )
            HorizontalDivider()
        }

        item {
            Text(
                stringResource(R.string.browser_section_privacy_bypass),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ShieldToggleRow(
                title = stringResource(R.string.browser_disable_incognito),
                subtitle = stringResource(R.string.browser_disable_incognito_desc),
                checked = cfg.disableIncognito,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_disable_incognito)
                    ) { it.copy(disableIncognito = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_disable_doh),
                subtitle = stringResource(R.string.browser_disable_doh_desc),
                checked = cfg.disableDoH,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_disable_doh)
                    ) { it.copy(disableDoH = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_force_safesearch),
                subtitle = stringResource(R.string.browser_force_safesearch_desc),
                checked = cfg.forceSafeSearch,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_force_safesearch)
                    ) { it.copy(forceSafeSearch = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_force_youtube_restricted),
                subtitle = stringResource(R.string.browser_force_youtube_restricted_desc),
                checked = cfg.forceYouTubeRestricted,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_force_youtube_restricted)
                    ) { it.copy(forceYouTubeRestricted = v) }
                }
            )
            if (hasBrave) {
                ShieldToggleRow(
                    title = stringResource(R.string.browser_disable_brave_tor),
                    subtitle = stringResource(R.string.browser_disable_brave_tor_desc),
                    checked = cfg.disableBraveTor,
                    onCheckedChange = { v ->
                        updateConfig(
                            context.getString(R.string.timelock_title_loosen_browser),
                            context.getString(R.string.browser_disable_brave_tor)
                        ) { it.copy(disableBraveTor = v) }
                    }
                )
            }
        }

        item {
            Text(
                stringResource(R.string.browser_section_short_form),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ShieldToggleRow(
                title = stringResource(R.string.browser_block_yt_shorts),
                subtitle = stringResource(R.string.browser_block_yt_shorts_desc),
                checked = cfg.blockYouTubeShorts,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_block_yt_shorts)
                    ) { it.copy(blockYouTubeShorts = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_block_ig_reels),
                subtitle = stringResource(R.string.browser_block_ig_reels_desc),
                checked = cfg.blockInstagramReels,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_block_ig_reels)
                    ) { it.copy(blockInstagramReels = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_block_fb_reels),
                subtitle = stringResource(R.string.browser_block_fb_reels_desc),
                checked = cfg.blockFacebookReels,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_block_fb_reels)
                    ) { it.copy(blockFacebookReels = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_block_tiktok),
                subtitle = stringResource(R.string.browser_block_tiktok_desc),
                checked = cfg.blockTikTok,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_block_tiktok)
                    ) { it.copy(blockTikTok = v) }
                }
            )
            ShieldToggleRow(
                title = stringResource(R.string.browser_block_adult_sites),
                subtitle = stringResource(R.string.browser_block_adult_sites_desc),
                checked = cfg.blockAdultSites,
                onCheckedChange = { v ->
                    updateConfig(
                        context.getString(R.string.timelock_title_loosen_browser),
                        context.getString(R.string.browser_block_adult_sites)
                    ) { it.copy(blockAdultSites = v) }
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
                    stringResource(R.string.browser_custom_urls),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = { showAddUrlDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add))
                }
            }
        }

        if (cfg.customBlockedUrls.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.browser_no_custom_urls),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(cfg.customBlockedUrls.sorted(), key = { "url_$it" }) { urlRule ->
                ListItem(
                    headlineContent = { Text(urlRule) },
                    trailingContent = {
                        IconButton(onClick = {
                            updateConfig(
                                context.getString(R.string.timelock_title_unblock_url, urlRule),
                                context.getString(R.string.browser_custom_urls)
                            ) { it.copy(customBlockedUrls = it.customBlockedUrls - urlRule) }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove))
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (showAddUrlDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddUrlDialog = false },
            title = { Text(stringResource(R.string.browser_add_url_title)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.browser_add_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = BrowserHardeningConfig.normalizeRule(input)
                    if (normalized.isNotEmpty() && normalized.contains('.')) {
                        updateConfig(
                            context.getString(R.string.browser_add_url_title),
                            normalized
                        ) { it.copy(customBlockedUrls = it.customBlockedUrls + normalized) }
                    }
                    showAddUrlDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddUrlDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    TimelockGateDialog(gate)
}
