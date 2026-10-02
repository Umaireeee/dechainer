package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.report.PeriodKind
import io.github.warleysr.dechainer.report.PeriodMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The calendar of the monthly and yearly reports. */
class PeriodMathTest {
    private val karachi = ZoneId.of("Asia/Karachi")
    private val newYork = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, zone: ZoneId = karachi) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun keysAreCalendarMonthsAndYearsThatSortByDate() {
        assertEquals("2026-10", PeriodMath.key(PeriodKind.MONTH, LocalDate.of(2026, 10, 31)))
        assertEquals("2026", PeriodMath.key(PeriodKind.YEAR, LocalDate.of(2026, 10, 31)))
        assertTrue("2026-09" < "2026-10" && "2026-12" < "2027-01")
        assertEquals("2027-01", PeriodMath.next(PeriodKind.MONTH, "2026-12"))
        assertEquals(LocalDate.of(2026, 2, 28), PeriodMath.lastDate(PeriodKind.MONTH, "2026-02"))
        assertEquals(LocalDate.of(2028, 2, 29), PeriodMath.lastDate(PeriodKind.MONTH, "2028-02"))
        assertEquals(LocalDate.of(2026, 12, 31), PeriodMath.lastDate(PeriodKind.YEAR, "2026"))
    }

    @Test
    fun aMonthEndsAtTheLocalMidnightThatStartsTheNextOneAcrossAClockChange() {
        // New York falls back on 2026-11-01: November has one 25 hour day.
        val start = PeriodMath.start(PeriodKind.MONTH, "2026-11", newYork)
        val end = PeriodMath.end(PeriodKind.MONTH, "2026-11", newYork)
        assertEquals(30L * 24 * 3_600_000 + 3_600_000, end - start)
    }

    @Test
    fun aPeriodIsDueOnlyOnceItHasEnded() {
        assertFalse(PeriodMath.isDue(PeriodKind.MONTH, "2026-10", at(2026, 10, 31, 23), karachi))
        assertTrue(PeriodMath.isDue(PeriodKind.MONTH, "2026-10", at(2026, 11, 1, 0), karachi))
        assertFalse(PeriodMath.isDue(PeriodKind.YEAR, "2026", at(2026, 12, 31, 23), karachi))
        assertTrue(PeriodMath.isDue(PeriodKind.YEAR, "2026", at(2027, 1, 1, 0), karachi))
        assertFalse("a bad key is never due", PeriodMath.isDue(PeriodKind.MONTH, "2026-13", at(2030, 1, 1), karachi))
        assertFalse(PeriodMath.isValid(PeriodKind.MONTH, "2026-1"))
    }

    @Test
    fun dueListsEndedPeriodsFromTheAnchorsWithoutAReportAndCatchesUpOnlyALittle() {
        val anchor = LocalDate.of(2026, 7, 20)
        val now = at(2026, 10, 2)
        assertEquals(listOf("2026-08", "2026-09"), PeriodMath.due(PeriodKind.MONTH, anchor, now, karachi, emptySet()))
        assertEquals(listOf("2026-07", "2026-08"), PeriodMath.due(PeriodKind.MONTH, anchor, now, karachi, setOf("2026-09")))
        assertEquals("the anchor's own month counts", listOf("2026-07"), PeriodMath.due(PeriodKind.MONTH, anchor, now, karachi, setOf("2026-08", "2026-09")))
        assertEquals(emptyList<String>(), PeriodMath.due(PeriodKind.YEAR, anchor, now, karachi, emptySet()))
        assertEquals(listOf("2026"), PeriodMath.due(PeriodKind.YEAR, anchor, at(2027, 3, 1), karachi, emptySet()))
        assertEquals("one year at most", listOf("2027"), PeriodMath.due(PeriodKind.YEAR, anchor, at(2028, 3, 1), karachi, emptySet()))
    }

    @Test
    fun theCurrentPeriodFollowsTheZone() {
        // 2026-10-31 at 21:00 UTC is already November in Karachi (UTC+5).
        val t = ZonedDateTime.of(2026, 10, 31, 21, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-11", PeriodMath.current(PeriodKind.MONTH, t, karachi))
        assertEquals("2026-10", PeriodMath.current(PeriodKind.MONTH, t, ZoneId.of("UTC")))
    }
}
