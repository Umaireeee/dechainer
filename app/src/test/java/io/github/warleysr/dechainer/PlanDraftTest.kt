package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.day.DayRules
import io.github.warleysr.dechainer.day.Goal
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.NewGoal
import io.github.warleysr.dechainer.day.PlanDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Writing tomorrow's plan: the goal types, the carry-over (on tap only) and what is saved. */
class PlanDraftTest {
    private fun goal(text: String, type: GoalType = GoalType.MANUAL, target: Int? = null, state: GoalState = GoalState.NOT_DONE) =
        Goal(text.hashCode().toLong(), 0, text, type, target, state)

    @Test
    fun aTapCyclesManualThroughTheFocusStepsToNoSlipAndBack() {
        var d = PlanDraft("read")
        val seen = mutableListOf<Pair<GoalType, Int?>>()
        repeat(6) { d = d.nextType(); seen += d.type to d.targetMinutes }
        assertEquals(
            listOf(
                GoalType.FOCUS_MINUTES to 30, GoalType.FOCUS_MINUTES to 60, GoalType.FOCUS_MINUTES to 90,
                GoalType.FOCUS_MINUTES to 120, GoalType.NO_SLIP to null, GoalType.MANUAL to null
            ),
            seen
        )
        assertEquals("the text is kept", "read", d.text)
    }

    @Test
    fun anEmptyManualLineIsNotSavedButAnEmptyMeasuredOneGetsPlainText() {
        assertNull(PlanDraft("   ").toNewGoal("Focus %d minutes", "No slip today"))
        assertEquals(NewGoal("Focus 60 minutes", GoalType.FOCUS_MINUTES, 60),
            PlanDraft("", GoalType.FOCUS_MINUTES, 60).toNewGoal("Focus %d minutes", "No slip today"))
        assertEquals(NewGoal("No slip today", GoalType.NO_SLIP), PlanDraft(" ", GoalType.NO_SLIP).toNewGoal("Focus %d minutes", "No slip today"))
        assertEquals(NewGoal("Gym", GoalType.MANUAL), PlanDraft("  Gym ").toNewGoal("x", "y"))
    }

    @Test
    fun aSavedPlanComesBackAsDraftsAndNoPlanIsThreeEmptyLines() {
        assertEquals(List(DayRules.MIN_GOALS) { PlanDraft() }, PlanDraft.fromGoals(emptyList()))
        assertEquals(listOf(PlanDraft("a", GoalType.FOCUS_MINUTES, 90)), PlanDraft.fromGoals(listOf(goal("a", GoalType.FOCUS_MINUTES, 90))))
    }

    @Test
    fun carryOverFillsEmptyLinesFirstAndNeverDoublesAGoal() {
        val drafts = listOf(PlanDraft("Gym"), PlanDraft(), PlanDraft())
        val out = PlanDraft.carryOver(drafts, listOf(goal("gym "), goal("Essay"), goal("Call mum")))
        assertEquals(listOf("Gym", "Essay", "Call mum"), out.map { it.text })
    }

    @Test
    fun carryOverNeverGrowsThePlanPastTheMaximum() {
        val drafts = List(6) { PlanDraft("g$it") }
        val out = PlanDraft.carryOver(drafts, listOf(goal("a"), goal("b"), goal("c")))
        assertEquals(DayRules.MAX_GOALS, out.size)
        assertEquals("a", out.last().text)
    }

    @Test
    fun carryOverKeepsAMeasuredGoalsType() {
        val out = PlanDraft.carryOver(List(3) { PlanDraft() }, listOf(goal("Focus", GoalType.FOCUS_MINUTES, 60)))
        assertEquals(PlanDraft("Focus", GoalType.FOCUS_MINUTES, 60), out.first())
    }
}
