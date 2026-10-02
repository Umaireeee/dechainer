package io.github.warleysr.dechainer.screens.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.report.Granularity
import io.github.warleysr.dechainer.report.ProgressPoint
import io.github.warleysr.dechainer.report.ProgressSeries
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.ui.theme.Eyebrow
import io.github.warleysr.dechainer.ui.theme.Segmented
import io.github.warleysr.dechainer.ui.theme.Space
import io.github.warleysr.dechainer.ui.theme.StatTile
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the trend chart shows; the four stat tiles pick it. */
enum class Metric { URGES, SLIPS, FOCUS, GOALS }

/**
 * Progress (Reports): the chosen period in four stat tiles (urges, slips, focus, goals done), each
 * with its change from the period before, and below them one trend chart of the metric whose tile
 * is selected. A tap on a bar picks that period for the tiles. One series at a time, so nothing
 * depends on telling colours apart; a table holds every number.
 */
@Composable
fun ProgressSection(
    points: List<ProgressPoint>,
    granularity: Granularity,
    onGranularity: (Granularity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(granularity, points.size) { mutableStateOf(points.lastIndex) }
    var metric by rememberSaveable { mutableStateOf(Metric.URGES) }
    var table by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.item)) {
        Segmented(
            options = Granularity.entries,
            selected = granularity,
            label = { stringResource(granularityLabel(it)) },
            onSelect = onGranularity,
            modifier = Modifier.fillMaxWidth()
        )
        if (points.isEmpty()) return@Column

        val index = selected.coerceIn(0, points.lastIndex)
        val p = points[index]
        val before = points.getOrNull(index - 1)
        val current = index == points.lastIndex

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(periodName(p, granularity, fmt, current), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(if (current) R.string.progress_so_far_note else R.string.progress_tap_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The four tiles, two by two. Each picks the metric of the chart below.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.item)) {
            StatTile(
                stringResource(R.string.progress_urges), p.urges.toString(),
                countDelta(p.urges, before?.urges, granularity), metric == Metric.URGES, { metric = Metric.URGES }, Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.progress_slips), p.slips.toString(),
                countDelta(p.slips, before?.slips, granularity), metric == Metric.SLIPS, { metric = Metric.SLIPS }, Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.item)) {
            StatTile(
                stringResource(R.string.progress_focus), duration(p.focusMinutes),
                minutesDelta(p.focusMinutes, before?.focusMinutes, granularity), metric == Metric.FOCUS, { metric = Metric.FOCUS }, Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.progress_goals), p.percentDone?.let { "$it%" } ?: "–",
                goalsNote(p, before, granularity), metric == Metric.GOALS, { metric = Metric.GOALS }, Modifier.weight(1f)
            )
        }

        CalmCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.padding(Space.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(stringResource(metricTitle(metric)), Modifier.weight(1f))
                    Text(
                        stringResource(rangeLabel(granularity)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val withData = points.count { it.urges + it.slips + it.focusMinutes + it.goalsTotal > 0 }
                if (withData < 2) {
                    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.progress_not_enough),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TrendChart(points, metric, granularity, index) { selected = it }
                }
            }
        }

        TextButton({ table = !table }) {
            Text(stringResource(if (table) R.string.progress_hide_table else R.string.progress_show_table))
        }
        if (table) {
            CalmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    points.asReversed().forEach { q ->
                        Column {
                            Text(shortName(q, granularity), style = MaterialTheme.typography.labelLarge)
                            Text(readout(q), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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

private fun rangeLabel(g: Granularity): Int = when (g) {
    Granularity.DAY -> R.string.progress_range_days
    Granularity.WEEK -> R.string.progress_range_weeks
    Granularity.MONTH -> R.string.progress_range_months
}

private fun metricTitle(m: Metric): Int = when (m) {
    Metric.URGES -> R.string.progress_urges
    Metric.SLIPS -> R.string.progress_slips
    Metric.FOCUS -> R.string.progress_focus
    Metric.GOALS -> R.string.progress_goals
}

/** 3h 05m, or 45m, or 0m. */
private fun duration(minutes: Int): String = if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"

@Composable
private fun versus(g: Granularity, change: String): String = stringResource(
    when (g) {
        Granularity.DAY -> R.string.progress_vs_day
        Granularity.WEEK -> R.string.progress_vs_week
        Granularity.MONTH -> R.string.progress_vs_month
    },
    change
)

@Composable
private fun countDelta(now: Int, before: Int?, g: Granularity): String? {
    before ?: return null
    val d = now - before
    return if (d == 0) stringResource(R.string.progress_same) else versus(g, (if (d > 0) "+" else "−") + kotlin.math.abs(d))
}

@Composable
private fun minutesDelta(now: Int, before: Int?, g: Granularity): String? {
    before ?: return null
    val d = now - before
    return if (d == 0) stringResource(R.string.progress_same) else versus(g, (if (d > 0) "+" else "−") + duration(kotlin.math.abs(d)))
}

@Composable
private fun goalsNote(p: ProgressPoint, before: ProgressPoint?, g: Granularity): String? {
    val now = p.percentDone ?: return stringResource(R.string.progress_no_goals)
    val was = before?.percentDone ?: return stringResource(R.string.progress_goals_count, p.goalsDone, p.goalsTotal)
    val d = now - was
    return if (d == 0) stringResource(R.string.progress_same) else versus(g, (if (d > 0) "+" else "−") + kotlin.math.abs(d) + " pts")
}

@Composable
private fun readout(p: ProgressPoint): String {
    val parts = mutableListOf(
        pluralStringResource(R.plurals.progress_urges_n, p.urges, p.urges),
        pluralStringResource(R.plurals.progress_slips_n, p.slips, p.slips),
        stringResource(R.string.progress_focus_n, duration(p.focusMinutes))
    )
    p.percentDone?.let { parts += stringResource(R.string.progress_goals_n, it, p.goalsDone, p.goalsTotal) }
    if (p.daysShort > 0) parts += pluralStringResource(R.plurals.progress_short_n, p.daysShort, p.daysShort)
    return parts.joinToString(" · ")
}

@Composable
private fun periodName(p: ProgressPoint, g: Granularity, fmt: DateTimeFormatter, current: Boolean): String = when {
    current && g == Granularity.DAY -> stringResource(R.string.progress_today)
    current && g == Granularity.WEEK -> stringResource(R.string.progress_this_week)
    current && g == Granularity.MONTH -> stringResource(R.string.progress_this_month)
    g == Granularity.DAY -> fmt.format(p.first)
    g == Granularity.WEEK -> stringResource(R.string.progress_week_of, p.first.format(DateTimeFormatter.ofPattern("d MMM")))
    else -> p.first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
}

private fun shortName(p: ProgressPoint, g: Granularity): String = when (g) {
    Granularity.MONTH -> p.first.format(DateTimeFormatter.ofPattern("MMM"))
    else -> p.first.format(DateTimeFormatter.ofPattern("d MMM"))
}

private fun value(p: ProgressPoint, m: Metric): Float? = when (m) {
    Metric.URGES -> p.urges.toFloat()
    Metric.SLIPS -> p.slips.toFloat()
    Metric.FOCUS -> p.focusMinutes / 60f
    Metric.GOALS -> p.percentDone?.toFloat()
}

private fun label(v: Float, m: Metric): String = when (m) {
    Metric.FOCUS -> duration((v * 60).toInt())
    Metric.GOALS -> "${v.toInt()}%"
    else -> v.toInt().toString()
}

/**
 * One metric over time as slim bars on a shared baseline. Zero is a small dot, so the time axis
 * stays readable; "no goals planned" draws nothing, so it never looks like zero done. The selected
 * bar is at full strength with its value above it; the scale's top is a dashed line with its value at the left.
 */
@Composable
private fun TrendChart(points: List<ProgressPoint>, metric: Metric, g: Granularity, selected: Int, onSelect: (Int) -> Unit) {
    val values = points.map { value(it, metric) }
    val top = when (metric) {
        Metric.GOALS -> 100f
        Metric.FOCUS -> ProgressSeries.niceMax(kotlin.math.ceil(values.maxOf { it ?: 0f }).toInt(), floor = 2).toFloat()
        else -> ProgressSeries.niceMax(values.maxOf { it ?: 0f }.toInt()).toFloat()
    }
    val bar = MaterialTheme.colorScheme.primary
    val dim = bar.copy(alpha = 0.32f)
    val rule = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = ink)
    // The bars grow in when the metric or the range changes: a signal that the view changed, nothing more.
    val grow = remember(metric, g) { Animatable(0f) }
    LaunchedEffect(metric, g) { grow.animateTo(1f, tween(450)) }
    val description = stringResource(metricTitle(metric)) + ": " + values.joinToString { v -> v?.let { label(it, metric) } ?: "–" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = description }
                .pointerInput(points.size) {
                    detectTapGestures { pos -> onSelect((pos.x / (size.width.toFloat() / points.size)).toInt().coerceIn(0, points.lastIndex)) }
                }
        ) {
            val n = values.size
            val labelSpace = 22.dp.toPx()
            val chartTop = labelSpace
            val base = size.height - 1.dp.toPx()
            val h = base - chartTop
            val slot = size.width / n
            val w = minOf(slot * 0.56f, 14.dp.toPx())
            val radius = w / 2

            // The scale: a dashed line at the top with its value, and the baseline.
            drawLine(rule, Offset(0f, chartTop), Offset(size.width, chartTop), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            drawLine(rule, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
            val topText = measurer.measure(label(top, metric), small.copy(color = muted))
            drawText(topText, topLeft = Offset(0f, chartTop - topText.size.height - 2.dp.toPx()))

            values.forEachIndexed { i, v ->
                val cx = slot * i + slot / 2
                when {
                    v == null -> Unit
                    v <= 0f -> drawCircle(dot, radius = 2.dp.toPx(), center = Offset(cx, base - 4.dp.toPx()))
                    else -> {
                        val bh = ((v / top).coerceAtMost(1f) * h * grow.value).coerceAtLeast(w)
                        drawRoundRect(
                            color = if (i == selected) bar else dim,
                            topLeft = Offset(cx - w / 2, base - bh),
                            size = Size(w, bh),
                            cornerRadius = CornerRadius(radius)
                        )
                    }
                }
            }

            // The selected period's value, above its bar.
            values.getOrNull(selected)?.let { v ->
                val text = measurer.measure(label(v, metric), valueStyle)
                val cx = slot * selected + slot / 2
                val bh = if (v <= 0f) 8.dp.toPx() else ((v / top).coerceAtMost(1f) * h * grow.value).coerceAtLeast(w)
                val x = (cx - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                val y = (base - bh - text.size.height - 4.dp.toPx()).coerceAtLeast(0f)
                drawText(text, topLeft = Offset(x, y))
            }
        }
        // The time axis: the first, the middle and the last period.
        Row(Modifier.fillMaxWidth()) {
            val mid = points.size / 2
            Text(shortName(points.first(), g), Modifier.weight(1f), style = small, color = muted)
            Text(shortName(points[mid], g), Modifier.weight(1f), style = small, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(shortName(points.last(), g), Modifier.weight(1f), style = small, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}
