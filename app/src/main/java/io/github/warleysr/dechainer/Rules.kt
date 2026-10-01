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

    /** Section 5.2. How long an urge lock lasts. Fixed: nothing extends it and nothing ends it early. */
    const val URGE_LOCK_MS = 10 * 60_000L

    /** Section 5.4. Two crashes within this window, while a brick runs, abort the brick. */
    const val CRASH_WINDOW_MS = 5 * 60_000L

    /** Section 5.4. How many crashes inside [CRASH_WINDOW_MS] abort a running brick. */
    const val CRASH_LIMIT = 2

    /**
     * A block found over by a wake-up this long after its end time (or later) is closed without the
     * chime: the phone was off or the alarm was lost, and ringing now would only be noise.
     */
    const val LATE_BLOCK_END_MS = 30_000L
}
