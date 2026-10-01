package io.github.warleysr.dechainer

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RideStatusRowTest {
    @Test
    fun theRowSaysWhenEachLockEndsAndZeroWhenNoneRuns() {
        val now = 1_000_000L
        // The old column name carries the urge lock's end, so the journal keeps working.
        assertArrayEquals(
            arrayOf<Any>(1, now + 600_000L, now + 1_800_000L, now + 600_000L),
            RideStatusProvider.statusRow(true, now + 600_000L, now + 1_800_000L, now)
        )
        assertArrayEquals(
            arrayOf<Any>(0, 0L, 0L, 0L),
            RideStatusProvider.statusRow(false, 0L, 0L, now)
        )
    }

    @Test
    fun aLockThatHasEndedReadsAsNotRunning() {
        val now = 1_000_000L
        assertArrayEquals(
            arrayOf<Any>(1, 0L, 0L, 0L),
            RideStatusProvider.statusRow(true, now - 1, now, now)
        )
    }
}
