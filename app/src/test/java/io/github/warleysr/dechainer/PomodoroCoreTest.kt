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
    fun logSurvivesARoundTrip() {
        val list = listOf(FocusSession(1L, 25, true), FocusSession(2L, 25, false), FocusSession(3L, 50, null))
        assertEquals(list, FocusLogMath.decode(FocusLogMath.encode(list)))
        assertEquals(emptyList<FocusSession>(), FocusLogMath.decode(null))
        // A damaged entry is skipped, not a crash.
        assertEquals(listOf(FocusSession(5L, 25, true)), FocusLogMath.decode("garbage;5,25,1;x,y,z"))
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
    fun thisWeekRunsFromMonday() {
        fun at(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + min
        val friday = LocalDate.of(2026, 9, 25)          // a Friday
        val list = listOf(
            FocusSession(at(LocalDate.of(2026, 9, 21)), 25, true),   // Monday: in
            FocusSession(at(LocalDate.of(2026, 9, 20)), 25, true),   // Sunday before: out
            FocusSession(at(friday), 50, false)                     // today: in
        )
        val week = FocusLogMath.thisWeek(list, friday, ZoneOffset.UTC)
        assertEquals(2, week.size)
        assertEquals(75, week.sumOf { it.minutes })
    }

    @Test
    fun tagsAreStoredSafelyAndOldEntriesStillRead() {
        val list = listOf(FocusSession(1L, 25, true, "FAR"), FocusSession(2L, 25, null, null))
        assertEquals(list, FocusLogMath.decode(FocusLogMath.encode(list)))
        // Separators in a tag can't break the log.
        assertEquals("Tax a b", FocusLogMath.cleanTag("Tax,a;b"))
        assertNull(FocusLogMath.cleanTag("   "))
        // A three-field entry from before tags existed.
        assertEquals(FocusSession(9L, 25, false, null), FocusLogMath.decode("9,25,0").single())
    }

    @Test
    fun minutesAddUpBySubject() {
        val list = listOf(FocusSession(1L, 25, true, "FAR"), FocusSession(2L, 50, true, "Tax"), FocusSession(3L, 25, true, "FAR"))
        assertEquals(listOf<Pair<String?, Int>>("FAR" to 50, "Tax" to 50).toSet(), FocusLogMath.minutesByTag(list).toSet())
    }

    @Test
    fun chartsCoverEveryDayAndWeekIncludingEmptyOnes() {
        fun at(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + min
        val today = LocalDate.of(2026, 9, 25)
        val list = listOf(FocusSession(at(today), 25, true), FocusSession(at(today.minusDays(3)), 50, true))
        val daily = FocusLogMath.dailyMinutes(list, today, 14, ZoneOffset.UTC)
        assertEquals(14, daily.size)
        assertEquals(today, daily.last().first)
        assertEquals(25, daily.last().second)
        assertEquals(50, daily[10].second)
        assertEquals(75, daily.sumOf { it.second })
        val weekly = FocusLogMath.weeklyMinutes(list, today, 12, ZoneOffset.UTC)
        assertEquals(12, weekly.size)
        assertEquals(LocalDate.of(2026, 9, 21), weekly.last().first)   // this week's Monday
        assertEquals(75, weekly.last().second)                          // both fall in this week
    }

    @Test
    fun intentionsAreStoredAndOlderFormatsStillRead() {
        val list = listOf(
            FocusSession(1L, 25, true, null, "IAS 16 questions 1-10"),
            FocusSession(2L, 25, false, "Tax", "Read chapter 4"),
            FocusSession(3L, 25, null, "FAR")
        )
        assertEquals(list, FocusLogMath.decode(FocusLogMath.encode(list)))
        assertEquals("a b c", FocusLogMath.cleanIntention("a,b;\nc"))
    }

    @Test
    fun dailyGoalIsKeptInRange() {
        assertEquals(20, PomodoroSettings(dailyGoal = 99).clamped().dailyGoal)
        assertEquals(0, PomodoroSettings(dailyGoal = -3).clamped().dailyGoal)
    }

    @Test
    fun csvBackupRoundTripsEveryField() {
        val list = listOf(
            FocusSession(1_758_000_000_000L, 60, true, "FAR", "Lecture 12"),
            FocusSession(1_758_010_000_000L, 25, false, "Tax"),
            FocusSession(1_758_020_000_000L, 25, null)
        )
        val csv = FocusLogMath.toCsv(list, ZoneOffset.UTC)
        assertTrue(csv.startsWith("date,time,start_millis"))
        assertEquals(list, FocusLogMath.fromCsv(csv))
        // Windows line endings (a file saved from Excel) and junk rows are fine.
        assertEquals(list, FocusLogMath.fromCsv(csv.replace("\n", "\r\n") + "junk\n,,,\n"))
    }

    @Test
    fun importAddsOnlyNewSessions() {
        val a = FocusSession(1L, 25, true)
        val b = FocusSession(2L, 25, false)
        val c = FocusSession(3L, 25, null)
        val merged = FocusLogMath.merge(listOf(a, b), listOf(b.copy(done = true), c, c))
        assertEquals(listOf(a, b, c), merged)   // b keeps what's already in the log
    }

    @Test
    fun subjectTargetsTrackTodayAndSurviveStorage() {
        // Targets count lectures: a lecture can take two sessions, or one session can finish two.
        val today = listOf(
            FocusSession(1L, 60, true, "FAR", lectures = 0), FocusSession(2L, 60, true, "FAR", lectures = 1),
            FocusSession(3L, 45, true, "FAR", lectures = 1), FocusSession(4L, 60, true, "Tax", lectures = 1)
        )
        val targets = mapOf("FAR" to 2, "Tax" to 2, "CAF 4" to 1)
        val progress = FocusLogMath.subjectProgress(today, targets, listOf("FAR", "Tax", "CAF 4", "Other"))
        assertEquals(listOf(Triple("FAR", 2, 2), Triple("Tax", 1, 2), Triple("CAF 4", 0, 1)), progress)
        assertEquals(targets, FocusLogMath.decodeTargets(FocusLogMath.encodeTargets(targets)))
        assertEquals(emptyMap<String, Int>(), FocusLogMath.decodeTargets("garbage\n=3\nX=abc"))
    }

    @Test
    fun averageIgnoresDaysBeforeYouStarted() {
        val today = LocalDate.of(2026, 9, 26)
        val points = (13 downTo 0).map { back -> today.minusDays(back.toLong()) to if (back <= 1) 60 else 0 }
        // Started yesterday: two days, 60 min each, average 60 (not 120 / 14 = 8).
        assertEquals(60, FocusLogMath.pageAverage(points, today.minusDays(1), weekly = false))
        // Started long before this page: every day counts.
        assertEquals(120 / 14, FocusLogMath.pageAverage(points, today.minusDays(100), weekly = false))
        assertEquals(0, FocusLogMath.pageAverage(points, null, weekly = false))
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
    fun lecturesAreStoredAndOldFormatsStillRead() {
        val list = listOf(
            FocusSession(1L, 90, true, "FAR", "IAS 16", lectures = 1),
            FocusSession(2L, 50, true, null, null, lectures = 2),
            FocusSession(3L, 25, false, "Tax")
        )
        assertEquals(list, FocusLogMath.decode(FocusLogMath.encode(list)))
        // Stored before lectures existed: five fields, reads as 0 lectures.
        assertEquals(0, FocusLogMath.decode("9,25,1,FAR,Read ch 4").single().lectures)
    }

    @Test
    fun oldCsvBackupsWithoutALecturesColumnStillImport() {
        val old = "date,time,start_millis,minutes,did_the_work,subject,intention\n" +
            "2026-09-20,09:00,1758000000000,25,yes,FAR,IAS 16 questions\n"
        val s = FocusLogMath.fromCsv(old).single()
        assertEquals(0, s.lectures)
        assertEquals("IAS 16 questions", s.intention)
        // And the new format carries lectures through a round trip.
        val now = listOf(FocusSession(1_758_000_000_000L, 90, true, "FAR", "Lecture 12", lectures = 1))
        assertEquals(now, FocusLogMath.fromCsv(FocusLogMath.toCsv(now, ZoneOffset.UTC)))
    }

    @Test
    fun chartsCanCountLectures() {
        fun at(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + min
        val today = LocalDate.of(2026, 9, 25)
        val list = listOf(FocusSession(at(today), 90, true, lectures = 1), FocusSession(at(today), 50, true, lectures = 2))
        val daily = FocusLogMath.dailyMinutes(list, today, 14, ZoneOffset.UTC) { it.lectures }
        assertEquals(3, daily.last().second)
        assertEquals(1.5f, FocusLogMath.pageAverageExact(listOf(today.minusDays(1) to 0, today to 3), today.minusDays(1), false))
    }

    // ---- Lectures ----

    @Test
    fun aLectureIsFirstAskedAboutAtTwoThirdsThenEvery30Minutes() {
        val m = io.github.warleysr.dechainer.focus.LectureMath
        var p = io.github.warleysr.dechainer.focus.LectureProgress()
        p = m.afterSession(p, 25); assertFalse(m.shouldAsk(p, 90))   // 25 min
        p = m.afterSession(p, 25); assertFalse(m.shouldAsk(p, 90))   // 50 min
        p = m.afterSession(p, 25); assertTrue(m.shouldAsk(p, 90))    // 75 min: past 60, ask
        p = m.answer(p, io.github.warleysr.dechainer.focus.LectureAnswer.IN_PROGRESS, 25)
        p = m.afterSession(p, 25); assertFalse(m.shouldAsk(p, 90))   // only 25 more
        p = m.afterSession(p, 25); assertTrue(m.shouldAsk(p, 90))    // 50 more: ask again
        p = m.answer(p, io.github.warleysr.dechainer.focus.LectureAnswer.DONE, 25)
        assertEquals(0, p.minutes)                                    // a fresh lecture starts
    }

    @Test
    fun procrastinatingTakesBackThatSessionButKeepsEarlierWork() {
        val m = io.github.warleysr.dechainer.focus.LectureMath
        val p = io.github.warleysr.dechainer.focus.LectureProgress(minutes = 75, askedAt = 0)
        val after = m.answer(p, io.github.warleysr.dechainer.focus.LectureAnswer.PROCRASTINATING, 25)
        assertEquals(50, after.minutes)
        assertFalse(m.shouldAsk(m.afterSession(after, 25), 90))         // 25 real minutes: not yet
        assertTrue(m.shouldAsk(m.afterSession(after, 30), 90))          // 30 real minutes: ask
    }

    @Test
    fun lectureProgressSurvivesStorage() {
        val m = io.github.warleysr.dechainer.focus.LectureMath
        val map = mapOf("FAR" to io.github.warleysr.dechainer.focus.LectureProgress(45, 0),
                        "" to io.github.warleysr.dechainer.focus.LectureProgress(70, 60))
        assertEquals(map, m.decode(m.encode(map)))
    }
}
