package io.github.warleysr.dechainer.security

/**
 * The lock at the front door: opening Déchaîner asks for the phone's own screen lock first.
 *
 * Pure rules only (no Android types), so they are unit tested. The screen that asks lives in
 * `EntryGate`. This lock only ever tightens: it never opens a recovery session, so changing
 * settings still needs the recovery code and the unlock delay.
 */
object EntryLock {
    /** Leaving for less than this (a call, the notification shade) does not lock the app again. */
    const val GRACE_MS = 60_000L

    /**
     * True when the app was left long enough ago to lock again. [leftAt] is 0 when it was never
     * left. A clock that went backwards (a reboot resets uptime) locks, never opens.
     */
    fun shouldRelock(leftAt: Long, now: Long): Boolean {
        if (leftAt <= 0L) return false
        val away = now - leftAt
        return away < 0L || away > GRACE_MS
    }

    /**
     * Whether the gate covers the app right now. It stays out of the way of everything that must
     * be reachable at once: first-run setup (no code yet), a brick (the home screen and the Urge
     * button stay open) and the urge flow itself. It also needs a screen lock on the phone: with
     * none there is nothing to ask for.
     */
    fun required(
        enabled: Boolean,
        recoverySet: Boolean,
        deviceSecure: Boolean,
        unlocked: Boolean,
        brick: Boolean,
        urgeActive: Boolean
    ): Boolean = enabled && recoverySet && deviceSecure && !unlocked && !brick && !urgeActive
}
