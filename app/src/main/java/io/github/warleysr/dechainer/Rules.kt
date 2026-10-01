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

    /** Section 6.2. Breathing: inhale and exhale lengths (a ten second cycle) and how long one prompt stays. */
    const val BREATH_INHALE_MS = 4_000L
    const val BREATH_EXHALE_MS = 6_000L
    const val BREATH_PROMPT_MS = 60_000L

    /** Section 6.2. After this long without an answer the AI's questions give way to the three fixed ones. */
    const val AI_QUESTIONS_TIMEOUT_MS = 20_000L

    /** Section 6.2. The AI is asked for 3 to 5 questions; a reply outside that range is not used. */
    const val MIN_AI_QUESTIONS = 3
    const val MAX_AI_QUESTIONS = 5

    /**
     * How long after an urge started (or its lock ended) the app still opens straight back into the
     * flow, so a killed app or a Home press does not lose the writing step. Older entries stay as
     * the counted stubs they are.
     */
    const val URGE_RESUME_WINDOW_MS = 60 * 60_000L

    /** The longest note, personal reason and answer the app stores or sends. */
    const val MAX_NOTE_CHARS = 4_000
    const val MAX_ANSWER_CHARS = 600
    const val MAX_REASON_CHARS = 300

    /** D10. A slip goes straight to writing. Flip to true and a slip also starts an urge lock. */
    const val LOCK_AFTER_SLIP = false
}
