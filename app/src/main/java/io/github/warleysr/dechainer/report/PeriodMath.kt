package io.github.warleysr.dechainer.report

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The longer reports: one per calendar month and one per calendar year. */
enum class PeriodKind {
    MONTH,
    YEAR;

    /** How many ended periods with no report are built after a long gap: never a flood. */
    val maxCatchUp: Int get() = if (this == MONTH) 2 else 1
}

/**
 * The calendar of the monthly and yearly reports. Pure: `now` and the zone are always passed in.
 * Periods are calendar months and years in the zone in force, keyed `2026-10` and `2026`, so the keys
 * sort by date. The first period is the one holding the anchor (the day of the first data point), even
 * when it began before it: that month or year is still the owner's first.
 */
object PeriodMath {
    fun key(kind: PeriodKind, date: LocalDate): String = when (kind) {
        PeriodKind.MONTH -> YearMonth.from(date).toString()
        PeriodKind.YEAR -> date.year.toString()
    }

    /** The first local date of the period [key]. */
    fun firstDate(kind: PeriodKind, key: String): LocalDate = when (kind) {
        PeriodKind.MONTH -> YearMonth.parse(key).atDay(1)
        PeriodKind.YEAR -> LocalDate.of(key.toInt(), 1, 1)
    }

    /** The last local date of the period [key]. */
    fun lastDate(kind: PeriodKind, key: String): LocalDate = next(kind, key).let { firstDate(kind, it) }.minusDays(1)

    /** The key of the period after [key]. */
    fun next(kind: PeriodKind, key: String): String = when (kind) {
        PeriodKind.MONTH -> YearMonth.parse(key).plusMonths(1).toString()
        PeriodKind.YEAR -> (key.toInt() + 1).toString()
    }

    fun start(kind: PeriodKind, key: String, zone: ZoneId): Long = firstDate(kind, key).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The exclusive end: the first moment of the next period. */
    fun end(kind: PeriodKind, key: String, zone: ZoneId): Long = start(kind, next(kind, key), zone)

    /** Whether [key] parses as a period of [kind]. */
    fun isValid(kind: PeriodKind, key: String): Boolean = runCatching { firstDate(kind, key) }.isSuccess &&
        key(kind, firstDate(kind, key)) == key

    fun isDue(kind: PeriodKind, key: String, now: Long, zone: ZoneId): Boolean = isValid(kind, key) && end(kind, key, zone) <= now

    /** The period [now] is in. */
    fun current(kind: PeriodKind, now: Long, zone: ZoneId): String = key(kind, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())

    /**
     * Ended periods from the one holding [anchor] that have no report row yet ([reported]), oldest first,
     * the newest [PeriodKind.maxCatchUp] only.
     */
    fun due(kind: PeriodKind, anchor: LocalDate, now: Long, zone: ZoneId, reported: Set<String>): List<String> {
        val current = current(kind, now, zone)
        val out = mutableListOf<String>()
        var k = key(kind, anchor)
        while (k < current) {
            if (k !in reported) out += k
            k = next(kind, k)
        }
        return out.takeLast(kind.maxCatchUp)
    }
}
