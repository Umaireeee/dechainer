package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.LockSafety
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalSafetyTest {
    // ---- #5: Device Owner cannot be removed while a lock runs ----

    @Test
    fun removalIsRefusedWhileAScheduleABrickOrARideLockRuns() {
        assertFalse(LockSafety.removalBlocked(false, false, 0L, forced = false))
        assertTrue(LockSafety.removalBlocked(true, false, 0L, forced = false))
        assertTrue(LockSafety.removalBlocked(false, true, 0L, forced = false))
        assertTrue(LockSafety.removalBlocked(false, false, 60_000L, forced = false))
    }

    @Test
    fun forcedRemovalIsTheOnlyExit() {
        assertFalse(LockSafety.removalBlocked(true, true, 60_000L, forced = true))
    }
}
