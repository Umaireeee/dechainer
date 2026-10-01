package io.github.warleysr.dechainer.clock

import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.security.ForcedRemovalClock

/**
 * What the clock saw at its last wake-up: the trusted reading it returned, and the monotonic
 * readings taken at the same moment (elapsed-realtime and Android's boot count).
 */
data class ClockCheckpoint(val trustedMs: Long, val elapsedMs: Long, val bootCount: Int)

/** One reading: the time to use, and the checkpoint to store so the next reading can build on it. */
data class ClockReading(val trustedMs: Long, val checkpoint: ClockCheckpoint, val wallDistrusted: Boolean)

/**
 * The rule behind [TrustedClock] (blueprint 9.2), with no Android in it so it can be tested.
 *
 * The trusted time is the wall time, except that it never goes backwards. If the wall clock is more
 * than [Rules.CLOCK_BACKSTEP_TOLERANCE_MS] behind the last reading, it was moved back, and the time
 * is rebuilt from the last reading plus the time the phone has really been running since
 * (elapsed-realtime, which the settings screen cannot change). After a reboot elapsed-realtime
 * started again from zero, so only the time since boot is added: a lower bound, which can only make
 * a lock last longer than needed, never end it early.
 *
 * Moving the clock forward is not detected here. That is what the date and time lock is for.
 */
object TrustedClockMath {
    fun read(
        wallNow: Long,
        elapsedNow: Long,
        bootCountNow: Int,
        last: ClockCheckpoint?,
        toleranceMs: Long = Rules.CLOCK_BACKSTEP_TOLERANCE_MS
    ): ClockReading {
        val distrusted: Boolean
        val trusted: Long
        if (last == null) {
            distrusted = false
            trusted = wallNow
        } else if (wallNow < last.trustedMs - toleranceMs) {
            distrusted = true
            trusted = last.trustedMs + ForcedRemovalClock.elapsedSince(
                lastElapsed = last.elapsedMs,
                lastBootCount = last.bootCount,
                nowElapsed = elapsedNow,
                nowBootCount = bootCountNow
            )
        } else {
            distrusted = false
            // Inside the tolerance the wall may read a little earlier: hold the last reading.
            trusted = maxOf(wallNow, last.trustedMs)
        }
        return ClockReading(trusted, ClockCheckpoint(trusted, elapsedNow, bootCountNow), distrusted)
    }

    /**
     * Converts a trusted-clock time into the wall time to hand to an alarm (alarms fire on wall
     * time). They differ only while the wall clock is behind, by exactly that amount.
     */
    fun toWall(trustedMs: Long, trustedNow: Long, wallNow: Long): Long = trustedMs - (trustedNow - wallNow)
}
