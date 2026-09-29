package io.github.warleysr.dechainer.screens.focus

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.OutlinedButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The long view: a chart of hours by day or by week, hours per subject for that range, this
 * week's total, and then every session, day by day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusLogScreen() {
    val context = LocalContext.current
    remember { Pomodoro.ensureLoaded(context) }
    val log by Pomodoro.log.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now()
    var weekly by rememberSaveable { mutableStateOf(false) }
    // What the chart counts: study time, or lectures finished.
    var lecturesMode by rememberSaveable { mutableStateOf(false) }
    val metric: (FocusSession) -> Int =
        if (lecturesMode) ({ s: FocusSession -> s.lectures }) else ({ s: FocusSession -> s.minutes })

    val days = remember(log) { FocusLogMath.byDay(log, zone) }
    // Which page of history: 0 is the latest (ending today), 1 the one before it, and so on.
    var page by remember(weekly) { mutableStateOf(0) }
    val windowEnd = if (weekly) today.minusWeeks(12L * page) else today.minusDays(14L * page)
    val bars = remember(log, weekly, windowEnd, lecturesMode) {
        if (weekly) FocusLogMath.weeklyMinutes(log, windowEnd, 12, zone, metric)
        else FocusLogMath.dailyMinutes(log, windowEnd, 14, zone, metric)
    }
    // Back as far as your first session, forward no further than today.
    val firstDate = remember(log) { log.minByOrNull { it.id }?.date(zone) }
    val canGoBack = firstDate != null && firstDate.isBefore(bars.first().first)
    val canGoForward = page > 0
    val goBack = { if (canGoBack) page++ }
    val goForward = { if (canGoForward) page-- }
    // Opens on the latest point of the page: today on the first page.
    var selected by remember(weekly, page) { mutableStateOf(bars.size - 1) }
    val selectedStart = bars[selected.coerceIn(0, bars.size - 1)].first
    val selectedEnd = if (weekly) selectedStart.plusDays(6) else selectedStart
    // The list below shows only the chosen day (or week): today until you tap another point.
    val shownDays = remember(days, selectedStart, selectedEnd) {
        days.filter { !it.date.isBefore(selectedStart) && !it.date.isAfter(selectedEnd) }
    }
    val rangeStart = bars.first().first
    val rangeEnd = if (weekly) bars.last().first.plusDays(6) else bars.last().first
    val inRange = remember(log, rangeStart, rangeEnd) { FocusLogMath.between(log, rangeStart, rangeEnd, zone) }
    val bySubject = remember(inRange, lecturesMode) { FocusLogMath.minutesByTag(inRange, metric) }
    val week = remember(log) { FocusLogMath.thisWeek(log, today, zone) }

    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy") }
    val backup = rememberBackup()
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
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    if (log.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.focus_log_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            // Kept here on purpose: after a reinstall the log is empty and this is how you restore.
            OutlinedButton(onClick = { backup.import() }) { Text(stringResource(R.string.focus_backup_import)) }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- This week ---
        item {
            // A summary, not something live: no highlight. Backup lives in its menu.
            CalmCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text(stringResource(R.string.focus_week), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMinutes(week.sumOf { it.minutes }), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            stringResource(R.string.focus_week_detail, sessionsLabel(week.size), week.count { it.done == true }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.focus_backup_menu),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.focus_backup_export)) },
                                onClick = { menuOpen = false; backup.export() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.focus_backup_import)) },
                                onClick = { menuOpen = false; backup.import() }
                            )
                        }
                    }
                }
            }
        }

        // --- The chart, and hours per subject for the same range ---
        item {
            CalmCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !lecturesMode, onClick = { lecturesMode = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.focus_metric_hours)) }
                        SegmentedButton(
                            selected = lecturesMode, onClick = { lecturesMode = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.focus_metric_lectures)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = !weekly, onClick = { weekly = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) { Text(stringResource(R.string.focus_chart_days)) }
                            SegmentedButton(
                                selected = weekly, onClick = { weekly = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) { Text(stringResource(R.string.focus_chart_weeks)) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Where you are in time, with arrows; swiping the chart does the same.
                    val rangeFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = goBack, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.focus_chart_older))
                        }
                        Text(
                            "${rangeStart.format(rangeFormat)} – ${rangeEnd.format(rangeFormat)}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        IconButton(onClick = goForward, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.focus_chart_newer))
                        }
                    }
                    if (canGoForward) {
                        TextButton(onClick = { page = 0 }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.focus_chart_today))
                        }
                    }
                    StudyChart(bars, weekly, selected, firstDate, lecturesMode, onSelect = { selected = it }, onSwipe = { older -> if (older) goBack() else goForward() })

                    // Only when there's a split to see: one subject (or none) has nothing to compare.
                    if (bySubject.size >= 2) {
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.focus_by_subject), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        val top = bySubject.maxOf { it.second }.coerceAtLeast(1)
                        bySubject.forEach { (tag, minutes) ->
                            SubjectBar(
                                tag ?: stringResource(R.string.focus_untagged),
                                if (lecturesMode) lecturesLabel(minutes) else formatMinutes(minutes),
                                minutes.toFloat() / top
                            )
                        }
                    }
                }
            }
        }

        // --- The chosen day's sessions (today until you tap another point) ---
        if (shownDays.isEmpty()) {
            item {
                Text(
                    stringResource(if (weekly) R.string.focus_log_none_week else R.string.focus_log_none_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
        items(shownDays, key = { it.date.toEpochDay() }) { day ->
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

/**
 * The study chart: one smooth line of your time per day (or week), a soft fill under it, and a
 * dashed line at your average for the range. Tap anywhere to read a point; it starts on the
 * latest one. The curve's control points sit level with its data points, so it never dips below
 * zero or swings past a day's real value.
 */
@Composable
private fun StudyChart(
    points: List<Pair<LocalDate, Int>>,
    weekly: Boolean,
    selected: Int,
    firstSession: LocalDate?,
    lectures: Boolean,
    onSelect: (Int) -> Unit,
    onSwipe: (older: Boolean) -> Unit
) {
    val values = points.map { it.second }
    val n = values.size
    val select by rememberUpdatedState(onSelect)
    val swipe by rememberUpdatedState(onSwipe)
    val total = values.sum()
    // Exact, not rounded: lectures average things like 1.5 a day.
    val average = FocusLogMath.pageAverageExact(points, firstSession, weekly)
    val max = maxOf(values.maxOrNull() ?: 0, 1)

    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    val avgColor = MaterialTheme.colorScheme.tertiary
    val labelFormat = remember { DateTimeFormatter.ofPattern("d MMM") }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    // --- Readout for the selected point ---
    val sel = points.getOrNull(selected)
    if (sel != null) {
        Text(
            if (weekly) stringResource(R.string.focus_chart_week_of, sel.first.format(labelFormat)) else sel.first.format(dayFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(if (lectures) lecturesLabel(sel.second) else formatMinutes(sel.second), style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(4.dp))

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            // Swipe right for older, left for newer: one page per swipe.
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        if (dx > threshold) swipe(true) else if (dx < -threshold) swipe(false)
                    }
                ) { _, amount -> dx += amount }
            }
            .pointerInput(n) {
                detectTapGestures { pos ->
                    if (n >= 2) {
                        val pad = 8.dp.toPx()
                        val step = (size.width - pad * 2) / (n - 1)
                        select(((pos.x - pad) / step).roundToInt().coerceIn(0, n - 1))
                    }
                }
            }
    ) {
        if (n < 2) return@Canvas
        val pad = 8.dp.toPx()
        val top = pad
        val bottom = size.height - pad
        val step = (size.width - pad * 2) / (n - 1)
        fun x(i: Int) = pad + i * step
        fun y(v: Int) = bottom - (bottom - top) * v / max
        fun y(v: Float) = bottom - (bottom - top) * v / max

        // Baseline.
        drawLine(guide, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())

        // Smooth path through every point.
        val path = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until n) {
                val midX = (x(i - 1) + x(i)) / 2
                cubicTo(midX, y(values[i - 1]), midX, y(values[i]), x(i), y(values[i]))
            }
        }
        // Soft fill under it.
        val fill = Path().apply {
            addPath(path)
            lineTo(x(n - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0f)), startY = top, endY = bottom))

        // Average.
        if (average > 0 && average < max * 0.9f) {
            drawLine(
                avgColor.copy(alpha = 0.8f), Offset(0f, y(average)), Offset(size.width, y(average)),
                1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
        }

        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // A dot on every point: filled where you studied, a quiet ring where you didn't.
        for (i in 0 until n) {
            if (i == selected) continue
            if (values[i] == 0) drawCircle(guide, 3.dp.toPx(), Offset(x(i), y(0)), style = Stroke(1.5.dp.toPx()))
            else drawCircle(line, 3.5.dp.toPx(), Offset(x(i), y(values[i])))
        }

        // Selected point: a guide down to the baseline and a ringed dot.
        val sx = x(selected.coerceIn(0, n - 1))
        val sy = y(values[selected.coerceIn(0, n - 1)])
        drawLine(guide, Offset(sx, sy), Offset(sx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        drawCircle(line.copy(alpha = 0.25f), 9.dp.toPx(), Offset(sx, sy))
        drawCircle(line, 4.5.dp.toPx(), Offset(sx, sy))
    }

    // --- Range ends and the summary ---
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            points.first().first.format(labelFormat),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            points.last().first.format(labelFormat),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Same rule as the line itself: no marker for a line that isn't drawn.
        if (average > 0 && average < max * 0.9f) {
            Canvas(Modifier.size(width = 16.dp, height = 2.dp)) {
                drawLine(avgColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(
                when {
                    lectures && weekly -> R.string.focus_chart_summary_week_lectures
                    lectures -> R.string.focus_chart_summary_day_lectures
                    weekly -> R.string.focus_chart_summary_week
                    else -> R.string.focus_chart_summary_day
                },
                if (lectures) lecturesLabel(total) else formatMinutes(total),
                if (lectures) "%.1f".format(average) else formatMinutes(average.toInt())
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SubjectBar(name: String, valueLabel: String, fraction: Float) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(track, size = size, cornerRadius = r)
                drawRoundRect(bar, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
            }
        }
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
        Text(
            session.tag ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            (if (session.lectures > 0) "✓ " + lecturesLabel(session.lectures) + " · " else "") +
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
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.focus_session_menu),
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // For a lecture finished outside a checkpoint (a quick one), or a slip to undo.
                DropdownMenuItem(
                    text = { Text(stringResource(if (session.lectures > 0) R.string.focus_lecture_unmark else R.string.focus_lecture_mark)) },
                    onClick = { menuOpen = false; Pomodoro.setLectures(context, session.id, if (session.lectures > 0) 0 else 1) }
                )
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

/** Export and import of the log, as two actions any part of the screen can call. */
private class BackupActions(val export: () -> Unit, val import: () -> Unit)

/**
 * CSV export (opens in Excel or Sheets) and import. Uses Android's own file picker, which works
 * even while Files by Google is suspended. Importing only adds sessions that aren't in the log
 * yet, so doing it twice is harmless.
 */
@Composable
private fun rememberBackup(): BackupActions {
    val context = LocalContext.current
    val exported = stringResource(R.string.focus_backup_exported)
    val failed = stringResource(R.string.focus_backup_failed)
    val importedFormat = stringResource(R.string.focus_backup_imported)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Pomodoro.exportTo(context, uri)
            Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val added = Pomodoro.importFrom(context, uri)
            Toast.makeText(context, if (added < 0) failed else importedFormat.format(added), Toast.LENGTH_SHORT).show()
        }
    }
    return remember(exporter, importer) {
        BackupActions(
            export = { exporter.launch("focus-log-${LocalDate.now()}.csv") },
            import = { importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
        )
    }
}

/** "1 lecture", "3 lectures". */
@Composable
fun lecturesLabel(count: Int): String =
    if (count == 1) stringResource(R.string.count_lecture_one) else stringResource(R.string.count_lecture_many, count)

