package io.github.warleysr.dechainer.security

/**
 * The timing rules for the unlock delay: after a correct recovery code, changes only unlock once
 * the chosen delay has passed. Pure, so it's unit-tested.
 *
 * All times are the phone's uptime clock (SystemClock.elapsedRealtime), not the wall clock, so
 * winding the time forward can't skip the wait. Uptime restarts at zero on reboot; [afterReboot]
 * then restarts the wait, so a reboot can only make it longer — never shorter.
 */
object UnlockDelay {
    const val SESSION_MS = 10 * 60 * 1000L
    const val MAX_MINUTES = 24 * 60
    val PRESETS = listOf(0, 5, 15, 30, 60, 120)

    data class Pending(val startedAt: Long, val unlockAt: Long)

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_MINUTES)

    fun isWaiting(p: Pending, now: Long): Boolean = now < p.unlockAt
    fun isOpen(p: Pending, now: Long): Boolean = now >= p.unlockAt && now < p.unlockAt + SESSION_MS
    fun isExpired(p: Pending, now: Long): Boolean = now >= p.unlockAt + SESSION_MS
    fun remainingWaitMs(p: Pending, now: Long): Long = (p.unlockAt - now).coerceAtLeast(0)
    fun remainingOpenMs(p: Pending, now: Long): Long = (p.unlockAt + SESSION_MS - now).coerceAtLeast(0)

    fun afterReboot(p: Pending, now: Long): Pending =
        if (now < p.startedAt) Pending(now, now + (p.unlockAt - p.startedAt)) else p

    /** Only shortening the delay, or turning it off, is loosening. Longer is always allowed. */
    fun changeNeedsUnlock(current: Int, new: Int): Boolean = new < current
}
