package io.github.warleysr.dechainer.screens.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.focus.FocusLogMath
import io.github.warleysr.dechainer.focus.FocusSession
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.ui.theme.CalmCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Every focus session, day by day, newest first, with the answer to "Did you do the work?" and a way to delete one. */
@Composable
fun FocusLogScreen() {
    val context = LocalContext.current
    LaunchedEffect(context) { withContext(Dispatchers.IO) { Pomodoro.ensureLoaded(context) } }
    val log by Pomodoro.log.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(log) { FocusLogMath.byDay(log, zone) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy") }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    // A session you chose to delete, waiting for confirmation.
    var toDelete by remember { mutableStateOf<FocusSession?>(null) }
    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.focus_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.focus_delete_text,
                        Instant.ofEpochMilli(s.id).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")),
                        formatMinutes(s.minutes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { Pomodoro.deleteSession(context, s.id); toDelete = null }) {
                    Text(stringResource(R.string.focus_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (log.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.focus_log_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(days, key = { it.date.toEpochDay() }) { day ->
            CalmCard(modifier = Modifier.fillMaxWidth(), highlighted = false) {
                Column(Modifier.padding(16.dp)) {
                    Text(day.date.format(dayFormat), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.focus_today_summary, sessionsLabel(day.count), day.doneCount, formatMinutes(day.minutes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    day.sessions.forEach { s ->
                        SessionRow(s, Instant.ofEpochMilli(s.id).atZone(zone).format(timeFormat)) { toDelete = s }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionRow(session: FocusSession, time: String, onDelete: () -> Unit) {
    val context = LocalContext.current
    val color = when (session.done) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(12.dp))
        Text(time, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.focus_minutes_short, session.minutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(
                when (session.done) {
                    true -> R.string.focus_log_done
                    false -> R.string.focus_log_not_done
                    null -> R.string.focus_log_unanswered
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.focus_session_menu),
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.focus_delete_confirm), color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
    session.intention?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
        )
    }
    }
}
