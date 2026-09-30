package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.ForcedRemovalClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcedRemovalClockTest {
    // ---- #6: forced-removal time survives reboots ----

    @Test
    fun timeWithinOneBootIsTheDifferenceBetweenReadings() {
        assertEquals(5_000L, ForcedRemovalClock.elapsedSince(10_000L, 7, 15_000L, 7))
    }

    @Test
    fun afterARebootTheWholeNewUptimeCounts() {
        // Uptime is now 40 000 ms, more than the 10 000 at the last checkpoint: the readings alone
        // would say 30 000 ms, but the boot count shows a reboot, so all 40 000 ms are new.
        assertEquals(40_000L, ForcedRemovalClock.elapsedSince(10_000L, 7, 40_000L, 8))
    }

    @Test
    fun withoutABootCountABackwardsClockStillMeansARestart() {
        val unknown = ForcedRemovalClock.UNKNOWN_BOOT
        assertEquals(3_000L, ForcedRemovalClock.elapsedSince(100_000L, unknown, 3_000L, unknown))
        assertEquals(2_000L, ForcedRemovalClock.elapsedSince(1_000L, unknown, 3_000L, unknown))
    }

    @Test
    fun timeNeverGoesNegative() {
        assertTrue(ForcedRemovalClock.elapsedSince(5_000L, 1, 0L, 2) >= 0L)
    }
}
