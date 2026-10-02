package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.EntryLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the lock at the front door: when it asks, how long a password may be, and the wait after wrong tries. */
class EntryLockTest {
    private fun required(
        enabled: Boolean = true, recoverySet: Boolean = true,
        unlocked: Boolean = false, brick: Boolean = false, urgeActive: Boolean = false
    ) = EntryLock.required(enabled, recoverySet, unlocked, brick, urgeActive)

    @Test fun coldStartAsks() = assertTrue(required())

    @Test fun unlockedOpensTheApp() = assertFalse(required(unlocked = true))

    @Test fun switchedOffNeverAsks() = assertFalse(required(enabled = false))

    @Test fun firstRunSetupIsNotBehindIt() = assertFalse(required(recoverySet = false))

    @Test fun aBrickAndTheUrgeFlowAreNeverBehindIt() {
        assertFalse(required(brick = true))
        assertFalse(required(urgeActive = true))
    }

    @Test fun shortTripsDoNotRelock() {
        assertFalse(EntryLock.shouldRelock(leftAt = 0, now = 5_000_000))
        assertFalse(EntryLock.shouldRelock(leftAt = 1_000, now = 1_000 + EntryLock.GRACE_MS))
    }

    @Test fun longTripsRelock() {
        assertTrue(EntryLock.shouldRelock(leftAt = 1_000, now = 1_001 + EntryLock.GRACE_MS))
    }

    /** Uptime restarts at zero on reboot: a backwards clock locks, it never opens. */
    @Test fun aBackwardsClockRelocks() = assertTrue(EntryLock.shouldRelock(leftAt = 9_000_000, now = 100))

    @Test fun aPasswordIsFourToSixtyFourCharacters() {
        assertFalse(EntryLock.isValidPassword("abc"))
        assertTrue(EntryLock.isValidPassword("abcd"))
        assertTrue(EntryLock.isValidPassword("x".repeat(64)))
        assertFalse(EntryLock.isValidPassword("x".repeat(65)))
    }

    @Test fun theFirstFiveWrongTriesCostNothingThenTheWaitDoublesToACap() {
        assertEquals(0L, EntryLock.waitAfterFailures(4))
        assertEquals(30_000L, EntryLock.waitAfterFailures(5))
        assertEquals(60_000L, EntryLock.waitAfterFailures(6))
        assertEquals(15 * 60_000L, EntryLock.waitAfterFailures(50))
    }

    @Test fun theWaitCountsDownAndABackwardsClockCountsItAgain() {
        assertEquals(20_000L, EntryLock.remainingWaitMs(5, lastFailAt = 1_000, now = 11_000))
        assertEquals(0L, EntryLock.remainingWaitMs(5, lastFailAt = 1_000, now = 40_000))
        assertEquals(30_000L, EntryLock.remainingWaitMs(5, lastFailAt = 9_000_000, now = 100))
        assertEquals(0L, EntryLock.remainingWaitMs(2, lastFailAt = 1_000, now = 1_001))
    }
}
