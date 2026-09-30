package io.github.warleysr.dechainer.security

/**
 * The arithmetic of the forced-removal wait, kept apart so it can be tested. The wait is counted in
 * time the phone has actually been running, from checkpoints, so moving the clock changes nothing.
 */
internal object ForcedRemovalClock {
    /** Boot count value when Android can't tell us. */
    const val UNKNOWN_BOOT = -1

    /**
     * Running time to add since the last checkpoint. Within one boot that is the change in the
     * elapsed-realtime clock. After a reboot (the boot count differs) the clock started again from
     * zero, so the whole of it is new; comparing the two clock readings alone cannot tell a reboot
     * from a long uptime, which is why the boot count is checked first.
     */
    fun elapsedSince(lastElapsed: Long, lastBootCount: Int, nowElapsed: Long, nowBootCount: Int): Long {
        val rebooted = lastBootCount != UNKNOWN_BOOT && nowBootCount != UNKNOWN_BOOT && lastBootCount != nowBootCount
        return when {
            rebooted -> nowElapsed
            nowElapsed >= lastElapsed -> nowElapsed - lastElapsed
            // No boot count to go on, but the clock went backwards: it must have restarted.
            else -> nowElapsed
        }.coerceAtLeast(0L)
    }
}
