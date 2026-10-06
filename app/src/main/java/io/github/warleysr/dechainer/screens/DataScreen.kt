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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
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
 * the rules allow (behind the recovery code), and the backup file.
 */
@Composable
fun DataScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val zone = TrustedClock.zone()
    val tools = remember { DataTools(ctx, zone) { TrustedClock.now(ctx) } }
    val gate = rememberRecoveryGate()
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }
    fun at(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    val urges = remember(version) { Store.urgeEntries(ctx).all().asReversed() }
    val sessions = remember(version) { Store.focus(ctx).sessions().asReversed() }
    val reports = remember(version) { Store.reports(ctx).all().filter { !it.deleted } }
    val periodReports = remember(version) {
        io.github.warleysr.dechainer.report.PeriodKind.entries.flatMap { Store.periodReports(ctx).all(it) }.filter { !it.deleted }
    }
    val goalDays = remember(version) { Store.days(ctx).datesWithGoals().asReversed() }

    val savedMsg = stringResource(R.string.data_exported)
    val saveFailedMsg = stringResource(R.string.data_export_failed)
    val deletedMsg = stringResource(R.string.data_deleted)
    val notAllowedMsg = stringResource(R.string.data_not_allowed)
    val importFailedMsg = stringResource(R.string.data_import_failed)

    fun report(r: DeleteResult) {
        message = when (r) {
            DeleteResult.DELETED -> deletedMsg
            DeleteResult.NOT_ALLOWED -> notAllowedMsg
            DeleteResult.MISSING -> null
        }
        version++
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(tools.export().toByteArray(Charsets.UTF_8)) }
            io.github.warleysr.dechainer.report.BackupReminder.markDone(ctx)
        }.fold({ savedMsg }, { saveFailedMsg })
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            val result = text?.let { tools.import(it) }
            message = when {
                result == null || !result.ok -> importFailedMsg
                else -> resources.getString(R.string.data_imported, result.added, result.skipped)
            }
            version++
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }

        item { Text(stringResource(R.string.data_export_section), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.data_export_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ exportLauncher.launch("dechainer-backup-" + java.time.LocalDate.now() + ".json") }) { Text(stringResource(R.string.data_export)) }
                OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(stringResource(R.string.data_import))
                }
            }
        }

        item { Text(stringResource(R.string.data_entries), style = MaterialTheme.typography.titleLarge) }
        if (urges.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(urges, key = { "u${it.id}" }) { e ->
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.data_urge_row, at(e.createdAt),
                    stringResource(if (e.kind == UrgeKind.SLIP) R.string.data_kind_slip else R.string.data_kind_urge),
                    stringResource(statusLabel(e.status))))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ report(tools.deleteUrge(e.id)) }) { Text(stringResource(R.string.data_delete)) }
                    if (e.status == UrgeStatus.DONE && e.deepDive != null)
                        TextButton({ report(tools.deleteDeepDive(e.id)) }) { Text(stringResource(R.string.data_delete_deep_dive)) }
                }
            }
        }

        item { Text(stringResource(R.string.data_sessions), style = MaterialTheme.typography.titleLarge) }
        if (sessions.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(sessions, key = { "s${it.id}" }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_session_row, at(s.startedAt), s.focusedMinutes, s.purpose.ifBlank { "-" }), Modifier.weight(1f))
                TextButton({ report(tools.deleteSession(s.id)) }) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_reports), style = MaterialTheme.typography.titleLarge) }
        if (reports.isEmpty() && periodReports.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(reports, key = { "r${it.id}" }) { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(at(r.periodStart), Modifier.weight(1f))
                TextButton({ report(tools.deleteReport(r.id)) }) { Text(stringResource(R.string.data_delete)) }
            }
        }
        items(periodReports, key = { "p${it.id}" }) { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(io.github.warleysr.dechainer.report.PeriodInputs.label(r.kind, r.key), Modifier.weight(1f))
                TextButton({ report(tools.deletePeriodReport(r.id)) }) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_goals), style = MaterialTheme.typography.titleLarge) }
        if (goalDays.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(goalDays, key = { "g$it" }) { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_goals_row, d.toString()), Modifier.weight(1f))
                TextButton({ report(tools.deleteGoals(d)) }, enabled = tools.canDeleteGoals(d)) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_wipe_section), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.data_wipe_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedButton({ gate.run { confirmWipe = true } }) { Text(stringResource(R.string.data_wipe)) }
        }
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
                    message = resources.getString(R.string.data_wipe_done, r.removed, r.kept)
                    version++
                }) { Text(stringResource(R.string.data_wipe)) }
            },
            dismissButton = { TextButton({ confirmWipe = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    RecoveryGateDialog(gate)
}

/** The stored status, said in plain words (blueprint 12: nothing internal is shown to the owner). */
private fun statusLabel(status: UrgeStatus): Int = when (status) {
    UrgeStatus.LOCKED -> R.string.data_status_locked
    UrgeStatus.WRITING -> R.string.data_status_writing
    UrgeStatus.QUESTIONS -> R.string.data_status_questions
    UrgeStatus.PENDING_DEEPDIVE -> R.string.data_status_pending
    UrgeStatus.DONE -> R.string.data_status_done
    UrgeStatus.SKIPPED -> R.string.data_status_skipped
}
