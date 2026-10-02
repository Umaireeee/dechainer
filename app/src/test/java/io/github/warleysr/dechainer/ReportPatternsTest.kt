package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.report.DayFact
import io.github.warleysr.dechainer.report.GoalFact
import io.github.warleysr.dechainer.report.ReportPatterns
import io.github.warleysr.dechainer.report.ReportSummary
import io.github.warleysr.dechainer.report.UrgeFact
import io.github.warleysr.dechainer.urge.UrgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The ready-made counts the reports hand the model. */
class ReportPatternsTest {
    // 2026-10-05 is a Monday.
    private fun urge(day: Int, hour: Int, kind: UrgeKind = UrgeKind.URGE) = UrgeFact(kind, LocalDateTime.of(2026, 10, day, hour, 0), emptyList(), null)
    private fun day(d: Int, vararg goals: Pair<String, GoalState>, violation: Violation? = null, kind: DayKind = DayKind.NORMAL) =
        DayFact(LocalDate.of(2026, 10, d), kind, goals.map { GoalFact(it.first, GoalType.MANUAL, null, it.second) }, violation, 0)

    @Test
    fun partsOfTheDayCoverEveryHourOnce() {
        for (h in 0..23) assertEquals(1, ReportPatterns.Band.entries.count { h in it.hours })
        assertEquals(ReportPatterns.Band.LATE, ReportPatterns.Band.of(23))
        assertEquals(ReportPatterns.Band.NIGHT, ReportPatterns.Band.of(0))
    }

    @Test
    fun urgesAndSlipsAreCountedByPartOfTheDayAndByWeekday() {
        val u = listOf(urge(5, 23), urge(5, 22, UrgeKind.SLIP), urge(6, 23), urge(6, 9))
        assertEquals(
            listOf(Triple(ReportPatterns.Band.MORNING, 1, 0), Triple(ReportPatterns.Band.LATE, 2, 1)),
            ReportPatterns.byBand(u)
        )
        val week = ReportPatterns.byWeekday(u)
        assertEquals(java.time.DayOfWeek.MONDAY, week.first().first)
        assertEquals(1, week.first().second); assertEquals(1, week.first().third)
        val lines = ReportPatterns.lines(u, emptyList())
        assertTrue(lines[0], lines[0].contains("late evening 22-23: urges 2, slips 1"))
        assertTrue(lines[1], lines[1].contains("Mon: urges 1, slips 1"))
    }

    @Test
    fun aGoalThatKeepsComingBackUndoneIsNamed() {
        val days = listOf(
            day(5, "Gym" to GoalState.NOT_DONE, "Read" to GoalState.DONE),
            day(6, "gym " to GoalState.NOT_DONE, "Read" to GoalState.DONE),
            day(7, "GYM" to GoalState.DONE, "Read" to GoalState.DONE)
        )
        val r = ReportPatterns.repeatedGoals(days)
        assertEquals(1, r.size)
        assertEquals(ReportPatterns.RepeatedGoal("Gym", 3, 2), r.single())
        assertTrue(ReportPatterns.lines(emptyList(), days).any { it.contains("\"Gym\" planned on 3 days, not done on 2") })
    }

    @Test
    fun ratesAreWholePercentagesOrNotApplicable() {
        assertNull(ReportPatterns.percent(1, 0))
        assertEquals(33, ReportPatterns.percent(1, 3))
        val line = ReportPatterns.ratesLine(ReportSummary(3, 1, 2, 50, 3, 1, 5, 10, 0))
        assertTrue(line, line.contains("slips as a share of all entries: 25%"))
        assertTrue(line, line.contains("check-in yes rate: 75%"))
        assertTrue(line, line.contains("goals done: 50%"))
        assertTrue(ReportPatterns.ratesLine(ReportSummary(0, 0, 0, 0, 0, 0, 0, 0, 0)).contains("n/a"))
    }

    @Test
    fun theDaysLineCountsShortDaysByReasonAndRestDays() {
        val line = ReportPatterns.daysLine(listOf(
            day(5, "a" to GoalState.DONE),
            day(6, "a" to GoalState.NOT_DONE, violation = Violation.UNDER_HALF),
            day(7, violation = Violation.PLAN_MISSING),
            day(8, kind = DayKind.REST)
        ))
        assertTrue(line, line.contains("days that fell short: 2 (plan_missing 1, under_half 1)"))
        assertTrue(line, line.contains("rest days: 1"))
    }
}
