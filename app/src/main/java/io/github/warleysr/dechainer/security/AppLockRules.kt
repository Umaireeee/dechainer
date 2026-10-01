package io.github.warleysr.dechainer.security

/** What the app lock is made of: a PIN or a pattern. Its own secret, never the phone's. */
enum class AppLockKind { PIN, PATTERN }

/**
 * The rules of the app lock, with no Android in them. The lock is a PIN (4 to 12 digits) or a
 * pattern (4 or more dots of a 3 by 3 grid) that the owner chooses separately from the phone's own
 * screen lock, ideally one a trusted person knows. Wrong guesses are slowed down; the recovery code
 * is the only way to remove it.
 */
object AppLockRules {
    const val PIN_MIN = 4
    const val PIN_MAX = 12
    const val PATTERN_MIN = 4
    const val GRID = 3

    /** After the app has been out of sight this long, it asks again. */
    const val RELOCK_AFTER_MS = 30_000L

    /** Wrong tries that cost nothing; the next one starts the waiting. */
    const val FREE_FAILURES = 4

    fun validPin(pin: String): Boolean = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }

    /**
     * Too easy to guess: one digit repeated (0000), or a straight run up or down (1234, 8765). A phone
     * lock that a trusted person set for you is worth little if anyone can guess it.
     */
    fun weakPin(pin: String): Boolean {
        if (pin.isEmpty()) return true
        if (pin.all { it == pin[0] }) return true
        val d = pin.map { it - '0' }
        val steps = d.zipWithNext { a, b -> b - a }
        return steps.all { it == 1 } || steps.all { it == -1 }
    }

    fun validPattern(dots: List<Int>): Boolean =
        dots.size >= PATTERN_MIN && dots.all { it in 0 until GRID * GRID } && dots.distinct().size == dots.size

    /** A pattern as the text that is hashed: its dots in order. */
    fun patternSecret(dots: List<Int>): String = dots.joinToString("")

    /**
     * When the finger goes from dot [from] straight to dot [to] across a dot nobody touched yet, that
     * dot counts too, as on the phone's own pattern lock. Returns it, or null. Dots are numbered 0 to 8
     * row by row.
     */
    fun skippedDot(from: Int, to: Int): Int? {
        val fr = from / GRID; val fc = from % GRID
        val tr = to / GRID; val tc = to % GRID
        val dr = tr - fr; val dc = tc - fc
        val straight = dr == 0 || dc == 0 || kotlin.math.abs(dr) == kotlin.math.abs(dc)
        val twoApart = kotlin.math.abs(dr) == 2 || kotlin.math.abs(dc) == 2
        if (!straight || !twoApart || (dr % 2 != 0) || (dc % 2 != 0)) return null
        return (fr + dr / 2) * GRID + (fc + dc / 2)
    }

    /** Adds [dot] to a path being drawn, with the dot it crosses first when that one is not on the path yet. */
    fun extend(path: List<Int>, dot: Int): List<Int> {
        if (dot in path) return path
        val last = path.lastOrNull() ?: return listOf(dot)
        val crossed = skippedDot(last, dot)
        return if (crossed != null && crossed !in path) path + crossed + dot else path + dot
    }

    /**
     * How long the app refuses to try again after [failures] wrong tries in a row: nothing for the first
     * few, then 30 seconds, a minute, 5 minutes, 15 minutes and an hour.
     */
    fun delayAfter(failures: Int): Long = when {
        failures <= FREE_FAILURES -> 0L
        failures == FREE_FAILURES + 1 -> 30_000L
        failures == FREE_FAILURES + 2 -> 60_000L
        failures == FREE_FAILURES + 3 -> 5 * 60_000L
        failures == FREE_FAILURES + 4 -> 15 * 60_000L
        else -> 60 * 60_000L
    }

    /** Milliseconds still to wait. A clock that reads earlier than the last failure waits the full delay, never less. */
    fun waitRemaining(failures: Int, lastFailureAt: Long, now: Long): Long {
        val delay = delayAfter(failures)
        if (delay == 0L) return 0L
        if (now < lastFailureAt) return delay
        return (lastFailureAt + delay - now).coerceIn(0L, delay)
    }

    /** Tries left before the next wait starts (0 means the next wrong one waits). */
    fun freeTriesLeft(failures: Int): Int = (FREE_FAILURES - failures).coerceAtLeast(0)

    /** Whether the app asks again, given how long it was out of sight. */
    fun shouldRelock(awayMs: Long): Boolean = awayMs >= RELOCK_AFTER_MS
}
