package io.github.warleysr.dechainer.urge

/**
 * The urge record, as plain types (blueprint section 7, table `urge_entry`). No Android here: the
 * repository stores these, the flow rules decide on them, and both are tested on the JVM.
 *
 * A blackout is one thing only: a full-screen lock the owner started. The entry says when it
 * happened, where it was started from, and the lock window. There is no note, no question, no
 * answer and no deep dive.
 */

/** Where the owner started it from. */
enum class UrgeSource { HOME, TILE, SHORTCUT, FOCUS }

/** One urge lock. [lockStartedAt] and [lockEndedAt] are trusted-clock epoch millis, null only if the entry could not be stored. */
data class UrgeEntry(
    val id: Long,
    val createdAt: Long,
    val source: UrgeSource,
    val lockStartedAt: Long?,
    val lockEndedAt: Long?
)
