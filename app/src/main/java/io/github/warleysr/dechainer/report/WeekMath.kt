package io.github.warleysr.dechainer.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The weekly report's calendar (blueprint 6.5). Pure: `now` and the zone are always passed in.
 *
 * The anchor is a local calendar date (the day of the first data point). Period k is the seven
 * calendar days `[anchor + 7k, anchor + 7k + 7)`, start of day to start of day in [zone]. Counting in
 * dates rather than in milliseconds keeps a period seven days long across a daylight-saving change,
 * month ends and a change of time zone (the boundaries are simply recomputed in the zone in force).
 */
object WeekMath {
    const val PERIOD_DAYS = 7L

    /** A phone that was off for months must not queue a flood of reports: only the newest few are built. */
    const val MAX_CATCH_UP = 4

    /** The anchor for a first data point at [firstDataAt]: the start of that local day. */
    fun anchorDate(firstDataAt: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(firstDataAt).atZone(zone).toLocalDate()

    fun startDate(anchor: LocalDate, k: Int): LocalDate = anchor.plusDays(PERIOD_DAYS * k)

    /** First moment of period [k], in epoch millis. */
    fun periodStart(anchor: LocalDate, k: Int, zone: ZoneId): Long = startDate(anchor, k).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The exclusive end of period [k]: the first moment of period k + 1. */
    fun periodEnd(anchor: LocalDate, k: Int, zone: ZoneId): Long = periodStart(anchor, k + 1, zone)

    /** The last local date inside period [k]. */
    fun lastDate(anchor: LocalDate, k: Int): LocalDate = startDate(anchor, k + 1).minusDays(1)

    /** The period an instant falls in; negative for an instant before the anchor day. */
    fun periodOf(anchor: LocalDate, at: Long, zone: ZoneId): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(anchor, Instant.ofEpochMilli(at).atZone(zone).toLocalDate()), PERIOD_DAYS).toInt()

    /** The period a local date falls in. */
    fun periodOf(anchor: LocalDate, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), PERIOD_DAYS).toInt()

    /** The newest period that has ended by [now], or -1 when none has. */
    fun lastEnded(anchor: LocalDate, now: Long, zone: ZoneId): Int = periodOf(anchor, now, zone) - 1

    /** A period is due once its end has passed. */
    fun isDue(anchor: LocalDate, k: Int, now: Long, zone: ZoneId): Boolean = k >= 0 && periodEnd(anchor, k, zone) <= now

    /**
     * Ended periods that have no report yet, oldest first, newest [MAX_CATCH_UP] only. [reported] are
     * the periods that already have a report row (a deleted report keeps its row, so it is not built again).
     */
    fun due(anchor: LocalDate, now: Long, zone: ZoneId, reported: Set<Int>): List<Int> =
        (0..lastEnded(anchor, now, zone)).filter { it !in reported }.takeLast(MAX_CATCH_UP)
}
