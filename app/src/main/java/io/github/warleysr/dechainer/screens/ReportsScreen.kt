package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.DayRow
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.lock.SettingsFreeze
import io.github.warleysr.dechainer.screens.urge.MarkdownText
import io.github.warleysr.dechainer.store.StoredReport
import io.github.warleysr.dechainer.store.Store
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Reports (blueprint 6.5): the weekly reports and the progress line of the daily results. Read only,
 * so it stays open on a punishment day. Deleting and the backup are on [DataScreen].
 */
@Composable
fun ReportsScreen(openReportId: Long?, onOpenData: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val zone = TrustedClock.zone()
    val reports = remember { Store.reports(ctx).all().filter { !it.deleted } }
    val rows = remember {
        val today = DayWindow.dateOf(TrustedClock.now(ctx), zone)
        Store.days(ctx).daysBetween(today.minusDays(27), today)
    }
    var openId by remember(openReportId) { mutableStateOf(openReportId) }
    val open = reports.firstOrNull { it.id == openId }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    @Composable
    fun label(r: StoredReport) = stringResource(
        R.string.reports_week,
        fmt.format(Instant.ofEpochMilli(r.periodStart).atZone(zone).toLocalDate()),
        fmt.format(Instant.ofEpochMilli(r.periodEnd - 1).atZone(zone).toLocalDate())
    )

    if (open != null) {
        LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(label(open), style = MaterialTheme.typography.titleLarge) }
            item { MarkdownText(open.bodyMd) }
            item { TextButton({ openId = null }) { Text(stringResource(R.string.reports_back)) } }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.reports_progress), style = MaterialTheme.typography.titleLarge)
            ProgressLine(rows)
        }
        if (reports.isEmpty()) item { Text(stringResource(R.string.reports_empty), style = MaterialTheme.typography.bodyMedium) }
        items(reports, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { openId = r.id }) {
                Text(label(r), Modifier.padding(16.dp).heightIn(min = 24.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            if (SettingsFreeze.isFrozen(ctx)) Text(stringResource(R.string.reports_frozen), style = MaterialTheme.typography.bodySmall)
            else TextButton(onOpenData) { Text(stringResource(R.string.reports_your_data)) }
        }
    }
}

/** The share of goals done on each finished day, as one line; a punishment day gets a ring. */
@Composable
private fun ProgressLine(rows: List<DayRow>) {
    val finished = rows.filter { it.totalCount > 0 && it.resolvedAt != null }
    if (finished.isEmpty()) {
        Text(stringResource(R.string.reports_progress_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.error
    val desc = stringResource(R.string.reports_progress_desc)
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 8.dp).semantics { contentDescription = desc }) {
        val n = finished.size
        fun point(i: Int, r: DayRow) = Offset(
            if (n == 1) size.width / 2 else size.width * i / (n - 1),
            size.height * (1f - r.doneCount.toFloat() / r.totalCount)
        )
        val path = Path()
        finished.forEachIndexed { i, r -> point(i, r).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        finished.forEachIndexed { i, r ->
            val p = point(i, r)
            drawCircle(if (r.kind == DayKind.PUNISHMENT) mark else line, radius = 5.dp.toPx(), center = p,
                style = if (r.kind == DayKind.PUNISHMENT) Stroke(2.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
        }
    }
}
