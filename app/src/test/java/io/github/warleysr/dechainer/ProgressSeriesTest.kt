package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.report.DayCount
import io.github.warleysr.dechainer.report.Granularity
import io.github.warleysr.dechainer.report.ProgressSeries
import io.github.warleysr.dechainer.urge.UrgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The numbers behind the progress graphs. */
class ProgressSeriesTest {
    private val today = LocalDate.of(2026, 10, 8) // a Thursday

    @Test
    fun eachGranularityHasItsBucketCountEndingWithTheBucketOfToday() {
        val d = ProgressSeries.build(Granularity.DAY, today, emptyList(), emptyList(), emptyList())
        assertEquals(28, d.size); assertEquals(today, d.last().first); assertEquals(today.minusDays(27), d.first().first)
        val w = ProgressSeries.build(Granularity.WEEK, today, emptyList(), emptyList(), emptyList())
        assertEquals(12, w.size)
        assertEquals("weeks start on Monday", LocalDate.of(2026, 10, 5), w.last().first)
        assertEquals(LocalDate.of(2026, 10, 11), w.last().last)
        val m = ProgressSeries.build(Granularity.MONTH, today, emptyList(), emptyList(), emptyList())
        assertEquals(12, m.size)
        assertEquals(LocalDate.of(2026, 10, 1), m.last().first)
        assertEquals(LocalDate.of(2025, 11, 1), m.first().first)
        assertEquals(LocalDate.of(2026, 2, 28), m[3].last)
    }

    @Test
    fun firstDateIsTheStartOfTheOldestBucket() {
        assertEquals(today.minusDays(27), ProgressSeries.firstDate(Granularity.DAY, today))
        assertEquals(LocalDate.of(2026, 7, 20), ProgressSeries.firstDate(Granularity.WEEK, today))
        assertEquals(LocalDate.of(2025, 11, 1), ProgressSeries.firstDate(Granularity.MONTH, today))
    }

    @Test
    fun everythingIsCountedInTheBucketOfItsDate() {
        val w = ProgressSeries.build(
            Granularity.WEEK, today,
            urges = listOf(
                LocalDate.of(2026, 10, 5) to UrgeKind.URGE, LocalDate.of(2026, 10, 7) to UrgeKind.SLIP,
                LocalDate.of(2026, 10, 4) to UrgeKind.URGE, LocalDate.of(2025, 1, 1) to UrgeKind.URGE
            ),
            focus = listOf(LocalDate.of(2026, 10, 6) to 50, LocalDate.of(2026, 10, 8) to 25),
            days = listOf(DayCount(LocalDate.of(2026, 10, 5), 2, 3, false), DayCount(LocalDate.of(2026, 10, 6), 0, 3, true))
        )
        val now = w.last()
        assertEquals(1, now.urges); assertEquals(1, now.slips)
        assertEquals(75, now.focusMinutes)
        assertEquals(2, now.goalsDone); assertEquals(6, now.goalsTotal); assertEquals(33, now.percentDone)
        assertEquals(1, now.daysShort)
        assertEquals("Sunday belongs to the week before", 1, w[10].urges)
        assertEquals("far outside the range: dropped", 2, w.sumOf { it.urges })
        assertNull(w.first().percentDone)
    }

    @Test
    fun theBarScaleRoundsUpAndHasAFloor() {
        assertEquals(4, ProgressSeries.niceMax(0))
        assertEquals(4, ProgressSeries.niceMax(3))
        assertEquals(8, ProgressSeries.niceMax(7))
        assertEquals(15, ProgressSeries.niceMax(13))
        assertEquals(140, ProgressSeries.niceMax(123))
        assertEquals(500, ProgressSeries.niceMax(430))
    }
}
