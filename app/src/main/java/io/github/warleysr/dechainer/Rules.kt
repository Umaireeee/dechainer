package io.github.warleysr.dechainer

/**
 * The numbers the blueprint fixes, in one place so a veto is a one-line change. Pure constants: no
 * Android, and no override path. A debug build that wants shorter durations does it from the
 * debug source set, which a release build does not contain.
 */
object Rules {
    /**
     * Section 9.2. Wall time may sit this far behind the last reading before the trusted clock stops
     * believing it; a small step back (a network time correction) is not an attack.
     */
    const val CLOCK_BACKSTEP_TOLERANCE_MS = 2 * 60_000L

    /** How often the trusted clock writes its checkpoint when called from the UI; wake-ups write every time. */
    const val CLOCK_CHECKPOINT_INTERVAL_MS = 10_000L

    /**
     * The blackout's length is the owner's setting ([io.github.warleysr.dechainer.urge.UrgeSettings]).
     * These bound it and give the default. Nothing extends a running lock and nothing ends it early.
     * The owner asked for up to twelve hours.
     */
    const val URGE_LOCK_DEFAULT_MINUTES = 10
    const val URGE_LOCK_MIN_MINUTES = 1
    const val URGE_LOCK_MAX_MINUTES = 12 * 60

    /**
     * The blackout screen's window brightness (0.0 to 1.0). Low enough that the OLED panel is nearly
     * dark and there is no glare, high enough that the countdown is still readable. One line to change.
     */
    const val BLACKOUT_BRIGHTNESS = 0.05f

    /** Section 5.4. Two crashes within this window, while a brick runs, abort the brick. */
    const val CRASH_WINDOW_MS = 5 * 60_000L

    /** Section 5.4. How many crashes inside [CRASH_WINDOW_MS] abort a running brick. */
    const val CRASH_LIMIT = 2

    /**
     * A block found over by a wake-up this long after its end time (or later) is closed without the
     * chime: the phone was off or the alarm was lost, and ringing now would only be noise.
     */
    const val LATE_BLOCK_END_MS = 30_000L

    /** Section 6.3. A focus block is at least this long (also the shortest session a block plans). */
    const val FOCUS_BLOCK_MIN_MS = 10 * 60_000L

    /** Section 5.2. A focus block is at most this long. */
    const val FOCUS_BLOCK_MAX_MS = 8 * 60 * 60_000L

    /** D13. How long the pre-session prompt waits before the session runs as special. */
    const val FOCUS_PROMPT_TIMEOUT_MS = 2 * 60_000L

    /** D14. How long a check-in waits before it is recorded as unanswered. */
    const val FOCUS_CHECKIN_TIMEOUT_MS = 5 * 60_000L

    /** Section 6.3. The two halves of the reset: meditation, then going outside. */
    const val FOCUS_RESET_MEDITATION_MS = 5 * 60_000L
    const val FOCUS_RESET_OUTSIDE_MS = 5 * 60_000L

    /** Section 6.3. How long the "your call" message is shown before the plain timer takes over. */
    const val FOCUS_NOT_READY_MESSAGE_MS = 15_000L

    /** Section 6.3. The shortest purpose that counts. */
    const val FOCUS_PURPOSE_MIN_CHARS = 3
}
