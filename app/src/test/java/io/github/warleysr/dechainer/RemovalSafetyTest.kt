package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.LockSafety
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalSafetyTest {
    // ---- #5: Device Owner cannot be removed while a lock runs ----

    @Test
    fun removalIsRefusedWhileAScheduleOrABrickRuns() {
        assertFalse(LockSafety.removalBlocked(false, false, forced = false))
        assertTrue(LockSafety.removalBlocked(true, false, forced = false))
        // A brick is a focus block or an urge lock.
        assertTrue(LockSafety.removalBlocked(false, true, forced = false))
    }

    @Test
    fun forcedRemovalIsTheOnlyExit() {
        assertFalse(LockSafety.removalBlocked(true, true, forced = true))
    }
}
