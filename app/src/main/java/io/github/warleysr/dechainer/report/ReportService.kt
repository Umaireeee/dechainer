package io.github.warleysr.dechainer.report

import android.content.Context
import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiGate
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.MarkdownOutcome
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.StoredReport
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeFlow
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and saves weekly reports (blueprint 6.5). The report is saved first and only then announced.
 * Blocking: call it off the main thread. The AI call, the gate and the announcement are injectable so
 * the whole path is tested without a network.
 */
class ReportService(
    private val ctx: Context,
    private val calls: AiCalls = AiCalls(),
    private val gate: () -> AiGateResult = {
        val s = AiSettings(ctx)
        AiGate.check(s.configured, s.consent, UrgeFlow.isOnline(ctx))
    },
    private val config: () -> AiConfig = { AiSettings(ctx).toConfig() },
    private val announce: (StoredReport) -> Unit = { ReportNotifier.post(ctx, it) }
) {
    private val reports = Store.reports(ctx)

    /**
     * Week 0 starts on the local day of the first data point. Once found it is stored and never moves
     * (it survives "wipe all data"). Null while there is no data point yet.
     */
    fun anchor(zone: ZoneId): LocalDate? {
        val state = Store.appState(ctx)
        state.get(AppStateKeys.WEEK_ANCHOR)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull()?.let { return it } }
        val first = listOfNotNull(
            Store.urgeEntries(ctx).firstCreatedAt(),
            Store.focus(ctx).allCheckins().minOfOrNull { it.at },
            Store.days(ctx).firstPlanAt()
        ).minOrNull() ?: return null
        val anchor = WeekMath.anchorDate(first, zone)
        state.set(AppStateKeys.WEEK_ANCHOR, anchor.toString())
        return anchor
    }

    /** The urges, sessions and days between two local dates, both inclusive. Derived data only. */
    fun loadRange(first: LocalDate, last: LocalDate, zone: ZoneId): ReportData {
        val from = DayWindow.startOf(first, zone)
        val to = DayWindow.endOf(last, zone)

        val urges = Store.urgeEntries(ctx).between(from, to).map { ReportInputs.urgeFact(it, zone) }

        val checkins = Store.focus(ctx).allCheckins().groupBy { it.sessionId }
        val sessions = Store.focus(ctx).sessions().filter { it.startedAt in from until to }.map { s ->
            FocusFact(
                date = DayWindow.dateOf(s.startedAt, zone), minutes = s.focusedMinutes, purpose = s.purpose,
                flavor = s.flavor, answers = checkins[s.id].orEmpty().map { it.answer }
            )
        }

        val days = Store.days(ctx)
        val dayRows = days.daysBetween(first, last).associateBy { it.date }
        val dayFacts = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.mapNotNull { date ->
            val row = dayRows[date]
            val goals = days.goals(date)
            if (row == null && goals.isEmpty()) null
            else DayFact(
                date = date,
                kind = row?.kind ?: io.github.warleysr.dechainer.day.DayKind.NORMAL,
                goals = goals.map { GoalFact(it.text, it.type, it.targetMinutes, it.state) },
                violation = row?.violation,
                focusMinutes = sessions.filter { it.date == date }.sumOf { it.minutes },
                recordedDone = row?.takeIf { it.evaluated || it.resolvedAt != null }?.doneCount,
                recordedTotal = row?.takeIf { it.evaluated || it.resolvedAt != null }?.totalCount
            )
        }.toList()
        return ReportData(first, last, urges, sessions, dayFacts, emptyList())
    }

    /** Everything period [k] is made from. Derived data only: see [ReportData]. */
    fun load(anchor: LocalDate, k: Int, zone: ZoneId): ReportData {
        val first = WeekMath.startDate(anchor, k)
        val last = WeekMath.lastDate(anchor, k)
        val range = loadRange(first, last, zone)

        val past = reports.before(k, 4).mapNotNull { r ->
            val summary = ReportInputs.summaryFromJson(r.summaryJson) ?: return@mapNotNull null
            PastSummary(
                firstDate = Instant.ofEpochMilli(r.periodStart).atZone(zone).toLocalDate(),
                lastDate = Instant.ofEpochMilli(r.periodEnd - 1).atZone(zone).toLocalDate(),
                summary = summary
            )
        }
        return range.copy(past = past)
    }

    /** The ended periods that still need a report and have a data point, oldest first. */
    fun dueWithData(now: Long, zone: ZoneId): List<Int> {
        val anchor = anchor(zone) ?: return emptyList()
        return WeekMath.due(anchor, now, zone, reports.reportedPeriods()).filter { load(anchor, it, zone).hasDataPoint }
    }

    /** One attempt to build the report for period [k]. */
    fun generate(k: Int, now: Long, zone: ZoneId): ReportOutcome {
        val anchor = anchor(zone) ?: return ReportOutcome.NO_DATA
        if (!WeekMath.isDue(anchor, k, now, zone)) return ReportOutcome.NOT_DUE
        if (k in reports.reportedPeriods()) return ReportOutcome.DONE
        val data = load(anchor, k, zone)
        if (!data.hasDataPoint) return ReportOutcome.NO_DATA
        ReportRun.forGate(gate())?.let { return it }

        if (!running.add(k)) return ReportOutcome.RETRY // another run is building this week
        try {
            val request = ReportInputs.build(data)
            return when (val out = calls.weeklyReport(config(), request)) {
                is MarkdownOutcome.Failed -> ReportRun.forFailure(out.error)
                is MarkdownOutcome.Ok -> {
                    val summary = ReportInputs.summaryToJson(ReportInputs.summary(data, ReportInputs.adviceOf(out.markdown)))
                    val saved = reports.insert(
                        k, WeekMath.periodStart(anchor, k, zone), WeekMath.periodEnd(anchor, k, zone),
                        now, out.markdown, summary
                    )
                    // Saved first; only a report this run really saved is announced.
                    if (saved) reports.all().firstOrNull { it.periodIndex == k }?.let {
                        try { announce(it) } catch (e: Exception) { Timber.w(e, "Report announcement failed") }
                    }
                    ReportOutcome.DONE
                }
            }
        } finally {
            running.remove(k)
        }
    }

    private companion object {
        val running: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }
}
