package io.github.warleysr.dechainer.screens.apps

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.content.Context
import io.github.warleysr.dechainer.ui.theme.Motion
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import kotlinx.coroutines.launch

/**
 * Suspend any app with one switch. Suspending is free; lifting a suspension asks for the recovery
 * code, and an app held by an open schedule or brick can't be lifted until it ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(viewModel: AppsViewModel = viewModel()) {
    val gate = rememberRecoveryGate()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val heldMsg = stringResource(R.string.apps_held_by)
    var limitFor by remember { mutableStateOf<AppItem?>(null) }
    // Back from the Usage access screen (or any time the screen returns): re-read the limits.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshLimits()
                // The list was only read once, when the tab was first made: a schedule that opened or
                // closed since, or an app installed since, was missing until the app restarted. This
                // is instant when nothing changed (the repository's copy is reused).
                viewModel.load()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Suspending is instant; lifting asks the engine (in the background) whether a schedule or
    // focus session still holds the app, then the recovery code.
    fun toggle(app: AppItem, suspend: Boolean) {
        if (suspend) viewModel.setSuspended(app.packageName, true)
        else viewModel.requestUnsuspend(
            app.packageName,
            onHeld = { holder -> scope.launch { snackbar.showSnackbar(heldMsg.format(holder)) } },
            onFree = { gate.run { viewModel.setSuspended(app.packageName, false) } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = { viewModel.query = it },
                placeholder = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilterChip(
                    selected = viewModel.showSystem,
                    onClick = { viewModel.showSystem = !viewModel.showSystem },
                    label = { Text(stringResource(R.string.apps_show_system)) }
                )
            }
            if (viewModel.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val visible = viewModel.visible
                val suspended = visible.filter { it.isSuspended }
                val others = visible.filterNot { it.isSuspended }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    // Suspended apps on top, everything else below. Both sections key rows by package
                    // name, and keys are unique across the whole list, so a switch is a *move* of the
                    // same row: animateItem slides it into the other section.
                    if (suspended.isNotEmpty()) {
                        stickyHeader(key = "h-suspended") {
                            StickyLabel(stringResource(R.string.apps_section_suspended, suspended.size))
                        }
                        items(suspended, key = { it.packageName }) { app ->
                            Box(Modifier.animateItem(placementSpec = tween(Motion.LIST_MS))) {
                                AppRow(
                                    app,
                                    limit = viewModel.limits[app.packageName] ?: 0,
                                    used = viewModel.usedMinutes[app.packageName],
                                    onOpenLimit = { limitFor = app }
                                ) { on -> toggle(app, on) }
                            }
                        }
                    }
                    if (others.isNotEmpty()) {
                        stickyHeader(key = "h-all") { StickyLabel(stringResource(R.string.apps_section_all)) }
                    }
                    items(others, key = { it.packageName }) { app ->
                        Box(Modifier.animateItem(placementSpec = tween(Motion.LIST_MS))) {
                            AppRow(
                                    app,
                                    limit = viewModel.limits[app.packageName] ?: 0,
                                    used = viewModel.usedMinutes[app.packageName],
                                    onOpenLimit = { limitFor = app }
                                ) { on -> toggle(app, on) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    RecoveryGateDialog(gate)
    limitFor?.let { app ->
        LimitDialog(
            appName = app.name,
            current = viewModel.limits[app.packageName] ?: 0,
            usageAccess = viewModel.usageAccess,
            onDismiss = { limitFor = null },
            onSave = { minutes ->
                val old = viewModel.limits[app.packageName] ?: 0
                // Removing or raising a limit loosens your own rule: that takes the recovery code.
                val loosens = old > 0 && (minutes == 0 || minutes > old)
                limitFor = null
                if (loosens) gate.run { viewModel.setLimit(app.packageName, minutes) }
                else viewModel.setLimit(app.packageName, minutes)
            }
        )
    }
}

@Composable
private fun AppRow(app: AppItem, limit: Int, used: Int?, onOpenLimit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val icon = remember(app.packageName) {
        runCatching { app.icon.toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    ListItem(
        headlineContent = { Text(app.name) },
        supportingContent = {
            if (limit > 0) {
                val reached = used != null && used >= limit
                Text(
                    when {
                        reached -> stringResource(R.string.limit_reached_line, limit)
                        used != null -> stringResource(R.string.limit_used_line, limit, used)
                        else -> stringResource(R.string.limit_line, limit)
                    },
                    color = if (reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = { icon?.let { Image(it, null, Modifier.size(40.dp).clip(CircleShape)) } },
        trailingContent = { Switch(checked = app.isSuspended, onCheckedChange = onToggle) },
        // Tap the row for the daily limit; the switch suspends the app right now.
        modifier = Modifier.clickable(onClick = onOpenLimit),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    )
}

/** A section label that stays pinned while its section scrolls under it. */
@Composable
private fun StickyLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

/** The daily limit for one app: quick picks, fine-tuning, and the one-time Usage access prompt. */
@Composable
private fun LimitDialog(
    appName: String,
    current: Int,
    usageAccess: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    val context = LocalContext.current
    var minutes by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limit_title, appName)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.limit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 15, 30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = {
                                Text(if (m == 0) stringResource(R.string.limit_none) else stringResource(R.string.focus_minutes_short, m))
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.limit_custom), modifier = Modifier.weight(1f))
                    TextButton(onClick = { minutes = (minutes - 5).coerceAtLeast(0) }, enabled = minutes > 0) { Text("−") }
                    Text(if (minutes == 0) "–" else minutes.toString(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { minutes = (minutes + 5).coerceAtMost(720) }) { Text("+") }
                }
                if (!usageAccess) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.limit_needs_access),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { openUsageAccess(context) }) { Text(stringResource(R.string.limit_grant_access)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(minutes) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Opens Android's Usage access screen for this app (or the general list where that isn't supported). */
private fun openUsageAccess(context: Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(flags)
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(flags))
        } catch (_: Exception) { }
    }
}

