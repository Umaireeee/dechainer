package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.Rules

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

    /** What the first session of a focus block is for (the journal's "first move"); optional, cleaned before use. */
    const val EXTRA_INTENTION = "intention"

    enum class Kind {
        /** The panic button, remotely: starts the urge lock. The length is fixed, so the minutes are ignored. */
        IMPULSE_BLOCK,

        /** A committed focus block: apps lock during sessions, and leaving early takes the recovery code. */
        FOCUS_BLOCK,

        /**
         * The journal's ride: the same urge lock as [IMPULSE_BLOCK] (every app with an icon is
         * suspended except calls, the alarm clock, Déchaîner and the journal itself). Both names are
         * kept so the journal keeps working unchanged.
         */
        RIDE_LOCK
    }

    const val FOCUS_MIN_MINUTES = 25
    const val FOCUS_MAX_MINUTES = 120

    fun parseKind(raw: String?): Kind? = Kind.entries.firstOrNull { it.name == raw }

    /**
     * Pulls a requested duration into the allowed range; a missing or bad value becomes the minimum.
     * An urge lock has one length, [Rules.URGE_LOCK_MS], whatever was asked.
     */
    fun clampMinutes(kind: Kind, minutes: Int): Int = when (kind) {
        Kind.FOCUS_BLOCK -> minutes.coerceIn(FOCUS_MIN_MINUTES, FOCUS_MAX_MINUTES)
        Kind.IMPULSE_BLOCK, Kind.RIDE_LOCK -> (Rules.URGE_LOCK_MS / 60_000L).toInt()
    }
}
