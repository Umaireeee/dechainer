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

    @Test fun nothingIsClosedOnTheActivationDayItself() {
        run(a); assertNull(repo.day(a)?.takeIf { it.evaluated })
    }
    @Test fun aDayIsClosedTheMorningAfterWithItsCountsAndReason() {
        plan(a.plusDays(1), 4); run(a.plusDays(1)); resolve(a.plusDays(1), 1)
        run(a.plusDays(2))
        val row = repo.day(a.plusDays(1))!!
        assertTrue(row.evaluated)
        assertEquals(1, row.doneCount); assertEquals(4, row.totalCount)
        assertEquals(Violation.UNDER_HALF, row.violation)
        assertEquals("closing a day never makes it anything but what it was", DayKind.NORMAL, row.kind)
    }
    @Test fun skippingTheFirstPlanIsRecordedOnTheActivationDay() {
        run(a.plusDays(1))
        assertEquals(Violation.PLAN_MISSING, repo.day(a)?.violation)
    }
    @Test fun aWrittenFirstPlanStartsClean() {
        plan(a.plusDays(1)); run(a.plusDays(1))
        assertNull(repo.day(a)?.violation)
    }
    @Test fun twoOfFourIsOnPlanAndOneOfThreeFallsShort() {
        plan(a.plusDays(1), 4); plan(a.plusDays(2)); plan(a.plusDays(3))
        run(a.plusDays(1)); resolve(a.plusDays(1), 2)
        run(a.plusDays(2)); resolve(a.plusDays(2), 1)
        run(a.plusDays(3))
        assertNull(repo.day(a.plusDays(1))?.violation)
        assertEquals(Violation.UNDER_HALF, repo.day(a.plusDays(2))?.violation)
    }
    @Test fun reRunningChangesNothing() {
        plan(a.plusDays(1)); run(a.plusDays(1)); resolve(a.plusDays(1), 0)
        run(a.plusDays(2)); val row = repo.day(a.plusDays(1))
        repeat(3) { run(a.plusDays(2)) }
        assertEquals(row, repo.day(a.plusDays(1)))
        // editing results after the day is closed cannot rewrite its record
        resolve(a.plusDays(1), 3); run(a.plusDays(3)); assertEquals(row?.violation, repo.day(a.plusDays(1))?.violation)
    }
    @Test fun aRestDayWaivesItsOwnGoals() {
        plan(a.plusDays(1)); plan(a.plusDays(2)); repo.setRest(a.plusDays(1), true)
        run(a.plusDays(2))
        assertEquals(DayKind.REST, repo.day(a.plusDays(1))?.kind)
        assertNull(repo.day(a.plusDays(1))?.violation)
        assertTrue(repo.isRest(a.plusDays(1)))
    }
    @Test fun anOpenManualGoalIsRecorded() {
        plan(a.plusDays(1)); plan(a.plusDays(2)); run(a.plusDays(1))
        run(a.plusDays(2))
        assertEquals(Violation.UNRESOLVED, repo.day(a.plusDays(1))?.violation)
    }
    @Test fun measuredGoalsSettleFromStats() {
        assertTrue(repo.savePlan(a.plusDays(1), listOf(NewGoal("f", GoalType.FOCUS_MINUTES, 30), NewGoal("n", GoalType.NO_SLIP), NewGoal("m")), 0L))
        plan(a.plusDays(2)); run(a.plusDays(1))
        repo.setGoal(repo.goals(a.plusDays(1))[2].id, GoalState.DONE, ResolvedBy.USER)
        val stats = object : DayStats { override fun focusMinutes(date: LocalDate) = 31; override fun slips(date: LocalDate) = 1 }
        run(a.plusDays(2), stats) // focus and manual done, slip failed: 2 of 3
        assertEquals(listOf(GoalState.DONE, GoalState.NOT_DONE, GoalState.DONE), repo.goals(a.plusDays(1)).map { it.state })
        assertNull(repo.day(a.plusDays(1))?.violation)
    }
    @Test fun missedDaysAreCaughtUpInOrderAndTheCatchUpStartsAfterTheLastClosedDay() {
        plan(a.plusDays(1)); plan(a.plusDays(2))
        // the phone was off for days; every day up to yesterday is closed on return
        run(a.plusDays(4))
        listOf(a, a.plusDays(1), a.plusDays(2), a.plusDays(3)).forEach { assertTrue("$it closed", repo.day(it)?.evaluated == true) }
        assertEquals(a.plusDays(3), repo.lastEvaluated())
        assertNull("today is never closed early", repo.day(a.plusDays(4))?.takeIf { it.evaluated })
    }
    @Test fun aRestDayCanBeDeclaredOnlyBeforeItIsClosed() {
        repo.setRest(a.plusDays(1), true); plan(a.plusDays(1))
        run(a.plusDays(2)); repo.setRest(a.plusDays(1), false)
        assertEquals(DayKind.REST, repo.day(a.plusDays(1))?.kind)
    }
    @Test fun savingAPlanNeedsThreeToSevenGoals() {
        assertFalse(repo.savePlan(a, List(2) { NewGoal("x$it") }, 0L))
        assertFalse(repo.savePlan(a, List(8) { NewGoal("x$it") }, 0L))
        assertFalse(repo.savePlan(a, listOf(NewGoal("x"), NewGoal(" "), NewGoal("y")), 0L))
        assertTrue(repo.goals(a).isEmpty())
    }
}
