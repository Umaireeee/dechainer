package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.day.Goal
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.report.ReportRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportRulesTest {
    private val zone = ZoneId.of("Europe/London")
    private fun ms(d: LocalDate, h: Int = 0) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private val d = LocalDate.of(2026, 10, 6)

    @Test fun goalsInTheOpenWeekCannotBeDeleted() {
        assertFalse(ReportRules.canDeleteGoals(d, null, zone)) // no report yet
        assertFalse(ReportRules.canDeleteGoals(LocalDate.of(2026, 10, 12), ms(LocalDate.of(2026, 10, 12)), zone))
    }

    @Test fun goalsCoveredByAReportCanGo() {
        val end = ms(LocalDate.of(2026, 10, 12))
        assertTrue(ReportRules.canDeleteGoals(d, end, zone))
        assertTrue(ReportRules.canDeleteGoals(LocalDate.of(2026, 10, 11), end, zone)) // the last day of that week
    }

    private fun goal(type: GoalType, state: GoalState) = Goal(1, 0, "g", type, null, state)

    @Test fun aSlipOrSessionOfTodayCannotBeDeleted() {
        val now = ms(d, 21)
        assertFalse(ReportRules.canDeleteMeasured(ms(d, 10), now, zone, emptyList()))
    }

    @Test fun aSlipOfAnEarlierDayCanGoOnceItsMeasuredGoalsAreSettled() {
        val now = ms(d.plusDays(1), 1)
        assertTrue(ReportRules.canDeleteMeasured(ms(d, 10), now, zone, emptyList()))
        assertTrue(ReportRules.canDeleteMeasured(ms(d, 10), now, zone, listOf(goal(GoalType.NO_SLIP, GoalState.NOT_DONE), goal(GoalType.MANUAL, GoalState.OPEN))))
        assertFalse(ReportRules.canDeleteMeasured(ms(d, 10), now, zone, listOf(goal(GoalType.NO_SLIP, GoalState.OPEN))))
        assertFalse(ReportRules.canDeleteMeasured(ms(d, 10), now, zone, listOf(goal(GoalType.FOCUS_MINUTES, GoalState.OPEN))))
    }
}
