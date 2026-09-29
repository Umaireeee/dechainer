package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.models.BlockSchedule

/**
 * Guards against "Lock while active" turning into a permanent lockout. A locked schedule can't be
 * edited while its window is open, so if the locked windows of all schedules together cover the
 * whole week (e.g. a 24h window every day, or two windows that chain into each other), there
 * would never be a moment to change them — only Forced removal would get the user out.
 */
object LockSafety {
    /** Locked windows must leave at least this much free time somewhere in the week. */
    const val MIN_FREE_MINUTES = 60

    /** Locked windows longer than this ask for explicit confirmation before saving. */
    const val LONG_LOCK_MINUTES = 6 * 60

    private const val WEEK_MINUTES = 7 * 1440

    /** Longest stretch of the week (wrapping Sunday -> Monday) not covered by any locked window. */
    fun longestUnlockedGapMinutes(schedules: List<BlockSchedule>): Int {
        val locked = BooleanArray(WEEK_MINUTES)
        schedules.filter { it.enabled && it.lockWhileActive }.forEach { schedule ->
            schedule.days.forEach { day ->
                val windowStart = (day.value - 1) * 1440 + schedule.startMinute
                for (m in 0 until schedule.windowMinutes) locked[(windowStart + m) % WEEK_MINUTES] = true
            }
        }
        val firstLocked = locked.indexOfFirst { it }
        if (firstLocked == -1) return WEEK_MINUTES

        var best = 0
        var run = 0
        // Start right after a locked minute so a gap that wraps around the week is counted whole.
        for (i in 1..WEEK_MINUTES) {
            if (!locked[(firstLocked + i) % WEEK_MINUTES]) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    fun leavesEnoughFreeTime(schedules: List<BlockSchedule>): Boolean =
        longestUnlockedGapMinutes(schedules) >= MIN_FREE_MINUTES
}
