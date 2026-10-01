package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.report.WeekMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeekMathTest {
    private val london = ZoneId.of("Europe/London")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private fun ms(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, s: Int = 0) =
        LocalDate.of(y, m, d).atTime(h, min, s).atZone(zone).toInstant().toEpochMilli()

    @Test fun anchorIsTheLocalDayOfTheFirstDataPoint() {
        assertEquals(LocalDate.of(2026, 10, 5), WeekMath.anchorDate(ms(london, 2026, 10, 5, 23, 59, 59), london))
        assertEquals(LocalDate.of(2026, 10, 6), WeekMath.anchorDate(ms(london, 2026, 10, 6, 0, 0, 0), london))
    }

    @Test fun periodsAreSevenCalendarDaysFromTheAnchor() {
        val a = LocalDate.of(2026, 10, 5)
        assertEquals(LocalDate.of(2026, 10, 5), WeekMath.startDate(a, 0))
        assertEquals(LocalDate.of(2026, 10, 11), WeekMath.lastDate(a, 0))
        assertEquals(ms(london, 2026, 10, 12), WeekMath.periodEnd(a, 0, london))
        assertEquals(WeekMath.periodStart(a, 1, london), WeekMath.periodEnd(a, 0, london))
    }

    @Test fun theLastSecondBelongsToTheOldPeriodAndMidnightToTheNext() {
        val a = LocalDate.of(2026, 10, 5)
        assertEquals(0, WeekMath.periodOf(a, ms(london, 2026, 10, 11, 23, 59, 59), london))
        assertEquals(1, WeekMath.periodOf(a, ms(london, 2026, 10, 12, 0, 0, 0), london))
        assertEquals(0, WeekMath.periodOf(a, ms(london, 2026, 10, 5, 0, 0, 0), london))
        assertEquals(-1, WeekMath.periodOf(a, ms(london, 2026, 10, 4, 23, 59, 59), london))
    }

    @Test fun aPeriodIsDueAtItsEndAndNotBefore() {
        val a = LocalDate.of(2026, 10, 5)
        assertFalse(WeekMath.isDue(a, 0, ms(london, 2026, 10, 11, 23, 59, 59), london))
        assertTrue(WeekMath.isDue(a, 0, ms(london, 2026, 10, 12, 0, 0, 0), london))
        assertFalse(WeekMath.isDue(a, -1, ms(london, 2026, 10, 12), london))
        assertEquals(-1, WeekMath.lastEnded(a, ms(london, 2026, 10, 8), london))
        assertEquals(0, WeekMath.lastEnded(a, ms(london, 2026, 10, 12), london))
    }

    @Test fun monthEndsAndYearEndsDoNotShiftAPeriod() {
        val a = LocalDate.of(2026, 12, 28)
        assertEquals(LocalDate.of(2027, 1, 3), WeekMath.lastDate(a, 0))
        assertEquals(LocalDate.of(2027, 1, 4), WeekMath.startDate(a, 1))
        assertEquals(1, WeekMath.periodOf(a, LocalDate.of(2027, 1, 4)))
        val leap = LocalDate.of(2028, 2, 24)
        assertEquals(LocalDate.of(2028, 3, 1), WeekMath.lastDate(leap, 0)) // 2028 has a 29 February
    }

    @Test fun aDaylightSavingChangeKeepsAPeriodSevenDatesLong() {
        // Europe/London springs forward on 2026-03-29: that Sunday has 23 hours.
        val a = LocalDate.of(2026, 3, 23)
        assertEquals(ms(london, 2026, 3, 30), WeekMath.periodEnd(a, 0, london))
        assertEquals(0, WeekMath.periodOf(a, ms(london, 2026, 3, 29, 23, 59, 59), london))
        assertEquals(1, WeekMath.periodOf(a, ms(london, 2026, 3, 30, 0, 0, 0), london))
    }

    @Test fun aChangeOfTimeZoneRecomputesTheBoundariesWithoutGapOrOverlap() {
        val a = LocalDate.of(2026, 10, 5)
        for (zone in listOf(london, tokyo)) {
            for (k in 0..3) assertEquals(WeekMath.periodStart(a, k + 1, zone), WeekMath.periodEnd(a, k, zone))
        }
        // The same instant can be in different periods in different zones, but each zone is consistent with itself.
        val instant = ms(london, 2026, 10, 11, 20, 0) // Sunday evening in London, already Monday in Tokyo
        assertEquals(0, WeekMath.periodOf(a, instant, london))
        assertEquals(1, WeekMath.periodOf(a, instant, tokyo))
        for (zone in listOf(london, tokyo)) {
            val k = WeekMath.periodOf(a, instant, zone)
            assertTrue(instant >= WeekMath.periodStart(a, k, zone) && instant < WeekMath.periodEnd(a, k, zone))
        }
    }

    @Test fun dueListsEndedPeriodsWithoutAReportOldestFirst() {
        val a = LocalDate.of(2026, 10, 5)
        val now = ms(london, 2026, 10, 27) // periods 0, 1 and 2 have ended (the third ends on the 26th)
        assertEquals(listOf(0, 1, 2), WeekMath.due(a, now, london, emptySet()))
        assertEquals(listOf(0, 2), WeekMath.due(a, now, london, setOf(1)))
        assertEquals(emptyList<Int>(), WeekMath.due(a, ms(london, 2026, 10, 8), london, emptySet()))
    }

    @Test fun aLongGapBuildsOnlyTheNewestFewPeriods() {
        val a = LocalDate.of(2026, 1, 5)
        val due = WeekMath.due(a, ms(london, 2026, 12, 1), london, emptySet())
        assertEquals(WeekMath.MAX_CATCH_UP, due.size)
        assertEquals(due, due.sorted())
        assertEquals(WeekMath.lastEnded(a, ms(london, 2026, 12, 1), london), due.last())
    }
}
