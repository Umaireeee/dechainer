package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.RideLock
import io.github.warleysr.dechainer.data.UrgeActions
import io.github.warleysr.dechainer.data.UrgeActions.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeActionsTest {

    @Test
    fun unknownKindsAreIgnored() {
        assertNull(UrgeActions.parseKind(null))
        assertNull(UrgeActions.parseKind("UNBLOCK_EVERYTHING"))
        assertEquals(Kind.IMPULSE_BLOCK, UrgeActions.parseKind("IMPULSE_BLOCK"))
        assertEquals(Kind.FOCUS_BLOCK, UrgeActions.parseKind("FOCUS_BLOCK"))
    }

    @Test
    fun durationsAreCapped() {
        assertEquals(15, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 0))
        assertEquals(360, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 100_000))
        assertEquals(60, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 60))
        assertEquals(25, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, -5))
        assertEquals(120, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, 100_000))
    }

    @Test
    fun aRequestNeverShortensARunningImpulseBlock() {
        val thirtyMinLeft = 30 * 60_000L
        assertFalse(UrgeActions.shouldStartImpulse(thirtyMinLeft, 15))
        assertFalse(UrgeActions.shouldStartImpulse(thirtyMinLeft, 30))
        assertTrue(UrgeActions.shouldStartImpulse(thirtyMinLeft, 60))
        assertTrue(UrgeActions.shouldStartImpulse(0L, 15))
    }

    @Test
    fun theRideLockIsShortAndCapped() {
        assertEquals(Kind.RIDE_LOCK, UrgeActions.parseKind("RIDE_LOCK"))
        assertEquals(10, UrgeActions.clampMinutes(Kind.RIDE_LOCK, 0))
        assertEquals(10, UrgeActions.clampMinutes(Kind.RIDE_LOCK, 10))
        assertEquals(30, UrgeActions.clampMinutes(Kind.RIDE_LOCK, 100_000))
    }

    @Test
    fun theRideLockRunsOutOnItsOwnAndCannotBeStretchedByTheClock() {
        val now = 1_000_000_000L
        assertEquals(0L, RideLock.remaining(0L, now))
        assertEquals(0L, RideLock.remaining(now - 1, now))
        assertEquals(5 * 60_000L, RideLock.remaining(now + 5 * 60_000L, now))
        // An end time far in the future (clock moved back) is cut to the longest a ride lock can be.
        assertEquals(30 * 60_000L, RideLock.remaining(now + 10 * 60 * 60_000L, now))
    }

    @Test
    fun theJournalAndEmergencyAppsStayOpenDuringARideLock() {
        assertTrue(RideLock.JOURNAL_PACKAGE in RideLock.ALWAYS_OPEN)
        assertTrue("com.android.emergency" in RideLock.ALWAYS_OPEN)
    }
}
