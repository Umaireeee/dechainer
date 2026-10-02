package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.Rules

/** What happened when the owner asked for an urge lock (blueprint 5.2 and 5.3). */
sealed interface UrgeStart {
    /** A new urge lock runs until [endsAt]. */
    data class Started(val endsAt: Long) : UrgeStart

    /** One is already running. It is left exactly as it is: a second tap never extends it. */
    data class AlreadyRunning(val endsAt: Long) : UrgeStart

    /**
     * A focus block already holds the phone, so no second lock starts. The
     * breathing screen still runs for the full ten minutes, until [breathingUntil].
     */
    data class Covered(val by: LockMode, val breathingUntil: Long) : UrgeStart

    /** This app is not Device Owner, so nothing can be locked. The breathing still runs until [breathingUntil]. */
    data class Unavailable(val breathingUntil: Long) : UrgeStart
}

/**
 * When an urge lock starts and when it does not. Pure: the engine reads the stored state, asks
 * here, and stores what comes back.
 */
object UrgeLockRule {
    fun decide(
        now: Long,
        runningUrgeEndsAt: Long,
        focusBlockActive: Boolean,
        deviceOwner: Boolean,
        lockMs: Long = Rules.URGE_LOCK_MS
    ): UrgeStart = when {
        // First: whatever is stored and still running is never touched, by any number of taps.
        runningUrgeEndsAt > now -> UrgeStart.AlreadyRunning(runningUrgeEndsAt)
        !deviceOwner -> UrgeStart.Unavailable(now + lockMs)
        focusBlockActive -> UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + lockMs)
        else -> UrgeStart.Started(now + lockMs)
    }
}
