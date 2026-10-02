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
import io.github.warleysr.dechainer.report.DataTools
import io.github.warleysr.dechainer.report.DeleteResult
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.ui.theme.Eyebrow
import io.github.warleysr.dechainer.ui.theme.RowDivider
import io.github.warleysr.dechainer.ui.theme.Space
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
                else -> ctx.getString(R.string.data_imported, result.added, result.skipped)
            }
            version++
        }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.item)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        message?.let { m ->
            item {
                CalmCard(Modifier.fillMaxWidth(), highlighted = true) {
                    Text(m, Modifier.padding(Space.cardPadding), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Eyebrow(stringResource(R.string.data_export_section)) }
        item {
            CalmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.data_export_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ exportLauncher.launch("dechainer-backup-" + java.time.LocalDate.now() + ".json") }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.data_export))
                        }
                        OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.data_import))
                        }
                    }
                }
            }
        }

        section(R.string.data_entries, urges.isEmpty()) {
            urges.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                DataRow(
                    title = stringResource(if (e.kind == UrgeKind.SLIP) R.string.data_kind_slip else R.string.data_kind_urge),
                    subtitle = at(e.createdAt) + " · " + e.status.name.lowercase().replace('_', ' ')
                ) {
                    if (e.status == UrgeStatus.DONE && e.deepDive != null)
                        TextButton({ report(tools.deleteDeepDive(e.id)) }) { Text(stringResource(R.string.data_delete_deep_dive)) }
                    TextButton({ report(tools.deleteUrge(e.id)) }) { Text(stringResource(R.string.data_delete)) }
                }
            }
        }

        section(R.string.data_sessions, sessions.isEmpty()) {
            sessions.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                DataRow(s.purpose.ifBlank { "–" }, at(s.startedAt) + " · " + s.focusedMinutes + " min") {
                    TextButton({ report(tools.deleteSession(s.id)) }) { Text(stringResource(R.string.data_delete)) }
                }
            }
        }

        section(R.string.data_reports, reports.isEmpty() && periodReports.isEmpty()) {
            reports.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                DataRow(stringResource(R.string.reports_kind_week), at(r.periodStart)) {
                    TextButton({ report(tools.deleteReport(r.id)) }) { Text(stringResource(R.string.data_delete)) }
                }
            }
            periodReports.forEachIndexed { i, r ->
                if (i > 0 || reports.isNotEmpty()) RowDivider()
                DataRow(io.github.warleysr.dechainer.report.PeriodInputs.label(r.kind, r.key), null) {
                    TextButton({ report(tools.deletePeriodReport(r.id)) }) { Text(stringResource(R.string.data_delete)) }
                }
            }
        }

        section(R.string.data_goals, goalDays.isEmpty()) {
            goalDays.forEachIndexed { i, d ->
                if (i > 0) RowDivider()
                DataRow(stringResource(R.string.data_goals_row, d.toString()), null) {
                    TextButton({ report(tools.deleteGoals(d)) }, enabled = tools.canDeleteGoals(d)) { Text(stringResource(R.string.data_delete)) }
                }
            }
        }

        item { Spacer(Modifier.height(Space.item)); Eyebrow(stringResource(R.string.data_wipe_section)) }
        item {
            CalmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.data_wipe_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        { gate.run { confirmWipe = true } },
                        Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.data_wipe)) }
                }
            }
        }
        item { Spacer(Modifier.height(Space.section)) }
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
                    message = ctx.getString(R.string.data_wipe_done, r.removed, r.kept)
                    version++
                }) { Text(stringResource(R.string.data_wipe)) }
            },
            dismissButton = { TextButton({ confirmWipe = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    RecoveryGateDialog(gate)
}

/** One titled section of the Data screen: an eyebrow and a card of rows, or "nothing here". */
private fun androidx.compose.foundation.lazy.LazyListScope.section(title: Int, empty: Boolean, rows: @Composable () -> Unit) {
    item { Spacer(Modifier.height(Space.item)); Eyebrow(stringResource(title)) }
    item {
        CalmCard(Modifier.fillMaxWidth()) {
            if (empty) Text(
                stringResource(R.string.data_none),
                Modifier.padding(Space.cardPadding),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ) else rows()
        }
    }
}

/** A row of the Data screen: what it is, when, and its actions at the end. */
@Composable
private fun DataRow(title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Space.cardPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}
