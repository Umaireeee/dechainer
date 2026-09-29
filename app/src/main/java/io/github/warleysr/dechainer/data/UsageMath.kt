package io.github.warleysr.dechainer.data

/** One entry from Android's usage log, reduced to what the sums need. */
data class UsageEvt(val type: Int, val pkg: String, val cls: String, val time: Long)

/**
 * Turns Android's own usage log into "how long was this app in front today". Pure, so it can be
 * tested without a phone. Android records this log whether or not Déchaîner is running, which is
 * why time limits need no service of their own.
 */
object UsageMath {
    const val RESUMED = 1        // an activity came to the front
    const val PAUSED = 2         // ...and left it
    const val STOPPED = 23
    const val SCREEN_OFF = 16    // the screen went off: nothing is in front any more
    const val SHUTDOWN = 26
    const val STARTUP = 27

    /** Foreground milliseconds of [pkg] across [events], counting an app still in front until [now]. */
    fun foregroundMillis(events: List<UsageEvt>, pkg: String, now: Long): Long =
        sum(events.sortedBy { it.time }, pkg, now)

    fun foregroundMillisAll(events: List<UsageEvt>, pkgs: Set<String>, now: Long): Map<String, Long> {
        val sorted = events.sortedBy { it.time }
        return pkgs.associateWith { sum(sorted, it, now) }
    }

    private fun sum(sorted: List<UsageEvt>, pkg: String, now: Long): Long {
        var total = 0L
        val open = HashMap<String, Long>()   // activity class -> when it came to the front
        fun closeAll(at: Long) {
            open.values.forEach { total += (at - it).coerceAtLeast(0L) }
            open.clear()
        }
        for (e in sorted) {
            when (e.type) {
                SCREEN_OFF, SHUTDOWN, STARTUP -> closeAll(e.time)
                RESUMED -> if (e.pkg == pkg) open.putIfAbsent(e.cls, e.time)
                PAUSED, STOPPED -> if (e.pkg == pkg) {
                    open.remove(e.cls)?.let { total += (e.time - it).coerceAtLeast(0L) }
                }
            }
        }
        open.values.forEach { total += (now - it).coerceAtLeast(0L) }
        return total
    }
}

/** When apps hit their daily limits, and when to look again. Pure. */
object LimitMath {
    /** Never check more often than this, however close a limit is. */
    const val MIN_DELAY_MS = 15_000L

    fun reached(limits: Map<String, Int>, used: Map<String, Long>): Set<String> =
        limits.filter { (pkg, minutes) -> (used[pkg] ?: 0L) >= minutes * 60_000L }.keys

    /**
     * How long until the next look: the soonest any app *could* run out (if it were used
     * non-stop from now), or midnight when everything resets. Usage can never outrun the clock, so
     * looking then can't miss a limit.
     */
    fun nextCheckDelay(limits: Map<String, Int>, used: Map<String, Long>, untilMidnightMs: Long): Long {
        val remaining = limits
            .filter { (pkg, minutes) -> (used[pkg] ?: 0L) < minutes * 60_000L }
            .map { (pkg, minutes) -> minutes * 60_000L - (used[pkg] ?: 0L) }
        return minOf(remaining.minOrNull() ?: Long.MAX_VALUE, untilMidnightMs).coerceAtLeast(MIN_DELAY_MS)
    }
}
