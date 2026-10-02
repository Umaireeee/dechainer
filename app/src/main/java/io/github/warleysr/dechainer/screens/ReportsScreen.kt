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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.report.DayCount
import io.github.warleysr.dechainer.report.Granularity
import io.github.warleysr.dechainer.report.PeriodInputs
import io.github.warleysr.dechainer.report.PeriodKind
import io.github.warleysr.dechainer.report.ProgressPoint
import io.github.warleysr.dechainer.report.ProgressSeries
import io.github.warleysr.dechainer.screens.report.ProgressSection
import io.github.warleysr.dechainer.screens.urge.MarkdownText
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.ui.theme.CalmCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Which list of reports is showing. */
private enum class ReportTab { WEEK, MONTH, YEAR }

/** One report in a list, whatever its kind. */
private data class ReportItem(val tab: ReportTab, val id: Long, val title: String, val body: String)

/**
 * Reports (blueprint 6.5, 1.2): the progress graphs, then the weekly, monthly and yearly reports.
 * Everything is read off the main thread. Deleting and the backup are on [DataScreen].
 */
@Composable
fun ReportsScreen(openReportId: Long?, openPeriodReportId: Long?, onOpenData: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val zone = TrustedClock.zone()
    var granularity by rememberSaveable { mutableStateOf(Granularity.WEEK) }
    var tab by rememberSaveable { mutableStateOf(ReportTab.WEEK) }
    var openKey by remember(openReportId, openPeriodReportId) {
        mutableStateOf(
            when {
                openPeriodReportId != null -> "P$openPeriodReportId"
                openReportId != null -> "W$openReportId"
                else -> null
            }
        )
    }

    val points by produceState<List<ProgressPoint>?>(null, granularity) {
        value = withContext(Dispatchers.IO) { runCatching { loadProgress(ctx, granularity, zone) }.getOrDefault(emptyList()) }
    }
    val backupDue by produceState(false) {
        value = withContext(Dispatchers.IO) { io.github.warleysr.dechainer.report.BackupReminder.isDue(ctx) }
    }
    val items by produceState<List<ReportItem>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { loadReports(ctx, zone) }.getOrDefault(emptyList()) }
    }

    val open = items?.firstOrNull { (if (it.tab == ReportTab.WEEK) "W" else "P") + it.id == openKey }
    if (open != null) {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Spacer(Modifier.height(8.dp)); Text(open.title, style = MaterialTheme.typography.headlineSmall) }
            item { MarkdownText(open.body) }
            item { TextButton({ openKey = null }) { Text(stringResource(R.string.reports_back)) } }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (backupDue) {
            item {
                Spacer(Modifier.height(8.dp))
                CalmCard(Modifier.fillMaxWidth(), highlighted = true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_reminder), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onOpenData) { Text(stringResource(R.string.backup_reminder_action)) }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reports_progress), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            CalmCard(Modifier.fillMaxWidth()) {
                val p = points
                if (p == null) {
                    Text(stringResource(R.string.reports_loading), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                } else {
                    ProgressSection(p, granularity, { granularity = it }, Modifier.padding(16.dp))
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reports_list), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportTab.entries.forEach { t ->
                    FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(stringResource(tabLabel(t))) })
                }
            }
        }
        val shown = items.orEmpty().filter { it.tab == tab }
        if (items != null && shown.isEmpty()) {
            item {
                Text(
                    stringResource(emptyText(tab)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(shown, key = { "${it.tab}${it.id}" }) { r ->
            CalmCard(Modifier.fillMaxWidth().clickable { openKey = (if (r.tab == ReportTab.WEEK) "W" else "P") + r.id }) {
                Column(Modifier.padding(16.dp).heightIn(min = 24.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item { TextButton(onOpenData) { Text(stringResource(R.string.reports_your_data)) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun tabLabel(t: ReportTab): Int = when (t) {
    ReportTab.WEEK -> R.string.reports_weekly
    ReportTab.MONTH -> R.string.reports_monthly
    ReportTab.YEAR -> R.string.reports_yearly
}

private fun emptyText(t: ReportTab): Int = when (t) {
    ReportTab.WEEK -> R.string.reports_empty
    ReportTab.MONTH -> R.string.reports_empty_month
    ReportTab.YEAR -> R.string.reports_empty_year
}

/** The numbers for the graphs: urges, sessions and day results from the first date [g] needs. */
private fun loadProgress(ctx: android.content.Context, g: Granularity, zone: ZoneId): List<ProgressPoint> {
    val today = DayWindow.dateOf(TrustedClock.now(ctx), zone)
    val first = ProgressSeries.firstDate(g, today)
    val from = DayWindow.startOf(first, zone)
    val to = DayWindow.endOf(today, zone)
    val urges = Store.urgeEntries(ctx).between(from, to).map { DayWindow.dateOf(it.createdAt, zone) to it.kind }
    val focus = Store.focus(ctx).sessions().filter { it.startedAt in from until to }
        .map { DayWindow.dateOf(it.startedAt, zone) to it.focusedMinutes }
    val days = Store.days(ctx).daysBetween(first, today)
        .filter { it.resolvedAt != null && it.totalCount > 0 }
        .map { DayCount(it.date, it.doneCount, it.totalCount, it.violation != null) }
    return ProgressSeries.build(g, today, urges, focus, days)
}

private fun loadReports(ctx: android.content.Context, zone: ZoneId): List<ReportItem> {
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun date(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    val weekly = Store.reports(ctx).all().filter { !it.deleted }.map {
        ReportItem(ReportTab.WEEK, it.id, ctx.getString(R.string.reports_week, date(it.periodStart), date(it.periodEnd - 1)), it.bodyMd)
    }
    val periods = Store.periodReports(ctx)
    val monthly = periods.all(PeriodKind.MONTH).filter { !it.deleted }.map {
        ReportItem(ReportTab.MONTH, it.id, PeriodInputs.label(PeriodKind.MONTH, it.key), it.bodyMd)
    }
    val yearly = periods.all(PeriodKind.YEAR).filter { !it.deleted }.map {
        ReportItem(ReportTab.YEAR, it.id, ctx.getString(R.string.reports_year_title, it.key), it.bodyMd)
    }
    return weekly + monthly + yearly
}
