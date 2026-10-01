package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.LockSafety
import io.github.warleysr.dechainer.models.BlockSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class BlockScheduleTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        // 2026-09-14 is a Monday, so day 0 = Monday.
        LocalDateTime.of(2026, 9, 14 + day, hour, minute)

    @Test
    fun sameDayWindow() {
        val s = BlockSchedule("1", "t", days = setOf(DayOfWeek.MONDAY), startMinute = 9 * 60, endMinute = 17 * 60)
        assertFalse(s.isActiveAt(at(0, 8, 59)))
        assertTrue(s.isActiveAt(at(0, 9, 0)))
        assertTrue(s.isActiveAt(at(0, 16, 59)))
        assertFalse(s.isActiveAt(at(0, 17, 0)))
        assertFalse(s.isActiveAt(at(1, 10, 0))) // Tuesday not selected
    }

    @Test
    fun overnightWindowBelongsToStartDay() {
        val s = BlockSchedule("1", "t", days = setOf(DayOfWeek.MONDAY), startMinute = 22 * 60, endMinute = 6 * 60)
        assertFalse(s.isActiveAt(at(0, 5, 0)))  // Monday 05:00 — window of Sunday, not selected
        assertTrue(s.isActiveAt(at(0, 23, 0)))  // Monday 23:00
        assertTrue(s.isActiveAt(at(1, 5, 59)))  // Tuesday 05:59, still Monday's window
        assertFalse(s.isActiveAt(at(1, 6, 0)))
        assertFalse(s.isActiveAt(at(1, 23, 0))) // Tuesday not selected
        assertEquals(at(1, 6, 0), s.currentWindowEnd(at(0, 23, 0)))
    }

    @Test
    fun disabledIsNeverActive() {
        val s = BlockSchedule("1", "t", enabled = false, startMinute = 0, endMinute = 1439)
        assertFalse(s.isActiveAt(at(0, 12)))
        assertNull(s.nextStartAfter(at(0, 12).atZone(ZoneId.of("UTC"))))
    }

    @Test
    fun nextBoundaryIsTheWindowEndWhileActive() {
        val zone = ZoneId.of("UTC")
        val s = BlockSchedule("1", "t", startMinute = 22 * 60, endMinute = 6 * 60)
        val now = at(0, 23).atZone(zone)
        assertEquals(at(1, 6).atZone(zone), s.boundariesAfter(now).minOrNull())
    }

    @Test
    fun websiteNormalisation() {
        assertEquals("instagram.com", BlockSchedule.normaliseWebsite(" https://www.Instagram.com/reels?x=1 "))
        assertNull(BlockSchedule.normaliseWebsite("not a site"))
    }

    @Test
    fun lockSafetyRejectsPermanentLocks() {
        val allDays = DayOfWeek.entries.toSet()
        // 24h window every day, locked: no free time ever.
        val permanent = BlockSchedule("a", "a", days = allDays, startMinute = 0, endMinute = 0, lockWhileActive = true)
        assertFalse(LockSafety.leavesEnoughFreeTime(listOf(permanent)))

        // Two locked windows that chain into each other also cover everything.
        val night = BlockSchedule("b", "b", days = allDays, startMinute = 22 * 60, endMinute = 14 * 60, lockWhileActive = true)
        val day = BlockSchedule("c", "c", days = allDays, startMinute = 14 * 60, endMinute = 22 * 60, lockWhileActive = true)
        assertFalse(LockSafety.leavesEnoughFreeTime(listOf(night, day)))

        // Same windows without the lock are fine: they can be edited with the recovery code any time.
        assertTrue(LockSafety.leavesEnoughFreeTime(listOf(night, day.copy(lockWhileActive = false))))

        // A normal nightly lock leaves plenty of free time.
        val nightly = BlockSchedule("d", "d", days = allDays, startMinute = 22 * 60, endMinute = 6 * 60, lockWhileActive = true)
        assertEquals(16 * 60, LockSafety.longestUnlockedGapMinutes(listOf(nightly)))

        // 24h lock on 6 days leaves exactly one free day.
        val sixDays = permanent.copy(days = allDays - DayOfWeek.SUNDAY)
        assertEquals(1440, LockSafety.longestUnlockedGapMinutes(listOf(sixDays)))
    }

    // ---- The entry type (blueprint D12, 13) ----

    @Test
    fun anEntryStoredBeforeTheTypeExistedIsABlockSchedule() {
        val old = org.json.JSONObject().put("id", "x").put("name", "n").put("start", 540).put("end", 1020)
        val parsed = BlockSchedule.fromJson(old)
        assertEquals(io.github.warleysr.dechainer.models.ScheduleType.BLOCK, parsed.type)
        assertFalse(parsed.isFocus)
    }

    @Test
    fun theTypeSurvivesBeingStoredAndReadBack() {
        val focus = BlockSchedule("f", "Study", startMinute = 540, endMinute = 660, type = io.github.warleysr.dechainer.models.ScheduleType.FOCUS)
        assertEquals(focus, BlockSchedule.fromJson(focus.toJson()))
        assertTrue(BlockSchedule.fromJson(focus.toJson()).isFocus)
    }

    @Test
    fun anUnknownStoredTypeFallsBackToBlock() {
        val odd = BlockSchedule("f", "n").toJson().put("type", "SOMETHING_NEW")
        assertEquals(io.github.warleysr.dechainer.models.ScheduleType.BLOCK, BlockSchedule.fromJson(odd).type)
    }

    @Test
    fun theWindowStartIsTheDayItBeganOn() {
        val s = BlockSchedule("1", "t", days = setOf(DayOfWeek.MONDAY), startMinute = 22 * 60, endMinute = 6 * 60)
        assertEquals(at(0, 22, 0), s.currentWindowStart(at(0, 23, 0)))
        assertEquals(at(0, 22, 0), s.currentWindowStart(at(1, 5, 59)))
        assertNull(s.currentWindowStart(at(1, 6, 0)))
        val day = BlockSchedule("2", "t", days = setOf(DayOfWeek.MONDAY), startMinute = 9 * 60, endMinute = 17 * 60)
        assertEquals(at(0, 9, 0), day.currentWindowStart(at(0, 12, 0)))
    }
}
