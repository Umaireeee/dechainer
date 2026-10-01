package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.FocusStart
import io.github.warleysr.dechainer.lock.FocusTimetable
import io.github.warleysr.dechainer.lock.SkipReason
import io.github.warleysr.dechainer.models.BlockSchedule
import io.github.warleysr.dechainer.models.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** Blueprint 6.3 and 5.3: which FOCUS entry starts a block now. 2026-10-01 is a Thursday. */
class FocusTimetableTest {
    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L

    private fun at(h: Int, m: Int = 0, day: Int = 1): Long =
        ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toInstant().toEpochMilli()

    private fun focus(id: String, start: Int, end: Int, days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), enabled: Boolean = true) =
        BlockSchedule(id, id, enabled = enabled, days = days, startMinute = start, endMinute = end, type = ScheduleType.FOCUS)

    private fun due(
        now: Long, schedules: List<BlockSchedule>, settled: Set<String> = emptySet(),
        punishment: Boolean = false, rest: Boolean = false, urgeEnds: Long = 0L
    ) = FocusTimetable.due(now, utc, schedules, settled, punishment, rest, urgeEnds)

    @Test
    fun aFocusWindowThatIsOpenStartsAtOnce() {
        val d = due(at(14), listOf(focus("study", 14 * 60, 16 * 60)))!!
        assertEquals(FocusStart.Now, d.decision)
        assertEquals(at(14), d.window.startsAt)
        assertEquals(at(16), d.window.endsAt)
        assertEquals("study@${at(14)}", d.window.key)
        assertTrue(d.settled)
    }

    @Test
    fun nothingStartsBeforeTheWindowOpensOrAfterItCloses() {
        val s = listOf(focus("study", 14 * 60, 16 * 60))
        assertNull(due(at(13, 59), s))
        assertNull(due(at(16), s))
    }

    @Test
    fun blockEntriesAndDisabledOrOtherDayEntriesNeverStartABlock() {
        val block = BlockSchedule("b", "b", startMinute = 14 * 60, endMinute = 16 * 60, packages = setOf("games"))
        assertNull(due(at(15), listOf(block)))
        assertNull(due(at(15), listOf(focus("off", 14 * 60, 16 * 60, enabled = false))))
        assertNull(due(at(15), listOf(focus("fri", 14 * 60, 16 * 60, days = setOf(DayOfWeek.FRIDAY)))))
    }

    @Test
    fun aPhoneSwitchedOnMidWindowStartsForTheRestOfIt() {
        val d = due(at(15, 20), listOf(focus("study", 14 * 60, 16 * 60)))!!
        assertEquals(FocusStart.Now, d.decision)
        assertEquals("the key still names the window's own start", at(14), d.window.startsAt)
        assertEquals(at(16), d.window.endsAt)
    }

    @Test
    fun aWindowWithLessThanTenMinutesLeftIsSkippedForGood() {
        val d = due(at(15, 51), listOf(focus("study", 14 * 60, 16 * 60)))!!
        assertEquals(FocusStart.Skip(SkipReason.TOO_LITTLE_LEFT), d.decision)
        assertTrue(d.settled)
        assertEquals(FocusStart.Now, due(at(15, 50), listOf(focus("study", 14 * 60, 16 * 60)))!!.decision)
    }

    @Test
    fun aSettledWindowIsNotActedOnAgain() {
        val s = listOf(focus("study", 14 * 60, 16 * 60))
        val first = due(at(14), s)!!
        assertNull(due(at(15), s, settled = setOf(first.window.key)))
    }

    @Test
    fun tomorrowsWindowOfTheSameEntryIsAFreshWindow() {
        val s = listOf(focus("study", 14 * 60, 16 * 60))
        val today = due(at(14), s)!!
        val tomorrow = due(at(14, 0, day = 2), s, settled = setOf(today.window.key))!!
        assertEquals(FocusStart.Now, tomorrow.decision)
        assertFalse(today.window.key == tomorrow.window.key)
    }

    @Test
    fun aWindowThatCrossesMidnightKeepsTheStartOfTheDayItBeganOn() {
        val s = listOf(focus("night", 22 * 60, 2 * 60))
        val late = due(at(23), s)!!
        val after = due(at(1, 0, day = 2), s)!!
        assertEquals(at(22), late.window.startsAt)
        assertEquals(at(2, 0, day = 2), late.window.endsAt)
        assertEquals(late.window.key, after.window.key)
    }

    @Test
    fun aWindowLongerThanEightHoursIsCappedAtEight() {
        val d = due(at(10), listOf(focus("long", 8 * 60, 20 * 60)))!!
        assertEquals(at(16), d.window.endsAt)
    }

    @Test
    fun aPunishmentDaySkipsTheStart() {
        val d = due(at(14), listOf(focus("study", 14 * 60, 16 * 60)), punishment = true)!!
        assertEquals(FocusStart.Skip(SkipReason.PUNISHMENT_DAY), d.decision)
        assertTrue("settled, so it does not start when the punishment ends mid-window", d.settled)
    }

    @Test
    fun aRestDaySkipsTheStart() {
        val d = due(at(14), listOf(focus("study", 14 * 60, 16 * 60)), rest = true)!!
        assertEquals(FocusStart.Skip(SkipReason.REST_DAY), d.decision)
    }

    @Test
    fun anUrgeLockDefersTheStartToItsEndWhileTheWindowIsStillOpen() {
        val d = due(at(14, 5), listOf(focus("study", 14 * 60, 16 * 60)), urgeEnds = at(14, 15))!!
        assertEquals(FocusStart.At(at(14, 15)), d.decision)
        assertFalse("not settled: it is acted on when the lock ends", d.settled)
    }

    @Test
    fun anUrgeLockThatLeavesTooLittleOfTheWindowSkipsIt() {
        val s = listOf(focus("study", 14 * 60, 16 * 60))
        assertEquals(FocusStart.Skip(SkipReason.TOO_LITTLE_LEFT), due(at(15, 45), s, urgeEnds = at(15, 52))!!.decision)
        assertEquals(FocusStart.Skip(SkipReason.URGE_LOCK_OUTLASTS_WINDOW), due(at(15, 45), s, urgeEnds = at(16, 5))!!.decision)
    }

    @Test
    fun whenTwoWindowsAreOpenTheOneThatEndsLastIsTaken() {
        val d = due(at(15), listOf(focus("a", 14 * 60, 16 * 60), focus("b", 14 * 60 + 30, 17 * 60)))!!
        assertEquals("b", d.window.scheduleId)
    }
}
