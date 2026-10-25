package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.ExitWait
import io.github.warleysr.dechainer.lock.LockdownInput
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockPlanner
import io.github.warleysr.dechainer.lock.LockRestrictions
import io.github.warleysr.dechainer.lock.LockState
import io.github.warleysr.dechainer.lock.PhoneFacts
import io.github.warleysr.dechainer.lock.StudyInput
import io.github.warleysr.dechainer.lock.StudyRules
import io.github.warleysr.dechainer.lock.StudyRules.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pins study mode and lockdown: started by hand, only ever longer, the lock closes every way around
 * itself while it runs and nothing is left behind when it ends.
 */
class StudyRulesTest {
    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 3_600_000L

    private val phone = PhoneFacts(
        launcherApps = setOf(
            "com.whatsapp", "com.brave.browser", "com.google.android.youtube", "com.android.settings",
            "com.android.documentsui", "com.miui.home", StudyRules.GEMINI, StudyRules.GOOGLE_APP, "com.android.camera"
        ),
        protectedApps = setOf("com.miui.home"),
        alarmApps = setOf("com.android.deskclock"),
        alwaysAllowed = setOf("com.android.settings", "com.android.documentsui", "com.android.camera"),
        emergencyApps = setOf("com.android.emergency")
    )

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val now = at(25, 8, 0)

    private fun state(
        study: StudyInput = StudyInput(),
        lockdown: LockdownInput = LockdownInput(),
        deviceOwner: Boolean = true,
        phone: PhoneFacts = this.phone
    ) = LockState(deviceOwner, emptyList(), phone, study = study, lockdown = lockdown)

    private fun plan(s: LockState, at: Long = now) = LockPlanner.plan(at, zone, s)

    // ---- Starting and extending ----

    @Test
    fun aSessionStartsWhenNothingRuns() {
        assertEquals(Start.Started(now + 5 * hour), StudyRules.decide(now, 0L, now + 5 * hour, deviceOwner = true))
    }

    @Test
    fun withoutDeviceOwnerNothingStarts() {
        assertEquals(Start.NoDeviceOwner, StudyRules.decide(now, 0L, now + hour, deviceOwner = false))
    }

    @Test
    fun aSessionShorterThanTenMinutesIsRefused() {
        assertEquals(Start.TooShort, StudyRules.decide(now, 0L, now + 9 * minute, true))
        assertEquals(Start.TooShort, StudyRules.decide(now, 0L, now, true))
        assertEquals(Start.TooShort, StudyRules.decide(now, 0L, now - hour, true))
        assertEquals(Start.Started(now + 10 * minute), StudyRules.decide(now, 0L, now + 10 * minute, true))
    }

    @Test
    fun aSessionLongerThanTwelveHoursIsRefused() {
        assertEquals(Start.Started(now + 12 * hour), StudyRules.decide(now, 0L, now + 12 * hour, true))
        assertEquals(Start.TooLong, StudyRules.decide(now, 0L, now + 12 * hour + 1, true))
    }

    @Test
    fun aRunningSessionCanBeExtended() {
        val running = now + 2 * hour
        assertEquals(Start.Extended(now + 5 * hour), StudyRules.decide(now, running, now + 5 * hour, true))
    }

    @Test
    fun aRunningSessionIsNeverShortenedOrEndedEarly() {
        val running = now + 5 * hour
        assertEquals(Start.NotLonger, StudyRules.decide(now, running, now + 2 * hour, true))
        assertEquals(Start.NotLonger, StudyRules.decide(now, running, running, true))
        assertEquals(Start.NotLonger, StudyRules.decide(now, running, now, true))
        assertEquals(Start.NotLonger, StudyRules.decide(now, running, 0L, true))
    }

    @Test
    fun anExtensionCannotRunPastTwelveHoursFromNow() {
        val running = now + 2 * hour
        assertEquals(Start.TooLong, StudyRules.decide(now, running, now + 13 * hour, true))
        assertEquals(Start.Extended(now + 12 * hour), StudyRules.decide(now, running, now + 12 * hour, true))
    }

    @Test
    fun aSessionThatHasEndedCountsAsNoneRunning() {
        assertEquals(Start.Started(now + hour), StudyRules.decide(now, now, now + hour, true))
        assertEquals(Start.Started(now + hour), StudyRules.decide(now, now - hour, now + hour, true))
        assertEquals(Start.TooShort, StudyRules.decide(now, now - hour, now + minute, true))
    }

    // ---- "Until 13:00" ----

    @Test
    fun anEndTimeStillAheadTodayIsToday() {
        assertEquals(at(25, 13, 0), StudyRules.nextOccurrence(now, zone, 13 * 60))
        assertEquals(at(25, 8, 1), StudyRules.nextOccurrence(now, zone, 8 * 60 + 1))
    }

    @Test
    fun anEndTimeAlreadyPastIsTomorrow() {
        assertEquals(at(26, 7, 59), StudyRules.nextOccurrence(now, zone, 7 * 60 + 59))
        assertEquals(at(26, 8, 0), StudyRules.nextOccurrence(now, zone, 8 * 60))
        assertEquals(at(26, 0, 0), StudyRules.nextOccurrence(now, zone, 0))
    }

    @Test
    fun theLocalClockDecidesNotUtc() {
        val karachi = ZoneId.of("Asia/Karachi")
        // 21:21 in Karachi is 16:21 UTC.
        val local = LocalDateTime.of(2026, 10, 25, 21, 21).atZone(karachi).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(karachi).toInstant().toEpochMilli()
        assertEquals(expected, StudyRules.nextOccurrence(local, karachi, 8 * 60))
    }

    @Test
    fun anEightToOneDayWorksFromTheMorning() {
        val end = StudyRules.nextOccurrence(now, zone, 13 * 60)
        assertEquals(Start.Started(end), StudyRules.decide(now, 0L, end, true))
        assertEquals(5 * hour, end - now)
    }

    // ---- Lockdown lists ----

    @Test
    fun aRunningLockdownOnlyGainsApps() {
        assertEquals(setOf("a", "b", "c"), StudyRules.lockdownApps(true, setOf("a", "b"), setOf("c")))
        assertEquals(setOf("a", "b"), StudyRules.lockdownApps(true, setOf("a", "b"), setOf("a")))
        assertEquals(setOf("a", "b"), StudyRules.lockdownApps(true, setOf("a", "b"), emptySet()))
    }

    @Test
    fun aNewLockdownTakesThePickedList() {
        assertEquals(setOf("c"), StudyRules.lockdownApps(false, setOf("a", "b"), setOf("c")))
    }

    @Test
    fun aLockdownNeverPausesTheEssentials() {
        val picked = setOf("com.whatsapp", "com.miui.home", "com.android.settings", "com.android.camera", "com.android.emergency", "com.android.deskclock")
        assertEquals(setOf("com.whatsapp"), StudyRules.lockdownBlocked(phone, picked))
    }

    @Test
    fun aLockdownNeedsAtLeastOneApp() {
        assertEquals(Start.NothingPicked, StudyRules.decideLockdown(now, 0L, emptySet(), now + hour, emptySet(), true))
        // A running lockdown always has apps, so extending it with an empty pick is still an extension.
        assertEquals(
            Start.Extended(now + 3 * hour),
            StudyRules.decideLockdown(now, now + hour, setOf("a"), now + 3 * hour, emptySet(), true)
        )
    }

    @Test
    fun aLockdownStartsWithThePickedApps() {
        assertEquals(Start.Started(now + hour), StudyRules.decideLockdown(now, 0L, setOf("old"), now + hour, setOf("a"), true))
        assertEquals(Start.TooShort, StudyRules.decideLockdown(now, 0L, emptySet(), now + minute, setOf("a"), true))
        assertEquals(Start.NoDeviceOwner, StudyRules.decideLockdown(now, 0L, emptySet(), now + hour, setOf("a"), false))
    }

    @Test
    fun aRunningLockdownCanGainAppsWithoutBeingLengthened() {
        val ends = now + 2 * hour
        assertEquals(Start.AppsAdded(ends), StudyRules.decideLockdown(now, ends, setOf("a"), ends, setOf("b"), true))
        // Asking for an earlier end while adding an app keeps the end it had.
        assertEquals(Start.AppsAdded(ends), StudyRules.decideLockdown(now, ends, setOf("a"), now + hour, setOf("b"), true))
    }

    @Test
    fun aRunningLockdownIsNeverShortenedAndNoAppIsEverTakenOut() {
        val ends = now + 2 * hour
        assertEquals(Start.NotLonger, StudyRules.decideLockdown(now, ends, setOf("a", "b"), now + hour, setOf("a"), true))
        assertEquals(Start.NotLonger, StudyRules.decideLockdown(now, ends, setOf("a", "b"), ends, emptySet(), true))
    }

    @Test
    fun aRunningLockdownCanBeExtendedAndGainAppsAtOnce() {
        val ends = now + hour
        assertEquals(Start.Extended(now + 4 * hour), StudyRules.decideLockdown(now, ends, setOf("a"), now + 4 * hour, setOf("b"), true))
        assertEquals(Start.TooLong, StudyRules.decideLockdown(now, ends, setOf("a"), now + 13 * hour, setOf("b"), true))
    }

    @Test
    fun anEndedLockdownIsReplacedNotAdded() {
        assertEquals(Start.Started(now + hour), StudyRules.decideLockdown(now, now - hour, setOf("a"), now + hour, setOf("b"), true))
    }

    // ---- Study mode in the plan ----

    @Test
    fun everyAppButTheEssentialsAndGeminiIsPausedWhileStudying() {
        val blocked = plan(state(study = StudyInput(now + 5 * hour))).desiredApps
        assertTrue("com.whatsapp" in blocked)
        assertTrue("com.brave.browser" in blocked)
        assertTrue("com.google.android.youtube" in blocked)
        assertFalse(StudyRules.GEMINI in blocked)
        assertFalse("com.miui.home" in blocked)
        assertFalse("com.android.settings" in blocked)
        assertFalse("com.android.camera" in blocked)
    }

    @Test
    fun theGoogleAppIsHiddenNotPaused() {
        val p = plan(state(study = StudyInput(now + 5 * hour)))
        assertFalse(StudyRules.GOOGLE_APP in p.desiredApps)
        assertEquals(setOf(StudyRules.GOOGLE_APP), p.desiredHidden)
        assertTrue(p.desiredApps.intersect(p.desiredHidden).isEmpty())
    }

    @Test
    fun anAppThePhoneCannotDoWithoutIsNeverHidden() {
        val guarded = phone.copy(protectedApps = phone.protectedApps + StudyRules.GOOGLE_APP)
        val p = plan(state(study = StudyInput(now + hour), phone = guarded))
        assertTrue(p.desiredHidden.isEmpty())
        assertFalse(StudyRules.GOOGLE_APP in p.desiredApps)
    }

    @Test
    fun theFilePickerIsClosedWhenItIsNotOnTheAlwaysAllowedList() {
        val noPickers = phone.copy(alwaysAllowed = phone.alwaysAllowed - StudyRules.FILE_PICKERS)
        assertTrue("com.android.documentsui" in plan(state(study = StudyInput(now + hour), phone = noPickers)).desiredApps)
        assertFalse("com.android.documentsui" in plan(state(study = StudyInput(now + hour))).desiredApps)
    }

    @Test
    fun everyWayAroundTheLockIsClosedWhileStudying() {
        val restrictions = plan(state(study = StudyInput(now + hour))).desiredRestrictions
        assertTrue(restrictions.containsAll(LockRestrictions.STUDY))
        listOf(
            LockRestrictions.SAFE_BOOT, LockRestrictions.DEBUGGING, LockRestrictions.FACTORY_RESET,
            LockRestrictions.ADD_USER, LockRestrictions.USER_SWITCH, LockRestrictions.INSTALL_APPS,
            LockRestrictions.INSTALL_UNKNOWN_SOURCES, LockRestrictions.UNINSTALL_APPS, LockRestrictions.APPS_CONTROL,
            LockRestrictions.ADD_CLONE_PROFILE, LockRestrictions.ADD_MANAGED_PROFILE, LockRestrictions.DATE_TIME
        ).forEach { assertTrue(it, it in restrictions) }
    }

    @Test
    fun studyModeNeverPinsThePhoneSoTheHotspotStaysInTheShade() {
        val p = plan(state(study = StudyInput(now + hour)))
        assertFalse(p.brick)
        assertEquals(null, p.brickStatus)
    }

    @Test
    fun studyModeWakesTheEngineAtItsEnd() {
        assertEquals(now + 5 * hour, plan(state(study = StudyInput(now + 5 * hour))).nextWakeAt)
    }

    @Test
    fun theHoldIsNamedStudy() {
        val holds = plan(state(study = StudyInput(now + hour))).holds
        assertEquals(listOf(LockMode.STUDY), holds.map { it.mode })
    }

    // ---- When it ends, the phone is normal ----

    @Test
    fun theMomentTheEndTimePassesNothingIsPausedOrHidden() {
        val end = now + 5 * hour
        val s = state(study = StudyInput(end))
        assertTrue(plan(s, at = end - 1).desiredApps.isNotEmpty())
        val after = plan(s, at = end)
        assertTrue(after.desiredApps.isEmpty())
        assertTrue(after.desiredHidden.isEmpty())
        assertTrue(after.holds.isEmpty())
    }

    @Test
    fun afterwardsOnlyTheAlwaysOnRulesRemain() {
        val end = now + hour
        val restrictions = plan(state(study = StudyInput(end)), at = end).desiredRestrictions
        assertEquals(setOf(LockRestrictions.DATE_TIME), restrictions)
        // Other apps can be uninstalled again; only this app stays protected (by SystemGuard, not by a plan rule).
        assertFalse(LockRestrictions.UNINSTALL_APPS in restrictions)
        assertFalse(LockRestrictions.APPS_CONTROL in restrictions)
        assertFalse(LockRestrictions.INSTALL_APPS in restrictions)
    }

    @Test
    fun withNothingRunningNothingIsPaused() {
        val p = plan(state())
        assertTrue(p.desiredApps.isEmpty())
        assertTrue(p.desiredHidden.isEmpty())
        assertEquals(setOf(LockRestrictions.DATE_TIME), p.desiredRestrictions)
        assertEquals(null, p.nextWakeAt)
    }

    @Test
    fun withoutDeviceOwnerNothingIsApplied() {
        val p = plan(state(study = StudyInput(now + hour), deviceOwner = false))
        assertTrue(p.desiredApps.isEmpty())
        assertTrue(p.desiredHidden.isEmpty())
        assertTrue(p.desiredRestrictions.isEmpty())
    }

    // ---- Lockdown in the plan ----

    @Test
    fun aLockdownPausesOnlyThePickedApps() {
        val p = plan(state(lockdown = LockdownInput(now + 2 * hour, setOf("com.whatsapp", "com.google.android.youtube"))))
        assertEquals(setOf("com.whatsapp", "com.google.android.youtube"), p.desiredApps)
        assertTrue(p.desiredHidden.isEmpty())
        assertEquals(listOf(LockMode.LOCKDOWN), p.holds.map { it.mode })
        assertFalse(p.brick)
    }

    @Test
    fun aLockdownOnlyKeepsThePausedAppsFromBeingRemoved() {
        val restrictions = plan(state(lockdown = LockdownInput(now + hour, setOf("com.whatsapp")))).desiredRestrictions
        assertTrue(LockRestrictions.UNINSTALL_APPS in restrictions)
        assertTrue(LockRestrictions.APPS_CONTROL in restrictions)
        assertFalse(LockRestrictions.INSTALL_APPS in restrictions)
        assertFalse(LockRestrictions.SAFE_BOOT in restrictions)
    }

    @Test
    fun aLockdownWithNoAppsHoldsNothing() {
        val p = plan(state(lockdown = LockdownInput(now + hour, emptySet())))
        assertTrue(p.holds.isEmpty())
        assertEquals(setOf(LockRestrictions.DATE_TIME), p.desiredRestrictions)
    }

    @Test
    fun anEndedLockdownLetsEverythingGoAndKeepsNoRules() {
        val end = now + hour
        val p = plan(state(lockdown = LockdownInput(end, setOf("com.whatsapp"))), at = end)
        assertTrue(p.desiredApps.isEmpty())
        assertEquals(setOf(LockRestrictions.DATE_TIME), p.desiredRestrictions)
    }

    @Test
    fun aLockdownDuringStudyAddsNothingToWhatStudyAlreadyPauses() {
        val s = state(
            study = StudyInput(now + 5 * hour),
            lockdown = LockdownInput(now + 2 * hour, setOf("com.whatsapp"))
        )
        val during = plan(s)
        assertEquals(plan(state(study = StudyInput(now + 5 * hour))).desiredApps, during.desiredApps)
        // The lockdown ending first changes nothing while study runs; the study end is the last wake-up.
        val afterLockdown = plan(s, at = now + 3 * hour)
        assertEquals(during.desiredApps, afterLockdown.desiredApps)
        assertEquals(now + 5 * hour, afterLockdown.nextWakeAt)
    }

    // ---- The exit wait ----

    @Test
    fun theWaitIsSplitIntoDaysAndHours() {
        assertEquals(7L to 0L, ExitWait.splitWait(7 * 24 * hour))
        assertEquals(6L to 23L, ExitWait.splitWait(7 * 24 * hour - hour))
        // A part of an hour counts as a whole hour, so the screen never shows 0 hours while time is left.
        assertEquals(7L to 0L, ExitWait.splitWait(7 * 24 * hour - 1))
        assertEquals(0L to 1L, ExitWait.splitWait(1))
        assertEquals(0L to 0L, ExitWait.splitWait(0))
    }

    @Test
    fun minutesLeftRoundsUpAndNeverShowsZeroWhileTimeRemains() {
        assertEquals(5L, StudyRules.minutesLeft(now, now + 5 * minute))
        assertEquals(1L, StudyRules.minutesLeft(now, now + 1))
        assertEquals(0L, StudyRules.minutesLeft(now, now))
        assertEquals(0L, StudyRules.minutesLeft(now, now - minute))
    }
}
