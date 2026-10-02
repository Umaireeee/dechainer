package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.UrgeLockRule
import io.github.warleysr.dechainer.lock.UrgeStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blueprint 5.2 (URGE_LOCK: ten minutes, no early end) and 5.3 (no second lock, no extending). */
class UrgeLockRuleTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun decide(
        at: Long = now, running: Long = 0L, focus: Boolean = false, deviceOwner: Boolean = true
    ) = UrgeLockRule.decide(at, running, focus, deviceOwner)

    @Test
    fun anUrgeLockIsTenMinutesFromTheMomentItIsChosen() {
        assertEquals(10 * minute, Rules.URGE_LOCK_MS)
        assertEquals(UrgeStart.Started(now + 10 * minute), decide())
    }

    @Test
    fun aSecondTapDuringARunningUrgeLockNeverExtendsIt() {
        val end = now + 10 * minute
        // Taps at every minute of the lock, and in its last millisecond: always the same end.
        for (tap in 0..9) assertEquals(UrgeStart.AlreadyRunning(end), decide(at = now + tap * minute, running = end))
        assertEquals(UrgeStart.AlreadyRunning(end), decide(at = end - 1, running = end))
    }

    @Test
    fun aTapAfterTheLockHasEndedStartsANewOne() {
        val end = now + 10 * minute
        assertEquals(UrgeStart.Started(end + 10 * minute), decide(at = end, running = end))
        assertEquals(UrgeStart.Started(end + minute + 10 * minute), decide(at = end + minute, running = end))
    }

    @Test
    fun anUrgeInsideAFocusBlockStartsNoSecondLockButTheBreathingStillRunsTenMinutes() {
        assertEquals(UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + 10 * minute), decide(focus = true))
    }

    @Test
    fun aRunningUrgeLockStaysTheAnswerEvenInsideAFocusBlock() {
        // The urge lock came first and is already running: it is left alone, not replaced by "covered".
        val end = now + 5 * minute
        assertEquals(UrgeStart.AlreadyRunning(end), decide(running = end, focus = true))
    }

    @Test
    fun withoutDeviceOwnerNothingIsLockedButTheBreathingStillRuns() {
        assertEquals(UrgeStart.Unavailable(now + 10 * minute), decide(deviceOwner = false))
        assertTrue(decide(deviceOwner = false, focus = true) is UrgeStart.Unavailable)
    }
}
