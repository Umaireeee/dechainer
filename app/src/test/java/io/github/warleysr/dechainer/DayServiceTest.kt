package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.day.*
import io.github.warleysr.dechainer.store.DechainerDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DayServiceTest {
    private lateinit var db: DechainerDatabase
    private lateinit var repo: DayRepository
    private val zone = ZoneId.of("Europe/London")
    private val a = LocalDate.of(2026, 10, 5) // activation day
    private val noon = { d: LocalDate -> d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = DechainerDatabase(ctx, null); repo = DayRepository(db)
    }
    @After fun tearDown() = db.close()

    private fun plan(d: LocalDate, n: Int = 3) = assertTrue(repo.savePlan(d, List(n) { NewGoal("g$it") }, 0L))
    private fun run(d: LocalDate, stats: DayStats = NoDayStats) = DayService.evaluate(repo, stats, a, noon(d), zone)
    private fun resolve(d: LocalDate, done: Int) = repo.goals(d).forEachIndexed { i, g ->
        repo.setGoal(g.id, if (i < done) GoalState.DONE else GoalState.NOT_DONE, ResolvedBy.USER)
    }

    @Test fun nothingIsJudgedOnTheActivationDay() {
        assertFalse(run(a)); assertNull(repo.day(a)?.takeIf { it.evaluated })
    }
    @Test fun skippingTheFirstPlanStillPunishesTheNextDay() {
        assertTrue(run(a.plusDays(1)))
        assertEquals(Violation.PLAN_MISSING, repo.day(a.plusDays(1))?.violation)
    }
    @Test fun aWrittenFirstPlanIsEnoughToStartClean() {
        plan(a.plusDays(1)); assertFalse(run(a.plusDays(1)))
    }
    @Test fun twoOfFourDoesNotPunishButOneOfThreeDoes() {
        plan(a.plusDays(1), 4); plan(a.plusDays(2))
        run(a.plusDays(1)); resolve(a.plusDays(1), 2)
        assertFalse(run(a.plusDays(2)))
        plan(a.plusDays(3)); resolve(a.plusDays(2), 1)
        assertTrue(run(a.plusDays(3)))
    }
    @Test fun aPunishmentDayNeverFollowsAPunishmentDay() {
        assertTrue(run(a.plusDays(1)))        // no plan: punished
        assertFalse(run(a.plusDays(2)))       // no plan again, but guarded
        assertEquals(DayKind.NORMAL, repo.day(a.plusDays(2))?.kind)
    }
    @Test fun reRunningChangesNothing() {
        plan(a.plusDays(1)); run(a.plusDays(1)); resolve(a.plusDays(1), 0)
        val first = run(a.plusDays(2)); val row = repo.day(a.plusDays(2))
        repeat(3) { assertEquals(first, run(a.plusDays(2))) }
        assertEquals(row, repo.day(a.plusDays(2)))
        // editing results after the evaluation cannot undo it
        resolve(a.plusDays(1), 3); assertEquals(row, repo.day(a.plusDays(2)))
    }
    @Test fun decliningRestNeverCancelsAPunishment() {
        plan(a.plusDays(1)); run(a.plusDays(1)); resolve(a.plusDays(1), 0)
        repo.setRest(a.plusDays(2), true); plan(a.plusDays(2))
        assertTrue(run(a.plusDays(2)))
        assertEquals(DayKind.PUNISHMENT, repo.day(a.plusDays(2))?.kind)
    }
    @Test fun anUnresolvedManualGoalPunishes() {
        plan(a.plusDays(1)); plan(a.plusDays(2)); run(a.plusDays(1))
        assertTrue(run(a.plusDays(2)))
        assertEquals(Violation.UNRESOLVED, repo.day(a.plusDays(2))?.violation)
    }
    @Test fun measuredGoalsSettleFromStats() {
        assertTrue(repo.savePlan(a.plusDays(1), listOf(NewGoal("f", GoalType.FOCUS_MINUTES, 30), NewGoal("n", GoalType.NO_SLIP), NewGoal("m")), 0L))
        plan(a.plusDays(2)); run(a.plusDays(1))
        repo.setGoal(repo.goals(a.plusDays(1))[2].id, GoalState.DONE, ResolvedBy.USER)
        val stats = object : DayStats { override fun focusMinutes(date: LocalDate) = 31; override fun slips(date: LocalDate) = 1 }
        assertFalse(run(a.plusDays(2), stats)) // focus and manual done, slip failed: 2 of 3
    }
    @Test fun missedDaysAreCaughtUpInOrder() {
        plan(a.plusDays(1)); plan(a.plusDays(2))
        assertFalse(DayService.evaluate(repo, NoDayStats, a, noon(a.plusDays(1)), zone))
        // the phone was off for two days; the unresolved goals of the first are judged on return
        assertTrue(run(a.plusDays(2)))
    }
    @Test fun aRestDayCanBeDeclaredOnlyBeforeItIsEvaluated() {
        repo.setRest(a.plusDays(1), true); plan(a.plusDays(1))
        run(a.plusDays(1)); repo.setRest(a.plusDays(1), false)
        assertEquals(DayKind.REST, repo.day(a.plusDays(1))?.kind)
    }
    @Test fun savingAPlanNeedsThreeToSevenGoals() {
        assertFalse(repo.savePlan(a, List(2) { NewGoal("x$it") }, 0L))
        assertFalse(repo.savePlan(a, List(8) { NewGoal("x$it") }, 0L))
        assertFalse(repo.savePlan(a, listOf(NewGoal("x"), NewGoal(" "), NewGoal("y")), 0L))
        assertTrue(repo.goals(a).isEmpty())
    }
}
