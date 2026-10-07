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
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.store.DataTools
import io.github.warleysr.dechainer.store.DeleteResult
import io.github.warleysr.dechainer.store.Store
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Your data (blueprint 6.6): delete single blackouts and focus sessions, wipe everything the rules
 * allow (behind the recovery code), and the backup file.
 */
@Composable
fun DataScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val zone = TrustedClock.zone()
    val tools = remember { DataTools(ctx) { TrustedClock.now(ctx) } }
    val gate = rememberRecoveryGate()
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }
    fun at(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    val urges = remember(version) { Store.urgeEntries(ctx).all().asReversed() }
    val sessions = remember(version) { Store.focus(ctx).sessions().asReversed() }

    val savedMsg = stringResource(R.string.data_exported)
    val saveFailedMsg = stringResource(R.string.data_export_failed)
    val deletedMsg = stringResource(R.string.data_deleted)
    val notAllowedMsg = stringResource(R.string.data_not_allowed)
    val importFailedMsg = stringResource(R.string.data_import_failed)

    fun showResult(r: DeleteResult) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_urge_row, at(e.createdAt)), Modifier.weight(1f))
                TextButton({ showResult(tools.deleteUrge(e.id)) }) { Text(stringResource(R.string.data_delete)) }
            }
        }

        item { Text(stringResource(R.string.data_sessions), style = MaterialTheme.typography.titleLarge) }
        if (sessions.isEmpty()) item { Text(stringResource(R.string.data_none)) }
        items(sessions, key = { "s${it.id}" }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_session_row, at(s.startedAt), s.focusedMinutes, s.purpose.ifBlank { "-" }), Modifier.weight(1f))
                TextButton({ showResult(tools.deleteSession(s.id)) }) { Text(stringResource(R.string.data_delete)) }
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
