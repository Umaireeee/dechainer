package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.ai.DeepDiveHistory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** A shorter report inside the period (or an earlier period), in its own short form. */
data class InnerReport(val label: String, val summary: ReportSummary)

/**
 * What a monthly or yearly report is made from. Like [ReportData], it has no field for a raw urge
 * note: urges come in as [UrgeFact]s, which drop it.
 */
data class PeriodFacts(
    val kind: PeriodKind,
    val key: String,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val urges: List<UrgeFact>,
    val sessions: List<FocusFact>,
    val days: List<DayFact>,
    /** The weekly reports inside a month, or the monthly reports inside a year, oldest first. */
    val inner: List<InnerReport>,
    /** The same kind of report for the periods before, oldest first. */
    val past: List<InnerReport>
) {
    /** A data point, as for a week: an urge or slip, a focus check-in or a goal. */
    val hasDataPoint: Boolean get() = asWeek().hasDataPoint

    /** The whole period as one [ReportData], for the shared totals. */
    fun asWeek(): ReportData = ReportData(firstDate, lastDate, urges, sessions, days, emptyList())
}

/** The monthly and yearly reports' input builder: derived data only, as plain text for the model. Pure. */
object PeriodInputs {
    const val MAX_LINKS = 40
    const val MAX_PAST_MONTHS = 3
    const val MAX_PAST_YEARS = 2

    /** The rows of the period: weeks (Monday to Sunday, cut at the month's edges) for a month, months for a year. */
    fun rows(kind: PeriodKind, first: LocalDate, last: LocalDate): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var start = first
        while (!start.isAfter(last)) {
            val end = when (kind) {
                PeriodKind.MONTH -> minOf(start.plusDays((DayOfWeek.SUNDAY.value - start.dayOfWeek.value).toLong()), last)
                PeriodKind.YEAR -> minOf(YearMonth.from(start).atEndOfMonth(), last)
            }
            out += start to end
            start = end.plusDays(1)
        }
        return out
    }

    /** The facts of [f] between two dates, both inclusive, as one [ReportData]. */
    fun slice(f: PeriodFacts, from: LocalDate, to: LocalDate): ReportData = ReportData(
        from, to,
        f.urges.filter { !it.at.toLocalDate().isBefore(from) && !it.at.toLocalDate().isAfter(to) },
        f.sessions.filter { !it.date.isBefore(from) && !it.date.isAfter(to) },
        f.days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) },
        emptyList()
    )

    fun label(kind: PeriodKind, key: String): String = when (kind) {
        PeriodKind.MONTH -> YearMonth.parse(key).let { "${it.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${it.year}" }
        PeriodKind.YEAR -> key
    }

    private fun summaryLine(s: ReportSummary): String =
        "urges ${s.urges}, slips ${s.slips}, focus ${s.focusMinutes} min in ${s.focusSessions} sessions, " +
            "check-in yes ${s.checkinYes} no ${s.checkinNo}, goals ${s.goalsDone}/${s.goalsTotal}, days short of plan ${s.daysMissed}"

    /** The text between the model's `<month_data>` or `<year_data>` tags. */
    fun build(f: PeriodFacts): String = buildString {
        val whole = f.asWeek()
        val s = ReportInputs.summary(whole)
        appendLine("Period: ${label(f.kind, f.key)} (${f.firstDate} to ${f.lastDate}, local dates).")
        appendLine()
        appendLine("TOTALS")
        appendLine("urges: ${s.urges}; slips: ${s.slips}")
        appendLine("focus sessions: ${s.focusSessions}; focused minutes: ${s.focusMinutes}; check-in yes: ${s.checkinYes}; check-in no: ${s.checkinNo}")
        appendLine("checklist goals done: ${s.goalsDone} of ${s.goalsTotal}; days that fell short of the plan: ${s.daysMissed}")
        appendLine(ReportPatterns.ratesLine(s))
        appendLine(ReportPatterns.daysLine(f.days))

        appendLine()
        appendLine(if (f.kind == PeriodKind.MONTH) "WEEK BY WEEK" else "MONTH BY MONTH")
        rows(f.kind, f.firstDate, f.lastDate).forEach { (from, to) ->
            val name = if (f.kind == PeriodKind.MONTH) "$from to $to" else YearMonth.from(from).toString()
            appendLine("- $name: ${summaryLine(ReportInputs.summary(slice(f, from, to)))}")
        }

        appendLine()
        appendLine("PATTERNS")
        ReportPatterns.lines(f.urges, f.days).forEach { appendLine(it) }

        if (f.kind == PeriodKind.MONTH) {
            appendLine()
            appendLine("EARLIEST LINKS FOUND BY THE DEEP DIVES (oldest first)")
            val links = f.urges.sortedBy { it.at }.mapNotNull { u ->
                val dive = u.deepDive ?: return@mapNotNull null
                val link = earliestLink(dive) ?: return@mapNotNull null
                "- ${u.at.toLocalDate()} %02d:%02d ${u.kind.name.lowercase()}: $link".format(u.at.hour, u.at.minute)
            }
            if (links.isEmpty()) appendLine("none")
            links.takeLast(MAX_LINKS).forEach { appendLine(it) }
        }

        appendLine()
        appendLine(if (f.kind == PeriodKind.MONTH) "THE WEEKLY REPORTS OF THIS MONTH (oldest first)" else "THE MONTHLY REPORTS OF THIS YEAR (oldest first)")
        if (f.inner.isEmpty()) appendLine("none")
        f.inner.forEach { r ->
            appendLine("- ${r.label}: ${summaryLine(r.summary)}")
            if (r.summary.advice.isNotBlank()) appendLine("  advice given then: ${clip(r.summary.advice, ReportInputs.MAX_ADVICE)}")
        }

        appendLine()
        appendLine(if (f.kind == PeriodKind.MONTH) "EARLIER MONTHS (oldest first)" else "EARLIER YEARS (oldest first)")
        if (f.past.isEmpty()) appendLine("none")
        f.past.forEach { r ->
            appendLine("- ${r.label}: ${summaryLine(r.summary)}")
            if (r.summary.advice.isNotBlank()) appendLine("  advice given then: ${clip(r.summary.advice, ReportInputs.MAX_ADVICE)}")
        }
    }.trimEnd()

    /** The "earliest link" of a deep dive, clipped, or null when it has none. */
    fun earliestLink(deepDive: String): String? {
        val sections = DeepDiveHistory.sections(deepDive)
        val named = sections.firstOrNull { it.first.contains("earliest link", ignoreCase = true) }?.second
        val body = named ?: sections.takeIf { it.size >= 4 }?.get(1)?.second
        return body?.let { clip(it, DeepDiveHistory.MAX_LINE) }?.ifBlank { null }
    }

    /** The summary stored with a monthly or yearly report: its numbers and the advice it gave. */
    fun summary(f: PeriodFacts, markdown: String): ReportSummary =
        ReportInputs.summary(f.asWeek(), ReportInputs.adviceOf(markdown, if (f.kind == PeriodKind.MONTH) "next month" else "next year"))

    private fun clip(text: String, max: Int) = text.trim().replace(Regex("\\s+"), " ").take(max)
}
