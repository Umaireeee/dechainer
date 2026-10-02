package io.github.warleysr.dechainer.security

/** What came of one try at the opening pattern. */
enum class EntryAttempt { OK, WRONG, WAIT }

/**
 * The lock at the front door: opening Déchaîner asks for the app's own pattern first. It is not
 * the phone's screen lock and not the recovery code (that one belongs with a trusted person).
 *
 * Pure rules only (no Android types), so they are unit tested. The screen that asks lives in
 * `EntryGate`. This lock only ever tightens: it never opens a recovery session, so changing
 * settings still needs the recovery code and the unlock delay.
 */
object EntryLock {
    /** Leaving for less than this (a call, the notification shade) does not lock the app again. */
    const val GRACE_MS = 60_000L

    /** Wrong tries allowed before the waiting starts. */
    const val FREE_TRIES = 5
    private const val FIRST_WAIT_MS = 30_000L
    private const val MAX_WAIT_MS = 15 * 60_000L

    /**
     * True when the app was left long enough ago to lock again. [leftAt] is 0 when it was never
     * left. A clock that went backwards (a reboot resets uptime) locks, never opens.
     */
    fun shouldRelock(leftAt: Long, now: Long): Boolean {
        if (leftAt <= 0L) return false
        val away = now - leftAt
        return away < 0L || away > GRACE_MS
    }

    /** How long a run of [failures] wrong tries makes the next try wait: none for the first few, then doubling. */
    fun waitAfterFailures(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val doublings = (failures - FREE_TRIES).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }

    /** What is left of the wait. A clock that went backwards (a reboot) counts the whole wait again. */
    fun remainingWaitMs(failures: Int, lastFailAt: Long, now: Long): Long {
        val wait = waitAfterFailures(failures)
        if (wait == 0L) return 0L
        val since = now - lastFailAt
        if (since < 0L) return wait
        return (wait - since).coerceAtLeast(0L)
    }

    /**
     * Whether the gate covers the app right now. It stays out of the way of everything that must
     * be reachable at once: first-run setup (no recovery code yet), a brick (the home screen and the
     * Urge button stay open) and the urge flow itself.
     */
    fun required(
        enabled: Boolean,
        recoverySet: Boolean,
        unlocked: Boolean,
        brick: Boolean,
        urgeActive: Boolean
    ): Boolean = enabled && recoverySet && !unlocked && !brick && !urgeActive
}
