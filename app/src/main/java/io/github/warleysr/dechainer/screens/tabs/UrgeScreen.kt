package io.github.warleysr.dechainer.screens.tabs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.UrgeRepository
import io.github.warleysr.dechainer.models.UrgeEntry
import io.github.warleysr.dechainer.models.UrgeStats
import io.github.warleysr.dechainer.models.UrgeTrigger
import io.github.warleysr.dechainer.models.UrgeWeek
import io.github.warleysr.dechainer.screens.common.SectionHeader
import io.github.warleysr.dechainer.ui.theme.CalmCard
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How long the person is asked to wait before deciding. Urges usually crest and fade within this. */
private const val WAIT_MS = 10L * 60 * 1000

@Composable
fun UrgeScreen() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(UrgeRepository.all(context)) }
    var trigger by remember { mutableStateOf<UrgeTrigger?>(null) }
    // Wall-clock start of the wait; 0 means not waiting.
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAt) {
        while (startedAt != 0L) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun finish(resisted: Boolean) {
        val t = trigger ?: return
        UrgeRepository.add(context, UrgeEntry(System.currentTimeMillis(), t, resisted))
        entries = UrgeRepository.all(context)
        startedAt = 0L
        trigger = null
    }

    val week = UrgeStats.week(entries, System.currentTimeMillis())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text(
                stringResource(R.string.urge_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        item {
            CalmCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                highlighted = startedAt != 0L
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (startedAt == 0L) {
                        Text(stringResource(R.string.urge_what_set_off), style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(UrgeTrigger.entries.toList(), key = { it.name }) { t ->
                                FilterChip(
                                    selected = trigger == t,
                                    onClick = { trigger = t },
                                    label = { Text(stringResource(triggerRes(t))) }
                                )
                            }
                        }
                        Button(
                            onClick = { now = System.currentTimeMillis(); startedAt = now },
                            enabled = trigger != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.urge_start)) }
                    } else {
                        val left = (WAIT_MS - (now - startedAt)).coerceAtLeast(0L)
                        Text(
                            "%d:%02d".format(left / 60_000, (left / 1000) % 60),
                            style = MaterialTheme.typography.displayMedium
                        )
                        Text(
                            stringResource(if (left > 0) R.string.urge_wait_hint else R.string.urge_wait_done),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { finish(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.urge_got_through))
                        }
                        OutlinedButton(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.urge_gave_in))
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.urge_week)) }
        item {
            CalmCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val summary = summaryText(week)
                    Text(summary, style = MaterialTheme.typography.bodyLarge)
                    if (week.total > 0) {
                        Row {
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, summary)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) {
                                Icon(Icons.Outlined.Share, null, Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.urge_share))
                            }
                        }
                        Text(
                            stringResource(R.string.urge_share_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (entries.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.urge_recent)) }
            items(entries.takeLast(10).reversed(), key = { it.time }) { e ->
                val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$whenText · ${stringResource(triggerRes(e.trigger))}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (e.resisted) R.string.urge_result_through else R.string.urge_result_gave_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e.resisted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun summaryText(w: UrgeWeek): String {
    if (w.total == 0) return stringResource(R.string.urge_week_empty)
    val lines = mutableListOf(
        stringResource(R.string.urge_week_counts, w.total, w.resisted, w.gaveIn)
    )
    w.topTrigger?.let { lines += stringResource(R.string.urge_week_top, stringResource(triggerRes(it))) }
    w.peakHour?.let { lines += stringResource(R.string.urge_week_peak, "%02d:00".format(it)) }
    w.daysSinceGaveIn?.let { lines += stringResource(R.string.urge_week_days, it) }
    return lines.joinToString("\n")
}

private fun triggerRes(t: UrgeTrigger): Int = when (t) {
    UrgeTrigger.BORED -> R.string.urge_trigger_bored
    UrgeTrigger.STRESSED -> R.string.urge_trigger_stressed
    UrgeTrigger.LONELY -> R.string.urge_trigger_lonely
    UrgeTrigger.TIRED -> R.string.urge_trigger_tired
    UrgeTrigger.ANXIOUS -> R.string.urge_trigger_anxious
    UrgeTrigger.HABIT -> R.string.urge_trigger_habit
    UrgeTrigger.OTHER -> R.string.urge_trigger_other
}
