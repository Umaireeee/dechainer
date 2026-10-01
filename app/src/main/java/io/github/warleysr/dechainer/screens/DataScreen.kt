package io.github.warleysr.dechainer.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.SettingsFreeze
import io.github.warleysr.dechainer.report.DataTools
import io.github.warleysr.dechainer.report.DeleteResult
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Your data (blueprint 6.6): delete single entries, sessions, deep dives and reports, wipe everything
 * the rules allow (behind the recovery code), and the backup file. Every write is refused on a
 * punishment day by [DataTools] itself; this screen only says so.
 */
@Composable
fun DataScreen(onOpenEntry: (Long) -> Unit = {}, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val zone = TrustedClock.zone()
    val tools = remember { DataTools(ctx, zone) { TrustedClock.now(ctx) } }
    val gate = rememberRecoveryGate()
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    // Every single delete asks first: two "Delete" buttons side by side were one mis-tap from losing a deep dive.
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> DeleteResult>?>(null) }
    val frozen = SettingsFreeze.isFrozen(ctx)
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }
    fun at(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    val urges = remember(version) { Store.urgeEntries(ctx).all().asReversed() }
    val sessions = remember(version) { Store.focus(ctx).sessions().asReversed() }
    val reports = remember(version) { Store.reports(ctx).all().filter { !it.deleted } }
    val goalDays = remember(version) { Store.days(ctx).datesWithGoals().asReversed() }

    val savedMsg = stringResource(R.string.data_exported)
    val saveFailedMsg = stringResource(R.string.data_export_failed)
    val deletedMsg = stringResource(R.string.data_deleted)
    val notAllowedMsg = stringResource(R.string.data_not_allowed)
    val frozenMsg = stringResource(R.string.data_frozen)
    val importFailedMsg = stringResource(R.string.data_import_failed)

    fun report(r: DeleteResult) {
        message = when (r) {
            DeleteResult.DELETED -> deletedMsg
            DeleteResult.FROZEN -> frozenMsg
            DeleteResult.NOT_ALLOWED -> notAllowedMsg
            DeleteResult.MISSING -> null
        }
        version++
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(tools.export().toByteArray(Charsets.UTF_8)) }
        }.fold({ savedMsg }, { saveFailedMsg })
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            val result = text?.let { tools.import(it) }
            message = when {
                SettingsFreeze.isFrozen(ctx) -> frozenMsg
                result == null || !result.ok -> importFailedMsg
                else -> ctx.getString(R.string.data_imported, result.added, result.skipped)
            }
            version++
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (frozen) item { Text(stringResource(R.string.data_frozen), color = MaterialTheme.colorScheme.error) }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }

        item { Text(stringResource(R.string.data_export_section), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.data_export_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ exportLauncher.launch("dechainer-backup.json") }) { Text(stringResource(R.string.data_export)) }
                OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = !frozen) {
                    Text(stringResource(R.string.data_import))
                }
            }
        }

        item { Text(stringResource(R.string.data_entries), style = MaterialTheme.typography.titleLarge) }
        if (urges.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(urges, key = { "u${it.id}" }) { e ->
            Column(Modifier.fillMaxWidth()) {
                // The row opens the entry: its deep dive and answers can be read again from here.
                EntryRow(
                    title = stringResource(R.string.data_urge_row, at(e.createdAt),
                        stringResource(if (e.kind == UrgeKind.SLIP) R.string.data_kind_slip else R.string.data_kind_urge),
                        stringResource(entryStatusLabel(e.status, e.deepDive != null))),
                    subtitle = stringResource(R.string.data_open_hint),
                    onClick = { onOpenEntry(e.id) }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ pendingDelete = at(e.createdAt) to { tools.deleteUrge(e.id) } }, enabled = !frozen) { Text(stringResource(R.string.data_delete)) }
                    if (e.status == UrgeStatus.DONE && e.deepDive != null)
                        TextButton({ pendingDelete = at(e.createdAt) to { tools.deleteDeepDive(e.id) } }, enabled = !frozen) { Text(stringResource(R.string.data_delete_deep_dive)) }
                }
            }
        }

        item { Text(stringResource(R.string.data_sessions), style = MaterialTheme.typography.titleLarge) }
        if (sessions.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(sessions, key = { "s${it.id}" }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_session_row, at(s.startedAt), s.focusedMinutes, s.purpose.ifBlank { "-" }), Modifier.weight(1f))
                TextButton({ pendingDelete = at(s.startedAt) to { tools.deleteSession(s.id) } }, enabled = !frozen) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_reports), style = MaterialTheme.typography.titleLarge) }
        if (reports.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(reports, key = { "r${it.id}" }) { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(at(r.periodStart), Modifier.weight(1f))
                TextButton({ pendingDelete = at(r.periodStart) to { tools.deleteReport(r.id) } }, enabled = !frozen) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_goals), style = MaterialTheme.typography.titleLarge) }
        if (goalDays.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(goalDays, key = { "g$it" }) { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_goals_row, d.toString()), Modifier.weight(1f))
                TextButton({ pendingDelete = d.toString() to { tools.deleteGoals(d) } }, enabled = !frozen && tools.canDeleteGoals(d)) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_wipe_section), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.data_wipe_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedButton({ gate.run { confirmWipe = true } }, enabled = !frozen) { Text(stringResource(R.string.data_wipe)) }
        }
    }

    pendingDelete?.let { (what, action) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.data_delete_one_title)) },
            text = { Text(stringResource(R.string.data_delete_one_body, what)) },
            confirmButton = { TextButton({ pendingDelete = null; report(action()) }) { Text(stringResource(R.string.data_delete)) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.data_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.data_wipe_confirm)) },
            confirmButton = {
                TextButton({
                    confirmWipe = false
                    val r = tools.wipeAll()
                    message = if (r.frozen) frozenMsg else ctx.getString(R.string.data_wipe_done, r.removed, r.kept)
                    version++
                }) { Text(stringResource(R.string.data_wipe)) }
            },
            dismissButton = { TextButton({ confirmWipe = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    RecoveryGateDialog(gate)
}

/** A plain word for where an entry is, instead of the stored status name. */
private fun entryStatusLabel(status: UrgeStatus, hasDeepDive: Boolean): Int = when {
    hasDeepDive -> R.string.journal_label_deep_dive
    status == UrgeStatus.PENDING_DEEPDIVE -> R.string.journal_label_waiting
    status == UrgeStatus.SKIPPED -> R.string.journal_label_skipped
    else -> R.string.journal_label_unfinished
}
