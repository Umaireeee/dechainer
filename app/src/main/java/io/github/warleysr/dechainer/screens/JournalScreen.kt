package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.screens.urge.MarkdownText
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.urge.DeepDiveScheduler
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread

private fun summaryOf(e: UrgeEntry): Int = when {
    e.deepDive != null -> R.string.journal_has_deep_dive
    e.status == UrgeStatus.PENDING_DEEPDIVE -> R.string.journal_waiting
    e.status == UrgeStatus.SKIPPED -> R.string.journal_skipped
    else -> R.string.journal_unfinished
}

/**
 * Deep dives: every urge and slip, newest first, each one a tap away from its deep dive. The deep
 * dive used to be shown once and then could not be read again; this is the place it is kept. Read
 * only, so it is open on any day.
 */
@Composable
fun JournalScreen(onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val zone = TrustedClock.zone()
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
    val entries = remember { runCatching { Store.urgeEntries(ctx).all().asReversed() }.getOrDefault(emptyList()) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.journal_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isEmpty()) item { Text(stringResource(R.string.journal_empty), style = MaterialTheme.typography.bodyLarge) }
        items(entries, key = { it.id }) { e ->
            EntryRow(
                title = stringResource(if (e.kind == UrgeKind.SLIP) R.string.data_kind_slip else R.string.data_kind_urge) + " · " +
                    fmt.format(Instant.ofEpochMilli(e.createdAt).atZone(zone)),
                subtitle = stringResource(summaryOf(e)),
                onClick = { onOpen(e.id) }
            )
        }
    }
}

/** A tappable row with a chevron: the whole row is the target, at least 56 dp tall. */
@Composable
fun EntryRow(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CalmCard(modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One entry in full: its deep dive, the answers behind it, and (only while it is still waiting) the note. */
@Composable
fun EntryScreen(id: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val zone = TrustedClock.zone()
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
    var version by remember { mutableIntStateOf(0) }
    val entry = remember(id, version) { runCatching { Store.urgeEntries(ctx).get(id) }.getOrNull() }
    var retried by remember { mutableStateOf(false) }

    if (entry == null) {
        Text(stringResource(R.string.entry_missing), modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val answers = remember(entry) { UrgeJson.answersFromJson(entry.answersJson) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (entry.kind == UrgeKind.SLIP) R.string.data_kind_slip else R.string.data_kind_urge) + " · " +
                fmt.format(Instant.ofEpochMilli(entry.createdAt).atZone(zone)),
            style = MaterialTheme.typography.titleLarge
        )
        if (entry.kind == UrgeKind.URGE && entry.lockStartedAt != null && entry.lockEndedAt != null) {
            Text(stringResource(R.string.entry_sat_through), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val dd = entry.deepDive
        if (dd != null) {
            MarkdownText(dd)
        } else {
            Text(stringResource(summaryOf(entry)), style = MaterialTheme.typography.bodyLarge)
            if (entry.status == UrgeStatus.PENDING_DEEPDIVE) {
                Text(stringResource(R.string.urge_dd_pending_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        retried = true
                        thread { DeepDiveScheduler.enqueueIfPending(ctx.applicationContext, replace = true) }
                    },
                    enabled = !retried,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(if (retried) R.string.entry_retry_queued else R.string.entry_retry)) }
            }
        }

        if (answers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.entry_answers), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            answers.forEach { a ->
                Column {
                    Text(a.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.answer.ifBlank { stringResource(R.string.entry_no_answer) }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        // A note that is still on the phone belongs to its owner too: it is readable here until it is deleted.
        entry.rawText?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.entry_note), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(note, style = MaterialTheme.typography.bodyLarge)
        }
    }
    // A deep dive that arrived in the background while this screen was open shows without leaving it.
    LaunchedEffect(entry.status, retried) {
        if (entry.status == UrgeStatus.PENDING_DEEPDIVE && retried) {
            repeat(20) {
                kotlinx.coroutines.delay(3_000)
                version++
            }
        }
    }
}
