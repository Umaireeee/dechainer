package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.focus.FocusLogMath
import io.github.warleysr.dechainer.focus.FocusSession
import io.github.warleysr.dechainer.focus.Phase
import io.github.warleysr.dechainer.focus.PomodoroCore
import io.github.warleysr.dechainer.focus.PomodoroSettings
import io.github.warleysr.dechainer.focus.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PomodoroCoreTest {
    private val s = PomodoroSettings(focusMinutes = 25, shortBreakMinutes = 5, longBreakMinutes = 15, longBreakEvery = 4)
    private val min = 60_000L

    @Test
    fun startRunsForTheFullFocusTime() {
        val st = PomodoroCore.start(PomodoroState(), 1_000L, s)
        assertTrue(st.isRunning)
        assertEquals(1_000L + 25 * min, st.endsAt)
        assertEquals(1_000L, st.phaseStartedAt)
    }

    @Test
    fun pauseKeepsTheTimeLeftAndResumeContinuesIt() {
        val running = PomodoroCore.start(PomodoroState(), 0L, s)
        val paused = PomodoroCore.pause(running, 10 * min)
        assertTrue(paused.isPaused)
        assertEquals(15 * min, paused.pausedRemaining)
        val resumed = PomodoroCore.start(paused, 100 * min, s)
        assertEquals(115 * min, resumed.endsAt)
        // Resuming continues the same session: it keeps its start (its id in the log).
        assertEquals(0L, resumed.phaseStartedAt)
    }

    @Test
    fun finishedFocusLeavesTheBreakWaitingForYou() {
        val focus = PomodoroCore.start(PomodoroState(), 0L, s)
        val next = PomodoroCore.advance(focus, 25 * min, s, completed = true)
        assertEquals(Phase.SHORT_BREAK, next.phase)
        assertTrue(next.isIdle)
        assertEquals(5 * min, next.remaining(99 * min, s))
        assertEquals(1, next.focusDoneInCycle)
        // Start is what begins it.
        assertEquals(35 * min, PomodoroCore.start(next, 30 * min, s).endsAt)
    }

    @Test
    fun withAutoStartTheBreakRunsAtOnce() {
        val auto = s.copy(autoStartBreaks = true)
        val next = PomodoroCore.advance(PomodoroCore.start(PomodoroState(), 0L, auto), 25 * min, auto, completed = true)
        assertTrue(next.isRunning)
        assertEquals(30 * min, next.endsAt)
    }

    @Test
    fun everyFourthFocusEarnsTheLongBreakAndTheCycleRestartsAfterIt() {
        var st = PomodoroState(focusDoneInCycle = 3)
        st = PomodoroCore.advance(PomodoroCore.start(st, 0L, s), 25 * min, s, completed = true)
        assertEquals(Phase.LONG_BREAK, st.phase)
        assertEquals(4, st.focusDoneInCycle)
        st = PomodoroCore.advance(PomodoroCore.start(st, 25 * min, s), 40 * min, s, completed = true)
        assertEquals(Phase.FOCUS, st.phase)
        assertEquals(0, st.focusDoneInCycle)
    }

    @Test
    fun skippedFocusIsNotCounted() {
        val focus = PomodoroCore.start(PomodoroState(focusDoneInCycle = 2), 0L, s)
        val next = PomodoroCore.advance(focus, 5 * min, s, completed = false)
        assertEquals(2, next.focusDoneInCycle)
        assertEquals(Phase.SHORT_BREAK, next.phase)
    }

    @Test
    fun afterABreakFocusWaitsForStartUnlessAutoStartIsOn() {
        val brk = PomodoroState(phase = Phase.SHORT_BREAK, endsAt = 5 * min, focusDoneInCycle = 1)
        val manual = PomodoroCore.advance(brk, 5 * min, s, completed = true)
        assertTrue(manual.isIdle)
        assertEquals(Phase.FOCUS, manual.phase)
        val auto = PomodoroCore.advance(brk, 5 * min, s.copy(autoStartFocus = true), completed = true)
        assertTrue(auto.isRunning)
        assertEquals(30 * min, auto.endsAt)
    }

    @Test
    fun stopReturnsToAnIdleFocusAndKeepsTheCycle() {
        val st = PomodoroCore.stop(PomodoroState(phase = Phase.SHORT_BREAK, endsAt = 9L, focusDoneInCycle = 2))
        assertTrue(st.isIdle)
        assertEquals(Phase.FOCUS, st.phase)
        assertEquals(2, st.focusDoneInCycle)
    }

    @Test
    fun settingsAreKeptInRange() {
        val c = PomodoroSettings(focusMinutes = 0, shortBreakMinutes = 999, longBreakMinutes = 1, longBreakEvery = 1).clamped()
        assertEquals(5, c.focusMinutes)
        assertEquals(30, c.shortBreakMinutes)
        assertEquals(5, c.longBreakMinutes)
        assertEquals(2, c.longBreakEvery)
    }

    @Test
    fun logGroupsByDayNewestFirst() {
        val day1 = LocalDate.of(2026, 9, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val day2 = day1 + 24 * 60 * min
        val days = FocusLogMath.byDay(
            listOf(FocusSession(day1 + min, 25, true), FocusSession(day2 + min, 25, false), FocusSession(day1 + 60 * min, 25, true)),
            ZoneOffset.UTC
        )
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2026, 9, 26), days[0].date)
        assertEquals(2, days[1].count)
        assertEquals(2, days[1].doneCount)
        assertEquals(50, days[1].minutes)
        assertEquals(0, days[0].doneCount)
        assertFalse(days[1].sessions.first().id < days[1].sessions.last().id)
        assertNull(FocusLogMath.decode("").firstOrNull())
    }

    @Test
    fun aSessionKeepsTheLengthItStartedWith() {
        val started = PomodoroCore.start(PomodoroState(), 0L, s)
        assertEquals(25, started.plannedMinutes)
        // Pausing and resuming keeps it, even if the settings change in between.
        val resumed = PomodoroCore.start(PomodoroCore.pause(started, 5 * min), 9 * min, s.copy(focusMinutes = 50))
        assertEquals(25, resumed.plannedMinutes)
    }

    // ---- Focus blocks ----

    @Test
    fun aBlockFillsItsTimeExactlyAndNeverEndsOnABreak() {
        val plan = io.github.warleysr.dechainer.focus.BlockPlanner.plan(300, s)   // 8:00-13:00
        assertEquals(300, plan.sumOf { it.second })
        assertEquals(Phase.FOCUS, plan.first().first)
        assertEquals(Phase.FOCUS, plan.last().first)
        assertEquals(10, plan.count { it.first == Phase.FOCUS })
        assertEquals(2, plan.count { it.first == Phase.LONG_BREAK })   // after sessions 4 and 8
    }

    @Test
    fun aBlockDropsLeftoversTooShortForASession() {
        val p = io.github.warleysr.dechainer.focus.BlockPlanner
        assertTrue(p.plan(9, s).isEmpty())
        assertEquals(listOf(Phase.FOCUS to 12), p.plan(12, s))
        // 34 minutes: a 25-minute session, then 9 left: not enough for a break and another session.
        assertEquals(listOf(Phase.FOCUS to 25), p.plan(34, s))
    }

    @Test
    fun blockStepsFollowTheRemainingTime() {
        val p = io.github.warleysr.dechainer.focus.BlockPlanner
        assertEquals(Phase.SHORT_BREAK to 5, p.next(Phase.FOCUS, 1, 60, s))
        assertEquals(Phase.LONG_BREAK to 15, p.next(Phase.FOCUS, 4, 60, s))
        assertNull(p.next(Phase.FOCUS, 1, 12, s))                // no room for a session after it
        assertEquals(Phase.FOCUS to 18, p.next(Phase.SHORT_BREAK, 1, 18, s))   // last one shortened
        assertNull(p.next(Phase.SHORT_BREAK, 1, 9, s))
    }

    @Test
    fun theOldStoredLogStillReadsForTheOneTimeImport() {
        assertEquals(emptyList<FocusSession>(), FocusLogMath.decode(null))
        // A damaged entry is skipped, not a crash.
        assertEquals(listOf(FocusSession(5L, 25, true)), FocusLogMath.decode("garbage;5,25,1;x,y,z"))
        // Three fields (before subjects existed), five (subject and intention) and six (with lectures) all read.
        assertEquals(FocusSession(9L, 25, false), FocusLogMath.decode("9,25,0").single())
        // The subject and the lecture count are dropped; the intention stays.
        assertEquals(FocusSession(9L, 90, true, intention = "IAS 16"), FocusLogMath.decode("9,90,1,FAR,IAS 16,2").single())
        assertEquals(FocusSession(9L, 25, null), FocusLogMath.decode("9,25,-,FAR").single())
    }

    @Test
    fun anIntentionIsStoredSafely() {
        assertEquals("a b c", FocusLogMath.cleanIntention("a,b;\nc"))
        assertNull(FocusLogMath.cleanIntention("   "))
        assertEquals(80, FocusLogMath.cleanIntention("x".repeat(200))!!.length)
    }
}
