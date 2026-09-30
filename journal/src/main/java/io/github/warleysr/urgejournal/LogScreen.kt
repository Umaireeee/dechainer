package io.github.warleysr.urgejournal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Every entry, the way Déchaîner's focus log shows every session: a chart of the last 14 days or 12
 * weeks, a tap on a bar to see just that day or week, the feelings behind them, and the full list.
 */
@Composable
fun LogScreen(
    entries: List<Entry>,
    onOpen: (Entry) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    var weekly by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var shown by remember { mutableIntStateOf(30) }

    val bars = remember(entries, weekly) {
        if (weekly) Insights.weeks(entries, now, zone, 12) else Insights.days(entries, now, zone, 14)
    }
    val bar = picked?.let { bars.getOrNull(it) }
    val scoped = if (bar != null) {
        Insights.entriesIn(entries, bar.date, bar.date.plusDays(if (weekly) 6L else 0L), zone)
    } else entries
    val visible = scoped.filter { filter.matches(it) }.sortedByDescending { it.time }
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    // The range shown in the chart, in numbers.
    val inRange = Insights.entriesIn(
        entries, bars.first().date, bars.last().date.plusDays(if (weekly) 6L else 0L), zone
    ).count { !it.isStub }
    val rangeThrough = bars.sumOf { it.resisted }
    val rangeGaveIn = bars.sumOf { it.gaveIn }

    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.log_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.log_total, entries.count { !it.isStub }),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !weekly,
                onClick = { weekly = false; picked = null; shown = 30 },
                label = { Text(stringResource(R.string.log_14_days)) }
            )
            FilterChip(
                selected = weekly,
                onClick = { weekly = true; picked = null; shown = 30 },
                label = { Text(stringResource(R.string.log_12_weeks)) }
            )
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(inRange.toString(), stringResource(R.string.stat_logged), Modifier)
                Stat(rangeThrough.toString(), stringResource(R.string.stat_resisted), Modifier)
                Stat(rangeGaveIn.toString(), stringResource(R.string.stat_gave_in), Modifier)
            }
            WeekBars(
                bars = bars,
                labels = if (weekly) bars.map { it.date.dayOfMonth.toString() } else null,
                selected = picked,
                onPick = { i ->
                    picked = if (picked == i) null else i
                    shown = 30
                }
            )
            Text(
                stringResource(if (weekly) R.string.log_tap_week else R.string.log_tap_day),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val feelings = Insights.feelingCounts(scoped)
        if (feelings.isNotEmpty()) {
            Eyebrow(stringResource(R.string.log_by_feeling))
            Panel {
                feelings.take(6).forEach { (feeling, count) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label("opt_", feeling), style = MaterialTheme.typography.bodyLarge)
                        Text(count.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == LogFilter.ALL, onClick = { filter = LogFilter.ALL; shown = 30 }, label = { Text(stringResource(R.string.log_filter_all)) })
            FilterChip(selected = filter == LogFilter.THROUGH, onClick = { filter = LogFilter.THROUGH; shown = 30 }, label = { Text(stringResource(R.string.result_through)) })
            FilterChip(selected = filter == LogFilter.GAVE_IN, onClick = { filter = LogFilter.GAVE_IN; shown = 30 }, label = { Text(stringResource(R.string.log_filter_gave_in)) })
        }

        Eyebrow(
            if (bar != null) stringResource(R.string.log_showing, dateFormat.format(bar.date), visible.size)
            else stringResource(R.string.log_all, visible.size)
        )
        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        visible.take(shown).groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.forEach { (day, list) ->
            Text(
                dateFormat.format(day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            list.forEach { e ->
                val feeling = e.answers[Q.FEELING]?.let { label("opt_", it) }
                    ?: e.after?.let { label("after_", it) } ?: stringResource(R.string.entry_urge)
                val result = when {
                    e.slipped -> stringResource(R.string.result_slipped)
                    e.outcome == Outcome.RESISTED -> stringResource(R.string.result_through)
                    e.outcome == Outcome.GAVE_IN -> stringResource(R.string.result_gave_in)
                    else -> stringResource(R.string.result_open)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${timeFormat.format(Instant.ofEpochMilli(e.time).atZone(zone))} · $feeling",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e.gaveIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (visible.size > shown) {
            TextButton(onClick = { shown += 30 }) { Text(stringResource(R.string.recent_more)) }
        }

        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.log_export))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
        Spacer(Modifier.height(16.dp))
    }
}
