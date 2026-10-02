package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.EntryLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the lock at the front door: when it asks, and what it never stands in front of. */
class EntryLockTest {
    private fun required(
        enabled: Boolean = true, recoverySet: Boolean = true, deviceSecure: Boolean = true,
        unlocked: Boolean = false, brick: Boolean = false, urgeActive: Boolean = false
    ) = EntryLock.required(enabled, recoverySet, deviceSecure, unlocked, brick, urgeActive)

    @Test fun coldStartAsks() = assertTrue(required())

    @Test fun unlockedOpensTheApp() = assertFalse(required(unlocked = true))

    @Test fun switchedOffNeverAsks() = assertFalse(required(enabled = false))

    @Test fun firstRunSetupIsNotBehindIt() = assertFalse(required(recoverySet = false))

    @Test fun noScreenLockMeansNothingToAskFor() = assertFalse(required(deviceSecure = false))

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
}
