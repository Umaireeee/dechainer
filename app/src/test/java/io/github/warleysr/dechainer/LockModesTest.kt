package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.BrickStatus
import io.github.warleysr.dechainer.lock.FocusInput
import io.github.warleysr.dechainer.lock.LimitInput
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockPlanner
import io.github.warleysr.dechainer.lock.LockRestrictions
import io.github.warleysr.dechainer.lock.LockState
import io.github.warleysr.dechainer.lock.PhoneFacts
import io.github.warleysr.dechainer.lock.RunningBrick
import io.github.warleysr.dechainer.lock.UrgeInput
import io.github.warleysr.dechainer.models.BlockSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Blueprint 5.2 (one test group per mode row) and 5.3 (the precedence rules) on `LockEngine.plan`.
 * 2026-10-01 is a Thursday; everything is in UTC unless a test says otherwise.
 */
class LockModesTest {
    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute

    private val phone = PhoneFacts(
        launcherApps = setOf("chrome", "games", "clock", "notes", "mail", "settings", "sms", "home", "dechainer", "com.android.emergency"),
        protectedApps = setOf("home", "dechainer"),
        alarmApps = setOf("clock"),
        alwaysAllowed = setOf("settings", "sms", "clock", "com.android.emergency"),
        emergencyApps = setOf("com.android.emergency"),
        smsApps = setOf("sms")
    )

    private fun at(h: Int, m: Int = 0, day: Int = 1): Long =
        ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toInstant().toEpochMilli()

    private fun state(
        urge: UrgeInput = UrgeInput(), focus: FocusInput = FocusInput(),
        limits: LimitInput = LimitInput(), schedules: List<BlockSchedule> = emptyList(), deviceOwner: Boolean = true
    ) = LockState(deviceOwner, schedules, phone, focus, limits, urge)

    private fun plan(now: Long, s: LockState) = LockPlanner.plan(now, utc, s)

    private fun schedule(name: String, start: Int, end: Int, vararg pkgs: String, allowOnly: Boolean = false) =
        BlockSchedule(id = name, name = name, startMinute = start, endMinute = end, packages = pkgs.toSet(), allowOnly = allowOnly)

    /** What the blackout leaves open besides the protected apps (this app and the call screen): nothing. */
    private val everythingButHomeThisAppAndTheCallScreen =
        setOf("chrome", "games", "clock", "notes", "mail", "settings", "sms", "com.android.emergency")

    // ================= 5.2  URGE_LOCK =================

    @Test
    fun aBlackoutLeavesOpenOnlyThisAppAndTheIncomingCallScreen() {
        val p = plan(at(10), state(urge = UrgeInput(at(10, 10))))
        assertEquals(everythingButHomeThisAppAndTheCallScreen, p.desiredApps)
        assertTrue("Emergency Info is blocked", "com.android.emergency" in p.desiredApps)
        assertTrue("SMS is blocked", "sms" in p.desiredApps)
        assertTrue("the alarm clock is blocked too: only calls get through", "clock" in p.desiredApps)
        assertFalse("this app and the home screen are never taken away", "dechainer" in p.desiredApps || "home" in p.desiredApps)
    }

    @Test
    fun anUrgeLockPinsThePhoneWithTheSafetyRestrictionsUntilItsEnd() {
        val end = at(10, 10)
        val p = plan(at(10), state(urge = UrgeInput(end)))
        assertTrue(p.brick)
        assertEquals(BrickStatus(LockMode.URGE_LOCK, end, setOf(LockMode.URGE_LOCK)), p.brickStatus)
        assertTrue(p.desiredRestrictions.containsAll(LockRestrictions.BRICK))
        assertTrue(p.brickStatus!!.ownerApps.isEmpty())
        assertEquals("its end is a wake-up", end, p.nextWakeAt)
    }

    @Test
    fun anUrgeLockHoldsToTheMillisecondAndOnlyTheTimeEndsIt() {
        val end = at(10, 10)
        val s = state(urge = UrgeInput(end))
        // Nothing but the clock changes the answer, from the first moment to the last.
        for (t in listOf(at(10), at(10, 5), at(10, 9), end - 1)) assertTrue("held at $t", plan(t, s).brick)
        assertFalse("released at its end", plan(end, s).brick)
        assertTrue(plan(end, s).desiredApps.isEmpty())
        assertFalse(plan(end + hour, s).brick)
    }

    // ================= 5.2  FOCUS_BLOCK =================

    @Test
    fun aFocusBlockKeepsTheOwnersAppsTheAlarmAndTheEmergencyAppsOpen() {
        val p = plan(at(10), state(focus = FocusInput(brickEndsAt = at(14), allowedApps = setOf("notes"))))
        assertEquals(setOf("chrome", "games", "mail", "settings", "sms"), p.desiredApps)
        assertEquals(setOf("notes"), p.brickStatus?.ownerApps)
        assertEquals(LockMode.FOCUS_BLOCK, p.brickStatus?.primary)
    }

    // ================= 5.3  precedence =================

    @Test
    fun aBrickWinsOverAScheduleThatWouldOpenAnAppInItsOwnWindow() {
        // An allow-only window that lets notes through, while an urge lock runs.
        val study = schedule("study", 9 * 60, 17 * 60, "notes", allowOnly = true)
        val p = plan(at(10), state(urge = UrgeInput(at(10, 10)), schedules = listOf(study)))
        assertTrue("notes is blocked: the schedule cannot open what the brick closes", "notes" in p.desiredApps)
    }

    @Test
    fun aScheduleEndingNeverEndsABrick() {
        val short = schedule("short", 10 * 60, 10 * 60 + 5, "games")
        val s = state(urge = UrgeInput(at(10, 10)), schedules = listOf(short))
        val p = plan(at(10, 6), s)   // the window closed at 10:05
        assertTrue(p.brick)
        assertTrue(p.desiredRestrictions.containsAll(LockRestrictions.BRICK))
        assertEquals(setOf(LockMode.URGE_LOCK), p.holds.map { it.mode }.toSet())
    }

    @Test
    fun aBrickEndingLeavesTheScheduleItWasHidingInPlace() {
        val work = schedule("work", 9 * 60, 17 * 60, "games")
        val s = state(urge = UrgeInput(at(10, 10)), schedules = listOf(work))
        val after = plan(at(10, 11), s)
        assertFalse(after.brick)
        assertEquals(setOf("games"), after.desiredApps)
    }

    @Test
    fun whenABrickAndAScheduleHoldTheSameAppTheBrickIsTheOneNamed() {
        val work = schedule("work", 9 * 60, 17 * 60, "games")
        val p = plan(at(10), state(urge = UrgeInput(at(10, 10)), schedules = listOf(work)))
        val by = LockPlanner.blockedBy(p.holds, phone.protectedApps)
        assertEquals("even though the schedule lasts longer", LockMode.URGE_LOCK, by.getValue("games").mode)
        assertEquals("an app only the brick holds is named for the brick too", LockMode.URGE_LOCK, by.getValue("chrome").mode)
    }

    @Test
    fun anAppAFocusBlockAllowsIsStillBlockedByALimitOrScheduleThatBlocksIt() {
        // The stricter reading of "bricks win": a block never lets an app through another rule.
        val s = state(
            focus = FocusInput(brickEndsAt = at(14), allowedApps = setOf("notes")),
            limits = LimitInput(setOf("notes"), at(0, day = 2))
        )
        assertTrue("notes" in plan(at(10), s).desiredApps)
    }

    @Test
    fun overlappingBricksLeaveOpenOnlyWhatEveryOneOfThemAllows() {
        // A focus block that allows notes, with an urge lock inside it: the urge lock allows no owner apps
        // and blocks the emergency apps, which a focus block alone leaves open.
        val s = state(focus = FocusInput(brickEndsAt = at(12), allowedApps = setOf("notes")), urge = UrgeInput(at(10, 10)))
        val both = plan(at(10), s)
        assertTrue("notes" in both.desiredApps)
        assertTrue("com.android.emergency" in both.desiredApps)
        assertTrue(both.brickStatus!!.ownerApps.isEmpty())
        assertEquals(setOf(LockMode.URGE_LOCK, LockMode.FOCUS_BLOCK), both.brickStatus?.modes)
    }

    @Test
    fun theBrickEndsOnlyWhenEveryOneOfThemHasEnded() {
        val s = state(focus = FocusInput(brickEndsAt = at(12), allowedApps = setOf("notes")), urge = UrgeInput(at(10, 10)))
        val both = plan(at(10), s)
        assertEquals("the phone unlocks at the last end", at(12), both.brickStatus?.endsAt)
        assertEquals(LockMode.FOCUS_BLOCK, both.brickStatus?.primary)

        // The urge lock is over; the focus block is not. Its own, looser allow set applies again.
        val rest = plan(at(10, 11), s)
        assertTrue(rest.brick)
        assertFalse("notes" in rest.desiredApps)
        assertFalse("com.android.emergency" in rest.desiredApps)
        assertEquals(setOf("notes"), rest.brickStatus?.ownerApps)
        assertFalse(plan(at(12), s).brick)
    }

    @Test
    fun whenBricksEndTogetherTheUrgeIsNamedBeforeAFocusBlock() {
        val end = at(12)
        fun brick(mode: LockMode, at: Long = end) = RunningBrick(mode, at)
        assertEquals(LockMode.URGE_LOCK, LockPlanner.statusOf(listOf(brick(LockMode.FOCUS_BLOCK), brick(LockMode.URGE_LOCK)))?.primary)
        assertEquals(
            "the one that lasts longest is named, whatever its rank",
            LockMode.FOCUS_BLOCK,
            LockPlanner.statusOf(listOf(brick(LockMode.URGE_LOCK), brick(LockMode.FOCUS_BLOCK, end + 1)))?.primary
        )
        assertNull(LockPlanner.statusOf(emptyList()))
    }

    @Test
    fun theOwnersAppsThatMayOpenPinnedAreTheIntersectionOfEveryRunningBricksList() {
        val end = at(12)
        val a = RunningBrick(LockMode.FOCUS_BLOCK, end, setOf("notes", "mail"))
        val urge = RunningBrick(LockMode.URGE_LOCK, end)
        assertEquals(setOf("notes", "mail"), LockPlanner.statusOf(listOf(a))?.ownerApps)
        assertTrue("one brick that takes none empties the set", LockPlanner.statusOf(listOf(a, urge))?.ownerApps!!.isEmpty())
    }

    @Test
    fun theStatusForTheFirstFrameAgreesWithThePlan() {
        val cases = listOf(
            0L to UrgeInput(),
            at(12) to UrgeInput(),
            0L to UrgeInput(at(10, 10)),
            at(12) to UrgeInput(at(10, 10)),
            at(9) to UrgeInput(at(9, 30)),   // both are over
        )
        for ((focusEnd, urge) in cases) {
            val s = state(focus = FocusInput(brickEndsAt = focusEnd, allowedApps = setOf("notes")), urge = urge)
            assertEquals(plan(at(10), s).brickStatus, LockPlanner.quickStatus(at(10), FocusInput(brickEndsAt = focusEnd, allowedApps = setOf("notes")), urge))
        }
    }

    @Test
    fun withoutDeviceOwnerNoBrickIsApplied() {
        val p = plan(at(12), state(urge = UrgeInput(at(12, 5)), deviceOwner = false))
        assertFalse(p.brick)
        assertTrue(p.desiredApps.isEmpty())
    }

    @Test
    fun thereIsNoPunishmentModeAnyMore() {
        assertEquals(setOf(LockMode.URGE_LOCK, LockMode.FOCUS_BLOCK), LockMode.entries.filter { it.isBrick }.toSet())
    }
}
