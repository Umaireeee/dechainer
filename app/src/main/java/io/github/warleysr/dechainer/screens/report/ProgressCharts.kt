package io.github.warleysr.dechainer.screens.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.report.Granularity
import io.github.warleysr.dechainer.report.ProgressPoint
import io.github.warleysr.dechainer.report.ProgressSeries
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The progress graphs (Reports): urges, slips, focus hours and the share of goals done, as small
 * multiples over the last 28 days, 12 weeks or 12 months. One series per chart, so a chart's title
 * names it and no colour has to carry identity. A tap on a bar selects that period in every chart,
 * and the readout above them gives its numbers in words; a table holds them all.
 */
@Composable
fun ProgressSection(
    points: List<ProgressPoint>,
    granularity: Granularity,
    onGranularity: (Granularity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(granularity, points.size) { mutableStateOf(points.lastIndex) }
    var table by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Granularity.entries.forEach { g ->
                FilterChip(
                    selected = g == granularity,
                    onClick = { onGranularity(g) },
                    label = { Text(stringResource(granularityLabel(g))) }
                )
            }
        }
        if (points.isEmpty()) return@Column

        val p = points[selected.coerceIn(0, points.lastIndex)]
        Text(periodName(p, granularity, fmt, current = selected == points.lastIndex), style = MaterialTheme.typography.titleMedium)
        Text(readout(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val urgeMax = ProgressSeries.niceMax(points.maxOf { maxOf(it.urges, it.slips) })
        BarChart(
            title = stringResource(R.string.progress_urges),
            values = points.map { it.urges.toFloat() },
            max = urgeMax.toFloat(),
            scaleLabel = urgeMax.toString(),
            selected = selected, onSelect = { selected = it },
            description = stringResource(R.string.progress_urges) + ": " + points.joinToString { it.urges.toString() }
        )
        // Slips share the urges' scale, so the two charts can be compared at a glance.
        BarChart(
            title = stringResource(R.string.progress_slips),
            values = points.map { it.slips.toFloat() },
            max = urgeMax.toFloat(),
            scaleLabel = urgeMax.toString(),
            selected = selected, onSelect = { selected = it },
            description = stringResource(R.string.progress_slips) + ": " + points.joinToString { it.slips.toString() }
        )
        val focusMax = ProgressSeries.niceMax((points.maxOf { it.focusMinutes } + 59) / 60, floor = 2)
        BarChart(
            title = stringResource(R.string.progress_focus),
            values = points.map { it.focusMinutes / 60f },
            max = focusMax.toFloat(),
            scaleLabel = stringResource(R.string.progress_hours, focusMax),
            selected = selected, onSelect = { selected = it },
            description = stringResource(R.string.progress_focus) + ": " + points.joinToString { hours(it.focusMinutes) }
        )
        BarChart(
            title = stringResource(R.string.progress_goals),
            values = points.map { it.percentDone?.toFloat() },
            max = 100f,
            scaleLabel = "100%",
            selected = selected, onSelect = { selected = it },
            description = stringResource(R.string.progress_goals) + ": " + points.joinToString { it.percentDone?.let { v -> "$v%" } ?: "-" }
        )
        // The axis ends, once for all four charts.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortName(points.first(), granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shortName(points.last(), granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton({ table = !table }) { Text(stringResource(if (table) R.string.progress_hide_table else R.string.progress_show_table)) }
        if (table) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                points.asReversed().forEach { q ->
                    Text(shortName(q, granularity) + " · " + readout(q), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun granularityLabel(g: Granularity): Int = when (g) {
    Granularity.DAY -> R.string.progress_days
    Granularity.WEEK -> R.string.progress_weeks
    Granularity.MONTH -> R.string.progress_months
}

private fun hours(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun readout(p: ProgressPoint): String {
    val parts = mutableListOf(
        pluralStringResource(R.plurals.progress_urges_n, p.urges, p.urges),
        pluralStringResource(R.plurals.progress_slips_n, p.slips, p.slips),
        stringResource(R.string.progress_focus_n, hours(p.focusMinutes))
    )
    p.percentDone?.let { parts += stringResource(R.string.progress_goals_n, it, p.goalsDone, p.goalsTotal) }
    if (p.daysShort > 0) parts += pluralStringResource(R.plurals.progress_short_n, p.daysShort, p.daysShort)
    return parts.joinToString(" · ")
}

@Composable
private fun periodName(p: ProgressPoint, g: Granularity, fmt: DateTimeFormatter, current: Boolean): String {
    val name = when (g) {
        Granularity.DAY -> fmt.format(p.first)
        Granularity.WEEK -> stringResource(R.string.progress_week_of, fmt.format(p.first))
        Granularity.MONTH -> p.first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }
    return if (current) stringResource(R.string.progress_so_far, name) else name
}

private fun shortName(p: ProgressPoint, g: Granularity): String = when (g) {
    Granularity.MONTH -> p.first.format(DateTimeFormatter.ofPattern("MMM yyyy"))
    else -> p.first.format(DateTimeFormatter.ofPattern("d MMM"))
}

/**
 * One series as bars on a shared baseline: thin bars with rounded tops anchored to the baseline and
 * a gap between them, a recessive baseline and top line, and the selected bar at full strength. A
 * null value (no goals that period) draws nothing, so "nothing planned" never looks like zero done.
 */
@Composable
private fun BarChart(
    title: String,
    values: List<Float?>,
    max: Float,
    scaleLabel: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    description: String
) {
    val bar = MaterialTheme.colorScheme.primary
    val dim = bar.copy(alpha = 0.45f)
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(scaleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(top = 4.dp)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { pos ->
                        if (values.isNotEmpty()) onSelect((pos.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex))
                    }
                }
        ) {
            val n = values.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val gap = maxOf(2.dp.toPx(), slot * 0.3f)
            val w = (slot - gap).coerceAtLeast(1f)
            val radius = minOf(4.dp.toPx(), w / 2)
            val line = 1.dp.toPx()
            drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = line)
            drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = line)
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0f || max <= 0f) return@forEachIndexed
                val h = (v / max).coerceAtMost(1f) * size.height
                val left = i * slot + gap / 2
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = size.height - h, right = left + w, bottom = size.height,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero
                        )
                    )
                }
                drawPath(path, if (i == selected) bar else dim)
            }
        }
    }
}
