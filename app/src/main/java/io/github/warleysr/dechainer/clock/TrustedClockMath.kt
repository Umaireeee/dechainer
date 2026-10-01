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
 * Moving the clock forward is caught only when it is certain to be a hand: automatic time is off
 * ([autoTimeOn] false) and, within one boot, the wall clock gained more than the phone really ran. Then the
 * jump is ignored and the time goes on from the last reading plus the running time. With automatic time
 * on, a jump forward is a network correction and is followed, so a clock that was honestly wrong and got
 * fixed is never left permanently behind. (After a reboot the two clocks cannot be compared; the Device
 * Owner's date and time lock covers that.)
 */
object TrustedClockMath {
    fun read(
        wallNow: Long,
        elapsedNow: Long,
        bootCountNow: Int,
        last: ClockCheckpoint?,
        toleranceMs: Long = Rules.CLOCK_BACKSTEP_TOLERANCE_MS,
        autoTimeOn: Boolean = true
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
        } else if (!autoTimeOn && jumpedForward(wallNow, elapsedNow, bootCountNow, last, toleranceMs)) {
            distrusted = true
            trusted = last.trustedMs + (elapsedNow - last.elapsedMs)
        } else {
            distrusted = false
            // Inside the tolerance the wall may read a little earlier: hold the last reading.
            trusted = maxOf(wallNow, last.trustedMs)
        }
        return ClockReading(trusted, ClockCheckpoint(trusted, elapsedNow, bootCountNow), distrusted)
    }

    /** The wall gained more than the phone ran since the last reading, in the same boot. */
    private fun jumpedForward(wallNow: Long, elapsedNow: Long, bootCountNow: Int, last: ClockCheckpoint, toleranceMs: Long): Boolean {
        val sameBoot = bootCountNow != ForcedRemovalClock.UNKNOWN_BOOT && bootCountNow == last.bootCount && elapsedNow >= last.elapsedMs
        if (!sameBoot) return false
        return wallNow - last.trustedMs > (elapsedNow - last.elapsedMs) + toleranceMs
    }

    /**
     * Converts a trusted-clock time into the wall time to hand to an alarm (alarms fire on wall
     * time). They differ only while the wall clock is behind, by exactly that amount.
     */
    fun toWall(trustedMs: Long, trustedNow: Long, wallNow: Long): Long = trustedMs - (trustedNow - wallNow)
}
