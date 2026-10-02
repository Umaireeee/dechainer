package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.urge.UrgeKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** How the progress graphs group time. */
enum class Granularity(val count: Int) {
    DAY(28),
    WEEK(12),
    MONTH(12)
}

/** One bucket of the progress graphs: a day, a week (Monday to Sunday) or a calendar month. */
data class ProgressPoint(
    val first: LocalDate,
    val last: LocalDate,
    val urges: Int,
    val slips: Int,
    val focusMinutes: Int,
    val goalsDone: Int,
    val goalsTotal: Int,
    /** Finished days that fell short of their plan. */
    val daysShort: Int
) {
    /** The share of goals done, 0 to 100, or null when no goal was planned. */
    val percentDone: Int? get() = ReportPatterns.percent(goalsDone, goalsTotal)
}

/** One day's goal counts and result, as the graphs need them. */
data class DayCount(val date: LocalDate, val done: Int, val total: Int, val short: Boolean)

/**
 * The numbers behind the progress graphs (Reports). Pure: the caller passes in what is stored and
 * today's date. Counts are by local date. The newest bucket is the one holding [today], so it is
 * still filling up; the screen says so.
 */
object ProgressSeries {
    fun bucketStart(g: Granularity, date: LocalDate): LocalDate = when (g) {
        Granularity.DAY -> date
        Granularity.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Granularity.MONTH -> YearMonth.from(date).atDay(1)
    }

    private fun bucketEnd(g: Granularity, start: LocalDate): LocalDate = when (g) {
        Granularity.DAY -> start
        Granularity.WEEK -> start.plusDays(6)
        Granularity.MONTH -> YearMonth.from(start).atEndOfMonth()
    }

    private fun previous(g: Granularity, start: LocalDate): LocalDate = when (g) {
        Granularity.DAY -> start.minusDays(1)
        Granularity.WEEK -> start.minusWeeks(1)
        Granularity.MONTH -> start.minusMonths(1)
    }

    /** The first date the graphs for [g] need data from. */
    fun firstDate(g: Granularity, today: LocalDate): LocalDate {
        var start = bucketStart(g, today)
        repeat(g.count - 1) { start = previous(g, start) }
        return start
    }

    /**
     * The last [Granularity.count] buckets up to the one holding [today], oldest first.
     * [urges] are each entry's local date and kind; [focus] each session's local date and minutes.
     */
    fun build(
        g: Granularity,
        today: LocalDate,
        urges: List<Pair<LocalDate, UrgeKind>>,
        focus: List<Pair<LocalDate, Int>>,
        days: List<DayCount>
    ): List<ProgressPoint> {
        val starts = ArrayDeque<LocalDate>()
        var start = bucketStart(g, today)
        repeat(g.count) { starts.addFirst(start); start = previous(g, start) }
        val urgeBy = urges.groupBy { bucketStart(g, it.first) }
        val focusBy = focus.groupBy { bucketStart(g, it.first) }
        val dayBy = days.groupBy { bucketStart(g, it.date) }
        return starts.map { s ->
            val u = urgeBy[s].orEmpty()
            val d = dayBy[s].orEmpty()
            ProgressPoint(
                first = s,
                last = bucketEnd(g, s),
                urges = u.count { it.second == UrgeKind.URGE },
                slips = u.count { it.second == UrgeKind.SLIP },
                focusMinutes = focusBy[s].orEmpty().sumOf { it.second },
                goalsDone = d.sumOf { it.done },
                goalsTotal = d.sumOf { it.total },
                daysShort = d.count { it.short }
            )
        }
    }

    /** A rounded-up top for a bar scale: never below [floor], so one small count does not fill the chart. */
    fun niceMax(max: Int, floor: Int = 4): Int {
        val m = maxOf(max, floor)
        val step = when {
            m <= 10 -> 2
            m <= 50 -> 5
            m <= 200 -> 20
            m <= 1000 -> 100
            else -> 500
        }
        return (m + step - 1) / step * step
    }
}
