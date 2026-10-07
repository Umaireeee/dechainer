package io.github.warleysr.dechainer.lock

/** What becomes of a scheduled focus start (blueprint 5.3, last rule; D18). */
sealed interface FocusStart {
    /** Start now. */
    data object Now : FocusStart

    /** An urge lock is running: start when it ends, the window being still open then. */
    data class At(val at: Long) : FocusStart

    data class Skip(val reason: SkipReason) : FocusStart
}

enum class SkipReason {
    /** Its window has already ended. */
    WINDOW_OVER,

    /** The urge lock outlasts the window, so there is nothing left to start. */
    URGE_LOCK_OUTLASTS_WINDOW,

    /** Less than a block's minimum is left of the window, so it is not worth starting (blueprint 6.3). */
    TOO_LITTLE_LEFT
}

/**
 * A FOCUS timetable entry reaches its start. Pure. The caller still applies the block's own
 * minimum length (10 minutes) to a start that was deferred: a window with less than that left
 * after the urge lock ends is not worth a block.
 */
object FocusStartRule {
    fun decide(
        now: Long, windowEndsAt: Long, urgeEndsAt: Long,
        minLeftMs: Long = 0L
    ): FocusStart = when {
        windowEndsAt <= now -> FocusStart.Skip(SkipReason.WINDOW_OVER)
        urgeEndsAt > now ->
            when {
                urgeEndsAt >= windowEndsAt -> FocusStart.Skip(SkipReason.URGE_LOCK_OUTLASTS_WINDOW)
                windowEndsAt - urgeEndsAt < minLeftMs -> FocusStart.Skip(SkipReason.TOO_LITTLE_LEFT)
                else -> FocusStart.At(urgeEndsAt)
            }
        windowEndsAt - now < minLeftMs -> FocusStart.Skip(SkipReason.TOO_LITTLE_LEFT)
        else -> FocusStart.Now
    }
}
