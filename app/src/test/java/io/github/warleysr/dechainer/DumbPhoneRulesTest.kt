package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.DumbPhoneRules
import io.github.warleysr.dechainer.lock.LockPlanner
import io.github.warleysr.dechainer.lock.LockRestrictions
import io.github.warleysr.dechainer.lock.LockState
import io.github.warleysr.dechainer.lock.PhoneFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Pins the dumb-phone lock: locked except 21:00 to 22:30, every way around it closed at all hours. */
class DumbPhoneRulesTest {
    private val zone = ZoneId.of("UTC")

    private val phone = PhoneFacts(
        launcherApps = setOf("com.whatsapp", "com.brave.browser", "com.google.android.youtube", "com.android.settings", "com.android.documentsui", "com.miui.home"),
        protectedApps = setOf("com.miui.home"),
        alarmApps = emptySet(),
        alwaysAllowed = setOf("com.android.settings", "com.android.documentsui")
    )

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun plan(dumb: Boolean = true, now: Long = at(20, 10, 7), phone: PhoneFacts = this.phone) =
        LockPlanner.plan(now, zone, LockState(true, if (dumb) listOf(DumbPhoneRules.schedule()) else emptyList(), phone, dumbPhone = dumb))

    @Test
    fun theLockIsOnExceptInTheOpenWindowEveryDay() {
        val s = DumbPhoneRules.schedule()
        for (day in 19..25) for (hour in 0..23) for (minute in listOf(0, 1, 29, 30, 31, 59)) {
            val open = DumbPhoneRules.isOpenAt(hour * 60 + minute)
            assertEquals("$day $hour:$minute", !open, s.isActiveAt(LocalDateTime.of(2026, 10, day, hour, minute)))
        }
    }

    @Test
    fun theOpenWindowIs2100To2230() {
        assertFalse(DumbPhoneRules.isOpenAt(20 * 60 + 59))
        assertTrue(DumbPhoneRules.isOpenAt(21 * 60))
        assertTrue(DumbPhoneRules.isOpenAt(22 * 60 + 29))
        assertFalse(DumbPhoneRules.isOpenAt(22 * 60 + 30))
        assertEquals("21:00 to 22:30", DumbPhoneRules.openHoursLabel())
    }

    @Test
    fun everyAppIsPausedWhileLocked() {
        val blocked = plan(now = at(20, 10, 7)).desiredApps
        assertTrue("com.whatsapp" in blocked)
        assertTrue("com.brave.browser" in blocked)
        assertTrue("com.google.android.youtube" in blocked)
    }

    @Test
    fun nothingIsPausedInTheOpenWindow() {
        for (time in listOf(at(20, 21, 0), at(20, 21, 45), at(20, 22, 29))) {
            assertTrue(plan(now = time).desiredApps.isEmpty())
        }
        assertTrue("com.whatsapp" in plan(now = at(20, 22, 30)).desiredApps)
    }

    @Test
    fun theRestrictionsStayOnInTheOpenWindow() {
        val restrictions = plan(now = at(20, 21, 30)).desiredRestrictions
        assertTrue(restrictions.containsAll(LockRestrictions.DUMB))
    }

    @Test
    fun theLauncherAndSettingsStayOpen() {
        val blocked = plan().desiredApps
        assertFalse("com.miui.home" in blocked)
        assertFalse("com.android.settings" in blocked)
    }

    @Test
    fun theFilePickerIsClosedWhenItIsNotOnTheAlwaysAllowedList() {
        val noPickers = phone.copy(alwaysAllowed = phone.alwaysAllowed - DumbPhoneRules.FILE_PICKERS)
        assertTrue("com.android.documentsui" in plan(phone = noPickers).desiredApps)
        assertFalse("com.android.documentsui" in plan().desiredApps)
    }

    @Test
    fun everyWayAroundTheLockIsClosed() {
        val restrictions = plan().desiredRestrictions
        assertTrue(restrictions.containsAll(LockRestrictions.DUMB))
        listOf(
            LockRestrictions.SAFE_BOOT, LockRestrictions.DEBUGGING, LockRestrictions.FACTORY_RESET,
            LockRestrictions.ADD_USER, LockRestrictions.USER_SWITCH, LockRestrictions.INSTALL_APPS,
            LockRestrictions.INSTALL_UNKNOWN_SOURCES, LockRestrictions.UNINSTALL_APPS,
            LockRestrictions.ADD_CLONE_PROFILE, LockRestrictions.ADD_MANAGED_PROFILE, LockRestrictions.DATE_TIME
        ).forEach { assertTrue(it, it in restrictions) }
    }

    @Test
    fun withoutTheLockNoneOfTheExtraRulesApply() {
        val restrictions = plan(dumb = false).desiredRestrictions
        assertFalse(LockRestrictions.INSTALL_APPS in restrictions)
        assertFalse(LockRestrictions.ADD_CLONE_PROFILE in restrictions)
    }

    @Test
    fun theLockNeverPinsThePhoneSoTheHotspotStaysInTheShade() {
        assertFalse(plan().brick)
    }

    @Test
    fun undoIsOpenForTenMinutesOnly() {
        val started = at(20, 10, 0)
        assertTrue(DumbPhoneRules.undoAvailable(started, started))
        assertTrue(DumbPhoneRules.undoAvailable(started + DumbPhoneRules.UNDO_MS - 1, started))
        assertFalse(DumbPhoneRules.undoAvailable(started + DumbPhoneRules.UNDO_MS, started))
        assertFalse(DumbPhoneRules.undoAvailable(started - 1, started))
    }

    @Test
    fun undoIsNeverOpenForALockThatNeverStarted() {
        assertFalse(DumbPhoneRules.undoAvailable(at(20, 10, 0), 0L))
        assertEquals(0L, DumbPhoneRules.undoRemainingMs(at(20, 10, 0), 0L))
    }

    @Test
    fun undoRemainingCountsDown() {
        val started = at(20, 10, 0)
        assertEquals(DumbPhoneRules.UNDO_MS - 60_000L, DumbPhoneRules.undoRemainingMs(started + 60_000L, started))
    }

    @Test
    fun theWaitIsSplitIntoDaysAndHours() {
        val hour = 3_600_000L
        assertEquals(7L to 0L, DumbPhoneRules.splitWait(7 * 24 * hour))
        assertEquals(6L to 23L, DumbPhoneRules.splitWait(7 * 24 * hour - hour))
        // A part of an hour counts as a whole hour, so the screen never shows 0 hours while time is left.
        assertEquals(7L to 0L, DumbPhoneRules.splitWait(7 * 24 * hour - 1))
        assertEquals(0L to 1L, DumbPhoneRules.splitWait(1))
        assertEquals(0L to 0L, DumbPhoneRules.splitWait(0))
    }
}
