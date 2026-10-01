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

    /** Never started on a punishment day. */
    PUNISHMENT_DAY,

    /** A declared rest day skips scheduled focus (D18). Block schedules and limits still run. */
    REST_DAY,

    /** The urge lock outlasts the window, so there is nothing left to start. */
    URGE_LOCK_OUTLASTS_WINDOW
}

/**
 * A FOCUS timetable entry reaches its start. Pure. The caller still applies the block's own
 * minimum length (10 minutes) to a start that was deferred: a window with less than that left
 * after the urge lock ends is not worth a block.
 */
object FocusStartRule {
    fun decide(now: Long, windowEndsAt: Long, punishmentActive: Boolean, restDay: Boolean, urgeEndsAt: Long): FocusStart = when {
        windowEndsAt <= now -> FocusStart.Skip(SkipReason.WINDOW_OVER)
        punishmentActive -> FocusStart.Skip(SkipReason.PUNISHMENT_DAY)
        restDay -> FocusStart.Skip(SkipReason.REST_DAY)
        urgeEndsAt > now ->
            if (urgeEndsAt < windowEndsAt) FocusStart.At(urgeEndsAt) else FocusStart.Skip(SkipReason.URGE_LOCK_OUTLASTS_WINDOW)
        else -> FocusStart.Now
    }
}
