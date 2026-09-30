package io.github.warleysr.dechainer.data

/**
 * The commands a companion app (the urge journal) may send to Déchaîner, and the rules that keep
 * them safe. They only ever *tighten*: nothing here can end a block, edit a schedule or touch the
 * recovery code, and every duration is capped, so a bug or a stray tap can't trap anyone.
 */
object UrgeActions {
    const val ACTION = "io.github.warleysr.dechainer.URGE_ACTION"

    /** Signature permission: only an app signed with the same key can send [ACTION]. */
    const val PERMISSION = "io.github.warleysr.dechainer.permission.URGE_ACTION"

    const val EXTRA_KIND = "kind"
    const val EXTRA_MINUTES = "minutes"

    enum class Kind {
        /** The panic button, remotely: locks Déchaîner and suspends the apps the panic button is set to. */
        IMPULSE_BLOCK,

        /** A committed focus block: apps lock during sessions, and leaving early takes the recovery code. */
        FOCUS_BLOCK,

        /**
         * The ride lock: every app with an icon is suspended except calls, emergency apps, the alarm
         * clock, Déchaîner and the journal itself, for a few minutes. It is what makes "ride it out
         * with nowhere to go" true, instead of only pausing the apps chosen for the panic button.
         */
        RIDE_LOCK
    }

    // Kept equal to SecurityManager's impulse bounds (15 min to 6 h).
    const val IMPULSE_MIN_MINUTES = 15
    const val IMPULSE_MAX_MINUTES = 360

    const val FOCUS_MIN_MINUTES = 25
    const val FOCUS_MAX_MINUTES = 120

    // Short on purpose: nothing can end a ride lock early, so it is capped at half an hour.
    const val RIDE_MIN_MINUTES = 10
    const val RIDE_MAX_MINUTES = 30

    fun parseKind(raw: String?): Kind? = Kind.entries.firstOrNull { it.name == raw }

    /** Pulls a requested duration into the allowed range; a missing or bad value becomes the minimum. */
    fun clampMinutes(kind: Kind, minutes: Int): Int = when (kind) {
        Kind.IMPULSE_BLOCK -> minutes.coerceIn(IMPULSE_MIN_MINUTES, IMPULSE_MAX_MINUTES)
        Kind.FOCUS_BLOCK -> minutes.coerceIn(FOCUS_MIN_MINUTES, FOCUS_MAX_MINUTES)
        Kind.RIDE_LOCK -> minutes.coerceIn(RIDE_MIN_MINUTES, RIDE_MAX_MINUTES)
    }

    /**
     * Whether a new impulse block should replace the running one. Only if it would last longer:
     * a request must never shorten a block that is already running.
     */
    fun shouldStartImpulse(remainingMillis: Long, requestedMinutes: Int): Boolean =
        remainingMillis < requestedMinutes * 60_000L
}
