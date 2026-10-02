package io.github.warleysr.dechainer.report

import android.content.Context
import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiGate
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.MarkdownOutcome
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.StoredPeriodReport
import io.github.warleysr.dechainer.urge.UrgeFlow
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and saves the monthly and yearly reports, the way [ReportService] does the weekly one: the
 * report is saved first and only then announced, and a period is built once. Blocking: call it off the
 * main thread. The AI call, the gate and the announcement are injectable so it is tested without a network.
 */
class PeriodReportService(
    private val ctx: Context,
    private val calls: AiCalls = AiCalls(),
    private val gate: () -> AiGateResult = {
        val s = AiSettings(ctx)
        AiGate.check(s.configured, s.consent, UrgeFlow.isOnline(ctx))
    },
    private val config: () -> AiConfig = { AiSettings(ctx).toConfig() },
    private val announce: (StoredPeriodReport) -> Unit = { ReportNotifier.postPeriod(ctx, it) }
) {
    private val weekly = ReportService(ctx)
    private val reports = Store.periodReports(ctx)

    /** Everything the period [key] of [kind] is made from. Derived data only: see [PeriodFacts]. */
    fun load(kind: PeriodKind, key: String, zone: ZoneId): PeriodFacts {
        val first = PeriodMath.firstDate(kind, key)
        val last = PeriodMath.lastDate(kind, key)
        val range = weekly.loadRange(first, last, zone)
        val start = PeriodMath.start(kind, key, zone)
        val end = PeriodMath.end(kind, key, zone)
        val weekFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        val inner = when (kind) {
            // The weekly reports that began in this month.
            PeriodKind.MONTH -> Store.reports(ctx).all().filter { !it.deleted && it.periodStart in start until end }
                .sortedBy { it.periodStart }
                .mapNotNull { r ->
                    val s = ReportInputs.summaryFromJson(r.summaryJson) ?: return@mapNotNull null
                    InnerReport("week of " + weekFmt.format(Instant.ofEpochMilli(r.periodStart).atZone(zone).toLocalDate()), s)
                }
            // The monthly reports of this year.
            PeriodKind.YEAR -> reports.all(PeriodKind.MONTH).filter { !it.deleted && it.key.startsWith("$key-") }
                .sortedBy { it.key }
                .mapNotNull { r -> ReportInputs.summaryFromJson(r.summaryJson)?.let { InnerReport(r.key, it) } }
        }
        val pastLimit = if (kind == PeriodKind.MONTH) PeriodInputs.MAX_PAST_MONTHS else PeriodInputs.MAX_PAST_YEARS
        val past = reports.before(kind, key, pastLimit)
            .mapNotNull { r -> ReportInputs.summaryFromJson(r.summaryJson)?.let { InnerReport(r.key, it) } }

        return PeriodFacts(kind, key, first, last, range.urges, range.sessions, range.days, inner, past)
    }

    /** The ended periods of [kind] that still need a report and have a data point, oldest first. */
    fun dueWithData(kind: PeriodKind, now: Long, zone: ZoneId): List<String> {
        val anchor = weekly.anchor(zone) ?: return emptyList()
        return PeriodMath.due(kind, anchor, now, zone, reports.reportedKeys(kind)).filter { load(kind, it, zone).hasDataPoint }
    }

    /** One attempt to build the report for the period [key] of [kind]. */
    fun generate(kind: PeriodKind, key: String, now: Long, zone: ZoneId): ReportOutcome {
        if (weekly.anchor(zone) == null) return ReportOutcome.NO_DATA
        if (!PeriodMath.isDue(kind, key, now, zone)) return ReportOutcome.NOT_DUE
        if (key in reports.reportedKeys(kind)) return ReportOutcome.DONE
        val facts = load(kind, key, zone)
        if (!facts.hasDataPoint) return ReportOutcome.NO_DATA
        ReportRun.forGate(gate())?.let { return it }

        val id = "$kind/$key"
        if (!running.add(id)) return ReportOutcome.RETRY // another run is building this period
        try {
            val request = PeriodInputs.build(facts)
            val out = when (kind) {
                PeriodKind.MONTH -> calls.monthlyReport(config(), request)
                PeriodKind.YEAR -> calls.yearlyReport(config(), request)
            }
            return when (out) {
                is MarkdownOutcome.Failed -> ReportRun.forFailure(out.error)
                is MarkdownOutcome.Ok -> {
                    val summary = ReportInputs.summaryToJson(PeriodInputs.summary(facts, out.markdown))
                    val saved = reports.insert(
                        kind, key, PeriodMath.start(kind, key, zone), PeriodMath.end(kind, key, zone), now, out.markdown, summary
                    )
                    // Saved first; only a report this run really saved is announced.
                    if (saved) reports.all(kind).firstOrNull { it.key == key }?.let {
                        try { announce(it) } catch (e: Exception) { Timber.w(e, "Report announcement failed") }
                    }
                    ReportOutcome.DONE
                }
            }
        } finally {
            running.remove(id)
        }
    }

    private companion object {
        val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
