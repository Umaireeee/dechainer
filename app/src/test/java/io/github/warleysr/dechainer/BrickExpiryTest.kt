package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.focus.Phase
import io.github.warleysr.dechainer.focus.PomodoroCore
import io.github.warleysr.dechainer.focus.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time, not an alarm, decides that a block is over (blueprint 5.4, R1). */
class BrickExpiryTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun block(endsAt: Long) =
        PomodoroCore.beginBlockIfIdle(PomodoroState(), endsAt, now - 40 * minute, 25)

    @Test
    fun aBlockIsActiveUntilItsEndAndNotAtIt() {
        val s = block(now + minute)
        assertTrue(s.blockActiveAt(now))
        assertFalse(s.blockExpiredAt(now))
        assertFalse("end <= now means over", s.blockActiveAt(now + minute))
        assertTrue(s.blockExpiredAt(now + minute))
        assertTrue(s.blockExpiredAt(now + 5 * minute))
    }

    @Test
    fun aStateWithNoBlockIsNeitherActiveNorExpired() {
        val idle = PomodoroState()
        assertFalse(idle.blockActiveAt(now))
        assertFalse(idle.blockExpiredAt(now))
    }

    @Test
    fun aBlockWhoseAlarmNeverFiredStillLooksRunningInItsStoredStateButIsOverByTheTime() {
        // The phase end and the block end both passed; nothing ever updated the stored state.
        val stale = block(now - 30 * minute)
        assertTrue("the stored state still says a block is running", stale.inBlock && stale.isRunning)
        assertFalse("but by the time it is not", stale.blockActiveAt(now))
        assertTrue(stale.blockExpiredAt(now))
    }

    @Test
    fun stoppingAnExpiredBlockLeavesAnIdleTimerAndKeepsTheCycleCount() {
        val stale = block(now - minute).copy(focusDoneInCycle = 2)
        val closed = PomodoroCore.stop(stale)
        assertFalse(closed.inBlock)
        assertTrue(closed.isIdle)
        assertEquals(Phase.FOCUS, closed.phase)
        assertEquals(2, closed.focusDoneInCycle)
        assertFalse(closed.blockExpiredAt(now))
    }
}
