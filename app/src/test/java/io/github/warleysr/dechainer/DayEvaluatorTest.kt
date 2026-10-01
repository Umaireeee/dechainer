package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.day.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayEvaluatorTest {
    private val zone = ZoneId.of("Europe/London")
    private val d = LocalDate.of(2026, 10, 7)

    private fun goals(vararg s: GoalState, type: GoalType = GoalType.MANUAL) =
        s.mapIndexed { i, st -> Goal(i.toLong(), i, "g$i", type, if (type == GoalType.FOCUS_MINUTES) 60 else null, st) }

    private fun eval(prev: DayKind = DayKind.NORMAL, g: List<Goal>, plan: Int = 3, rest: Boolean = false) =
        DayEvaluator.evaluate(prev, g, plan, rest)

    private val done = GoalState.DONE
    private val not = GoalState.NOT_DONE
    private val open = GoalState.OPEN

    @Test fun missingPlanPunishes() {
        val v = eval(g = goals(done, done, done), plan = 0)
        assertEquals(DayKind.PUNISHMENT, v.kind); assertEquals(Violation.PLAN_MISSING, v.violation)
        assertEquals(Violation.PLAN_MISSING, eval(g = goals(done, done, done), plan = 2).violation)
    }
    @Test fun twoOfFourDoesNotPunish() = assertEquals(DayKind.NORMAL, eval(g = goals(done, done, not, not)).kind)
    @Test fun oneOfThreePunishes() = assertEquals(Violation.UNDER_HALF, eval(g = goals(done, not, not)).violation)
    @Test fun oneOfFourPunishes() = assertEquals(Violation.UNDER_HALF, eval(g = goals(done, not, not, not)).violation)
    @Test fun unresolvedManualGoalPunishes() = assertEquals(Violation.UNRESOLVED, eval(g = goals(done, done, open)).violation)
    @Test fun zeroGoalsNeverUnderHalf() = assertEquals(DayKind.NORMAL, eval(g = emptyList()).kind)
    @Test fun zeroGoalsStillNeedTomorrowsPlan() = assertEquals(Violation.PLAN_MISSING, eval(g = emptyList(), plan = 0).violation)

    @Test fun punishmentNeverFollowsPunishment() {
        assertEquals(DayKind.NORMAL, eval(DayKind.PUNISHMENT, goals(not, not, not), plan = 0).kind)
        assertNull(eval(DayKind.PUNISHMENT, goals(open, not), plan = 0).violation)
    }
    @Test fun restWaivesItsOwnResultsOnly() {
        assertEquals(DayKind.NORMAL, eval(DayKind.REST, goals(not, not, not)).kind)
        assertEquals(Violation.PLAN_MISSING, eval(DayKind.REST, goals(done, done, done), plan = 0).violation)
    }
    @Test fun decliningRestNeverCancelsAPunishment() {
        assertEquals(DayKind.PUNISHMENT, eval(g = goals(not, not, not), rest = true).kind)
        assertEquals(DayKind.PUNISHMENT, eval(g = goals(done, done, done), plan = 0, rest = true).kind)
        assertEquals(DayKind.REST, eval(g = goals(done, done, done), rest = true).kind)
    }

    @Test fun settleResolvesMeasuredGoalsByAuto() {
        val g = goals(open, open, type = GoalType.FOCUS_MINUTES)
        assertEquals(listOf(done, done), DayEvaluator.settle(g, 60, 0).map { it.state })
        assertEquals(listOf(not, not), DayEvaluator.settle(g, 59, 0).map { it.state })
        assertTrue(DayEvaluator.settle(g, 59, 0).all { it.resolvedBy == ResolvedBy.AUTO })
        val n = goals(open, type = GoalType.NO_SLIP)
        assertEquals(done, DayEvaluator.settle(n, 0, 0)[0].state)
        assertEquals(not, DayEvaluator.settle(n, 0, 1)[0].state)
        // manual goals and settled goals are left alone, and settling twice changes nothing
        val m = goals(open, done)
        assertEquals(m, DayEvaluator.settle(m, 99, 0))
        val once = DayEvaluator.settle(g, 10, 0)
        assertEquals(once, DayEvaluator.settle(once, 999, 0))
    }
    @Test fun focusGoalTicksLiveButNeverFailsEarly() {
        val g = goals(open, type = GoalType.FOCUS_MINUTES)
        assertEquals(open, DayEvaluator.live(g, 59)[0].state)
        assertEquals(done, DayEvaluator.live(g, 60)[0].state)
    }

    @Test fun boundariesAt235959And0000() {
        fun ms(h: Int, m: Int, s: Int, day: LocalDate = d) = day.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = d.plusDays(1)
        assertFalse(DayWindow.canEditPlan(ms(19, 59, 59), tomorrow, zone))
        assertTrue(DayWindow.canEditPlan(ms(20, 0, 0), tomorrow, zone))
        assertTrue(DayWindow.canEditPlan(ms(23, 59, 59), tomorrow, zone))
        assertFalse(DayWindow.canEditPlan(ms(0, 0, 0, tomorrow), tomorrow, zone))
        assertTrue(DayWindow.canMarkNotDone(ms(23, 59, 59), d, zone))
        assertFalse(DayWindow.canMarkNotDone(ms(0, 0, 0, tomorrow), d, zone))
        assertFalse(DayWindow.canMarkNotDone(ms(12, 0, 0), d, zone))
        assertFalse("not during the day", DayWindow.canMarkDone(ms(9, 0, 0), d, zone))
        assertFalse(DayWindow.canMarkDone(ms(19, 59, 59), d, zone))
        assertTrue(DayWindow.canMarkDone(ms(20, 0, 0), d, zone))
        assertTrue(DayWindow.canMarkDone(ms(23, 59, 59), d, zone))
        assertFalse(DayWindow.canMarkDone(ms(0, 0, 0, tomorrow), d, zone))
        assertFalse("not the day before", DayWindow.canMarkDone(ms(21, 0, 0, d.minusDays(1)), d, zone))
    }
    @Test fun restLimitIsOnePerRollingSeven() {
        val now = d.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val t = d.plusDays(1)
        assertTrue(DayWindow.canDeclareRest(now, t, emptyList(), zone))
        assertFalse(DayWindow.canDeclareRest(now, t, listOf(t.minusDays(6)), zone))
        assertTrue(DayWindow.canDeclareRest(now, t, listOf(t.minusDays(7)), zone))
        assertFalse(DayWindow.canDeclareRest(now - 3_600_000L * 24, t, emptyList(), zone)) // not in the window
    }
}
