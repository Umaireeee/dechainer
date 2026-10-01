package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.FocusInput
import io.github.warleysr.dechainer.lock.Hold
import io.github.warleysr.dechainer.lock.LimitInput
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockPlanner
import io.github.warleysr.dechainer.lock.LockRestrictions
import io.github.warleysr.dechainer.lock.LockState
import io.github.warleysr.dechainer.lock.PhoneFacts
import io.github.warleysr.dechainer.models.BlockSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** `LockEngine.plan`: every rule that decides what is locked, with no Android. 2026-10-01 is a Thursday. */
class LockPlannerTest {
    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute

    private val phone = PhoneFacts(
        launcherApps = setOf("chrome", "games", "clock", "notes", "settings", "sms", "home", "dechainer", "com.android.emergency"),
        protectedApps = setOf("home", "dechainer"),
        alarmApps = setOf("clock"),
        alwaysAllowed = setOf("settings", "sms")
    )

    private fun at(h: Int, m: Int = 0, day: Int = 1, zone: ZoneId = utc): Long =
        ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun schedule(
        name: String, start: Int, end: Int, vararg pkgs: String,
        allowOnly: Boolean = false, restrictions: Set<String> = emptySet(), websites: Set<String> = emptySet(),
        days: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    ) = BlockSchedule(
        id = name, name = name, days = days, startMinute = start, endMinute = end,
        packages = pkgs.toSet(), restrictions = restrictions, websites = websites, allowOnly = allowOnly
    )

    private fun state(
        schedules: List<BlockSchedule> = emptyList(), focus: FocusInput = FocusInput(), limits: LimitInput = LimitInput(),
        extra: List<Hold> = emptyList(), deviceOwner: Boolean = true
    ) = LockState(deviceOwner, schedules, phone, focus, limits, extra)

    private fun plan(now: Long, s: LockState, zone: ZoneId = utc) = LockPlanner.plan(now, zone, s)

    // ---- Nothing running ----

    @Test
    fun withNothingRunningNothingIsBlockedButTheClockStaysLocked() {
        val p = plan(at(12), state())
        assertTrue(p.holds.isEmpty())
        assertTrue(p.desiredApps.isEmpty())
        assertEquals(setOf(LockRestrictions.DATE_TIME), p.desiredRestrictions)
        assertTrue("date, time and zone are locked all the time (9.2)", p.holdClock)
        assertFalse(p.brick)
        assertNull(p.nextWakeAt)
    }

    @Test
    fun withoutDeviceOwnerNothingIsAppliedButTheNextBoundaryIsStillWatched() {
        val p = plan(at(8), state(listOf(schedule("work", 9 * 60, 17 * 60, "games")), deviceOwner = false))
        assertTrue(p.desiredApps.isEmpty())
        assertTrue(p.desiredRestrictions.isEmpty())
        assertFalse(p.holdClock)
        assertEquals(at(9), p.nextWakeAt)
    }

    // ---- SCHEDULE ----

    @Test
    fun anOpenWindowBlocksItsAppsUntilItsEnd() {
        val p = plan(at(10), state(listOf(schedule("work", 9 * 60, 17 * 60, "games", "chrome"))))
        assertEquals(setOf("games", "chrome"), p.desiredApps)
        assertEquals(LockMode.SCHEDULE, p.holds.single().mode)
        assertEquals("work", p.holds.single().name)
        assertEquals(at(17), p.holds.single().endsAt)
        assertEquals(at(17), p.nextWakeAt)
    }

    @Test
    fun aClosedWindowBlocksNothingAndTheNextWakeIsItsStart() {
        val p = plan(at(8), state(listOf(schedule("work", 9 * 60, 17 * 60, "games"))))
        assertTrue(p.desiredApps.isEmpty())
        assertEquals(at(9), p.nextWakeAt)
    }

    @Test
    fun theWindowBoundariesAreStartInclusiveEndExclusive() {
        val s = state(listOf(schedule("work", 9 * 60, 17 * 60, "games")))
        assertTrue(plan(at(9), s).desiredApps.isNotEmpty())
        assertTrue(plan(at(8, 59), s).desiredApps.isEmpty())
        assertTrue(plan(at(16, 59), s).desiredApps.isNotEmpty())
        assertTrue(plan(at(17), s).desiredApps.isEmpty())
    }

    @Test
    fun aWindowAcrossMidnightIsOpenOnBothSidesAndEndsTheNextMorning() {
        val s = state(listOf(schedule("night", 22 * 60, 6 * 60, "games")))
        val late = plan(at(23), s)
        assertEquals(setOf("games"), late.desiredApps)
        assertEquals(at(6, day = 2), late.holds.single().endsAt)
        val early = plan(at(2, day = 2), s)
        assertEquals(setOf("games"), early.desiredApps)
        assertEquals(at(6, day = 2), early.holds.single().endsAt)
        assertTrue(plan(at(7, day = 2), s).desiredApps.isEmpty())
    }

    @Test
    fun aWindowOnlyRunsOnItsDays() {
        val thursdayOnly = state(listOf(schedule("w", 9 * 60, 17 * 60, "games", days = setOf(DayOfWeek.THURSDAY))))
        assertTrue(plan(at(10, day = 1), thursdayOnly).desiredApps.isNotEmpty())    // Thursday
        assertTrue(plan(at(10, day = 2), thursdayOnly).desiredApps.isEmpty())        // Friday
    }

    @Test
    fun aDisabledScheduleDoesNothing() {
        val off = schedule("w", 9 * 60, 17 * 60, "games").copy(enabled = false)
        val p = plan(at(10), state(listOf(off)))
        assertTrue(p.desiredApps.isEmpty())
        assertNull(p.nextWakeAt)
    }

    @Test
    fun anOpenWindowAddsItsRestrictionsAndWebsites() {
        val s = schedule("w", 9 * 60, 17 * 60, "games", restrictions = setOf("no_install_apps"), websites = setOf("example.com"))
        val p = plan(at(10), state(listOf(s)))
        assertEquals(setOf("no_install_apps", LockRestrictions.DATE_TIME), p.desiredRestrictions)
        assertEquals(setOf("example.com"), p.desiredSites)
        // And none of it once it closes.
        val after = plan(at(18), state(listOf(s)))
        assertEquals(setOf(LockRestrictions.DATE_TIME), after.desiredRestrictions)
        assertTrue(after.desiredSites.isEmpty())
    }

    @Test
    fun anAllowOnlyWindowBlocksEverythingElseExceptTheEssentials() {
        val p = plan(at(10), state(listOf(schedule("study", 9 * 60, 17 * 60, "notes", allowOnly = true))))
        // Not blocked: notes (allowed), home and dechainer (protected), settings and sms (always allowed).
        assertEquals(setOf("chrome", "games", "clock", "com.android.emergency"), p.desiredApps)
    }

    @Test
    fun aProtectedAppIsNeverBlockedEvenWhenAScheduleListsIt() {
        val p = plan(at(10), state(listOf(schedule("w", 9 * 60, 17 * 60, "games", "home", "dechainer"))))
        assertEquals(setOf("games"), p.desiredApps)
    }

    @Test
    fun theWindowsAreReadInTheZoneGiven() {
        // 10:00 UTC is 15:00 in Karachi (inside 09-17) and 23:00 in Auckland (outside it).
        val s = state(listOf(schedule("w", 9 * 60, 17 * 60, "games")))
        val instant = at(10)
        assertTrue(plan(instant, s, ZoneId.of("Asia/Karachi")).desiredApps.isNotEmpty())
        assertTrue(plan(instant, s, ZoneId.of("Pacific/Auckland")).desiredApps.isEmpty())
    }

    // ---- DAILY_LIMIT ----

    @Test
    fun anAppOutOfTimeStaysBlockedUntilTheDayResets() {
        val s = state(limits = LimitInput(reachedApps = setOf("games"), resetsAt = at(0, day = 2)))
        val p = plan(at(15), s)
        assertEquals(setOf("games"), p.desiredApps)
        assertEquals(LockMode.DAILY_LIMIT, p.holds.single().mode)
        assertEquals(at(0, day = 2), p.nextWakeAt)
    }

    @Test
    fun aLimitThatHasResetBlocksNothing() {
        val s = state(limits = LimitInput(reachedApps = setOf("games"), resetsAt = at(0, day = 2)))
        assertTrue(plan(at(0, day = 2), s).desiredApps.isEmpty())
    }

    @Test
    fun noLimitReachedMeansNoHold() {
        assertTrue(plan(at(12), state(limits = LimitInput())).holds.isEmpty())
    }

    // ---- FOCUS_BLOCK ----

    @Test
    fun aRunningBlockIsABrickWithItsSafetyRestrictionsAndItsEndTime() {
        val end = at(14, 30)
        val p = plan(at(13), state(focus = FocusInput(brickEndsAt = end, allowedApps = setOf("notes"))))
        assertTrue(p.brick)
        assertFalse(p.expiredFocusBlock)
        assertTrue(p.desiredRestrictions.containsAll(LockRestrictions.BRICK))
        assertTrue(LockRestrictions.DATE_TIME in p.desiredRestrictions)
        assertEquals(end, p.holds.single().endsAt)
        assertEquals(end, p.nextWakeAt)
    }

    @Test
    fun aBrickSuspendsEverythingExceptTheEssentialsAlarmsEmergencyAndAllowedApps() {
        val p = plan(at(13), state(focus = FocusInput(brickEndsAt = at(14), allowedApps = setOf("notes"))))
        // chrome and games go. notes is allowed, clock is the alarm, emergency never, home/dechainer protected,
        // and settings and sms are NOT spared by a brick (only an allow-only window spares them).
        assertEquals(setOf("chrome", "games", "settings", "sms"), p.desiredApps)
    }

    @Test
    fun aBlockWhoseEndHasPassedIsOverWithoutAnyAlarm() {
        // The acceptance case: the end alarm never fired. The next wake-up, an hour late, ends it.
        val end = at(14, 30)
        val p = plan(end + hour, state(focus = FocusInput(brickEndsAt = end)))
        assertFalse("no brick", p.brick)
        assertTrue("flagged so the stored block gets closed", p.expiredFocusBlock)
        assertTrue("nothing is blocked", p.desiredApps.isEmpty())
        assertTrue("the safety restrictions are gone", p.desiredRestrictions.none { it in LockRestrictions.BRICK })
        assertTrue(p.holds.isEmpty())
    }

    @Test
    fun theBlockEndsAtItsEndTimeToTheMillisecond() {
        val end = at(14, 30)
        val s = state(focus = FocusInput(brickEndsAt = end))
        assertTrue(plan(end - 1, s).brick)
        val atEnd = plan(end, s)
        assertFalse("end <= now means over", atEnd.brick)
        assertTrue(atEnd.expiredFocusBlock)
        assertTrue(plan(end + 1, s).expiredFocusBlock)
    }

    @Test
    fun noBlockRecordedMeansNothingToClose() {
        val p = plan(at(12), state(focus = FocusInput(brickEndsAt = 0)))
        assertFalse(p.expiredFocusBlock)
        assertFalse(p.brick)
    }

    @Test
    fun planningTheSameMomentTwiceGivesTheSamePlan() {
        val s = state(
            schedules = listOf(schedule("w", 9 * 60, 17 * 60, "games")),
            focus = FocusInput(brickEndsAt = at(14), allowedApps = setOf("notes")),
            limits = LimitInput(setOf("chrome"), at(0, day = 2))
        )
        assertEquals(plan(at(12), s), plan(at(12), s))
    }

    // ---- Several holds at once ----

    @Test
    fun overlappingHoldsBlockTheUnionAndAnAppStaysBlockedUntilTheLastEnds() {
        val s = state(
            schedules = listOf(schedule("work", 9 * 60, 17 * 60, "games", "chrome")),
            limits = LimitInput(setOf("games"), at(0, day = 2))
        )
        val p = plan(at(10), s)
        assertEquals(setOf("games", "chrome"), p.desiredApps)
        val by = LockPlanner.blockedBy(p.holds, phone.protectedApps)
        assertEquals("games is held by the limit, which lasts longer", at(0, day = 2), by.getValue("games").endsAt)
        assertEquals("chrome only by the window", at(17), by.getValue("chrome").endsAt)
    }

    @Test
    fun aBrickWinsOverAScheduleThatWouldHaveLetAnAppThrough() {
        // The window blocks only games; the brick blocks chrome as well.
        val s = state(
            schedules = listOf(schedule("work", 9 * 60, 17 * 60, "games")),
            focus = FocusInput(brickEndsAt = at(14))
        )
        assertTrue("chrome" in plan(at(10), s).desiredApps)
    }

    @Test
    fun theNextWakeIsTheEarliestThingThatCouldChange() {
        val s = state(
            schedules = listOf(schedule("work", 9 * 60, 13 * 60, "games")),
            focus = FocusInput(brickEndsAt = at(12))
        )
        assertEquals("the brick ends before the window", at(12), plan(at(10), s).nextWakeAt)
        val s2 = state(
            schedules = listOf(schedule("work", 9 * 60, 11 * 60, "games")),
            focus = FocusInput(brickEndsAt = at(12))
        )
        assertEquals("the window ends before the brick", at(11), plan(at(10), s2).nextWakeAt)
    }

    // ---- The old locked session, and the holds carried until Phase 2 ----

    @Test
    fun aLockedSessionOutsideABlockLetsOnlyAllowedAppsThrough() {
        val p = plan(at(10), state(focus = FocusInput(sessionLockEndsAt = at(10, 25), allowedApps = setOf("notes"))))
        assertEquals(setOf("chrome", "games", "clock", "com.android.emergency"), p.desiredApps)
        assertFalse("it is not a brick", p.brick)
        assertEquals(LockMode.FOCUS_SESSION, p.holds.single().mode)
    }

    @Test
    fun aPausedLockedSessionHoldsUntilResumedAndSetsNoWakeOfItsOwn() {
        val p = plan(at(10), state(focus = FocusInput(sessionLockEndsAt = Long.MAX_VALUE)))
        assertEquals(LockMode.FOCUS_SESSION, p.holds.single().mode)
        assertNull(p.nextWakeAt)
    }

    @Test
    fun aLockedSessionThatHasEndedBlocksNothing() {
        val p = plan(at(10), state(focus = FocusInput(sessionLockEndsAt = at(9, 59))))
        assertTrue(p.holds.isEmpty())
    }

    @Test
    fun aBlockTakesTheBrickPathNotTheSessionPath() {
        val p = plan(at(10), state(focus = FocusInput(brickEndsAt = at(11), sessionLockEndsAt = at(10, 20))))
        assertEquals(listOf(LockMode.FOCUS_BLOCK), p.holds.map { it.mode })
    }

    @Test
    fun carriedHoldsApplyUntilTheirEndAndThenDrop() {
        val extra = listOf(
            Hold(LockMode.IMPULSE_LOCK, "impulse", at(10, 30), setOf("games")),
            Hold(LockMode.RIDE_LOCK, "ride", at(9, 59), setOf("chrome"))
        )
        val p = plan(at(10), state(extra = extra))
        assertEquals(setOf("games"), p.desiredApps)
        assertEquals(at(10, 30), p.nextWakeAt)
    }
}
