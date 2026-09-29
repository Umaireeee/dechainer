package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.UnlockDelay
import io.github.warleysr.dechainer.security.UnlockDelay.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the unlock delay: when changes unlock, and that nothing can shorten the wait. */
class UnlockDelayTest {
    private val min = 60_000L
    private val p = Pending(startedAt = 1_000 * min, unlockAt = 1_015 * min)   // a 15-minute wait

    @Test
    fun waitingUntilTheDelayEnds() {
        assertTrue(UnlockDelay.isWaiting(p, 1_014 * min))
        assertFalse(UnlockDelay.isOpen(p, 1_014 * min))
        assertEquals(min, UnlockDelay.remainingWaitMs(p, 1_014 * min))
    }

    @Test
    fun opensForTenMinutesAfterTheDelay() {
        assertTrue(UnlockDelay.isOpen(p, 1_015 * min))
        assertTrue(UnlockDelay.isOpen(p, 1_024 * min))
        assertFalse(UnlockDelay.isOpen(p, 1_025 * min))
        assertTrue(UnlockDelay.isExpired(p, 1_025 * min))
    }

    /** Uptime restarts at zero on reboot: the wait starts again, it never gets shorter. */
    @Test
    fun aRebootRestartsTheWait() {
        val after = UnlockDelay.afterReboot(p, 2 * min)
        assertEquals(2 * min, after.startedAt)
        assertEquals(17 * min, after.unlockAt)
        assertTrue(UnlockDelay.isWaiting(after, 16 * min))
    }

    @Test
    fun noRebootMeansNoChange() {
        assertEquals(p, UnlockDelay.afterReboot(p, 1_010 * min))
    }

    @Test
    fun onlyShorteningNeedsTheCode() {
        assertTrue(UnlockDelay.changeNeedsUnlock(current = 15, new = 5))
        assertTrue(UnlockDelay.changeNeedsUnlock(current = 15, new = 0))
        assertFalse(UnlockDelay.changeNeedsUnlock(current = 15, new = 60))
        assertFalse(UnlockDelay.changeNeedsUnlock(current = 0, new = 30))
        assertFalse(UnlockDelay.changeNeedsUnlock(current = 30, new = 30))
    }

    @Test
    fun delayIsKeptWithinADay() {
        assertEquals(0, UnlockDelay.clampMinutes(-5))
        assertEquals(1440, UnlockDelay.clampMinutes(9999))
        assertEquals(45, UnlockDelay.clampMinutes(45))
    }
}
