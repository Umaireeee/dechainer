package io.github.warleysr.dechainer.urge

import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.lock.UrgeStart

/** The screens of the urge flow after the choice. */
enum class UrgeScreen { BREATHING, WRITING, QUESTIONS, DEEP_DIVE, FINISHED }

/** When the breathing runs: [startsAt] to [endsAt] (trusted-clock millis), and whether the urge lock itself was started for it. */
data class BreathingWindow(val startsAt: Long, val endsAt: Long, val lockStarted: Boolean) {
    val totalMs: Long get() = (endsAt - startsAt).coerceAtLeast(0L)
}

/**
 * What the urge flow decides, as pure functions over stored data and a `now` that is handed in
 * (blueprint 5.1 and 6.2). Nothing here reads a clock or touches Android.
 */
object UrgeFlowRules {

    /**
     * The moves an entry may make. The deep dive is saved before the note is deleted, and only an
     * entry whose answers are saved can get one; nothing moves out of DONE or SKIPPED.
     */
    fun canMove(from: UrgeStatus, to: UrgeStatus): Boolean = when (from) {
        UrgeStatus.LOCKED -> to == UrgeStatus.WRITING || to == UrgeStatus.QUESTIONS || to == UrgeStatus.SKIPPED
        UrgeStatus.WRITING -> to == UrgeStatus.QUESTIONS || to == UrgeStatus.SKIPPED
        UrgeStatus.QUESTIONS -> to == UrgeStatus.PENDING_DEEPDIVE
        UrgeStatus.PENDING_DEEPDIVE -> to == UrgeStatus.DONE
        UrgeStatus.DONE, UrgeStatus.SKIPPED -> false
    }

    /**
     * The breathing window for what [UrgeLockRule] decided (5.3). A fresh lock breathes for exactly
     * its ten minutes, from the moment it was stored ([lockStartedAt]). A lock already running (a second
     * tap, or one the journal started) breathes until it ends, never longer, and never extends it.
     * Inside a focus block, or without Device Owner, no lock starts and the
     * breathing still runs for the full ten minutes from [now].
     */
    fun breathingFor(start: UrgeStart, now: Long, lockStartedAt: Long? = null): BreathingWindow = when (start) {
        is UrgeStart.Started -> BreathingWindow((lockStartedAt ?: now).coerceAtMost(now), start.endsAt, lockStarted = true)
        is UrgeStart.AlreadyRunning ->
            BreathingWindow((lockStartedAt ?: now).coerceAtMost(now), start.endsAt, lockStarted = false)
        // The full ten minutes, ending where the rule said, whatever the clock read in between.
        is UrgeStart.Covered -> BreathingWindow(start.breathingUntil - Rules.URGE_LOCK_MS, start.breathingUntil, lockStarted = false)
        is UrgeStart.Unavailable -> BreathingWindow(start.breathingUntil - Rules.URGE_LOCK_MS, start.breathingUntil, lockStarted = false)
    }

    /**
     * Which screen an entry belongs on, from its stored state and the time. The breathing holds
     * until its window has run out, then the writing screen; everything after follows the status.
     * Derived from stored data, so a killed app comes back to the same place.
     */
    fun screenFor(entry: UrgeEntry, now: Long): UrgeScreen = when (entry.status) {
        UrgeStatus.LOCKED ->
            if (entry.kind == UrgeKind.URGE && entry.lockEndedAt != null && now < entry.lockEndedAt) UrgeScreen.BREATHING
            else UrgeScreen.WRITING
        UrgeStatus.WRITING -> UrgeScreen.WRITING
        UrgeStatus.QUESTIONS -> UrgeScreen.QUESTIONS
        UrgeStatus.PENDING_DEEPDIVE, UrgeStatus.DONE -> UrgeScreen.DEEP_DIVE
        UrgeStatus.SKIPPED -> UrgeScreen.FINISHED
    }

    /**
     * The entry the app opens straight back into: the newest one still in the flow (breathing,
     * writing or answering) that began, or whose lock ended, within [Rules.URGE_RESUME_WINDOW_MS].
     * Entries past the window stay as counted stubs. A pending deep dive is the retry job's, not the
     * screen's.
     */
    fun resumable(entries: List<UrgeEntry>, now: Long): UrgeEntry? = entries
        .filter { it.status == UrgeStatus.LOCKED || it.status == UrgeStatus.WRITING || it.status == UrgeStatus.QUESTIONS }
        .filter { (it.lockEndedAt ?: it.createdAt) + Rules.URGE_RESUME_WINDOW_MS > now }
        .maxByOrNull { it.createdAt }

    /**
     * An urge started from the locked screen runs the lock and the breathing and nothing more: the
     * writing, the questions and the deep dive are private and wait for the pattern. True when the
     * flow should step aside for the lock screen on [screen].
     */
    fun stepsAsideForPattern(holdPrivate: Boolean, screen: UrgeScreen): Boolean = holdPrivate && screen != UrgeScreen.BREATHING

    /** What the status of a brand-new entry is: the ongoing path starts locked, a slip goes straight to writing. */
    fun startingStatus(kind: UrgeKind): UrgeStatus = if (kind == UrgeKind.URGE) UrgeStatus.LOCKED else UrgeStatus.WRITING

    /** A note is long enough to go on with once it has at least this many characters, after trimming. */
    const val MIN_NOTE_CHARS = 3

    fun noteReady(text: String): Boolean = text.trim().length >= MIN_NOTE_CHARS

    /** The questions screen shows the fixed list when the AI's list did not arrive in time or could not be used. */
    fun useFixedQuestions(aiAnswered: Boolean, waitedMs: Long): Boolean =
        !aiAnswered || waitedMs > Rules.AI_QUESTIONS_TIMEOUT_MS
}

/** The three fixed questions (blueprint 6.2), used when the AI cannot give better ones. Their text comes from the app's strings. */
object FixedQuestions {
    val IDS = listOf("before", "feeling", "where")

    /** Builds the fixed list from the three prompts, in the order of [IDS]. */
    fun build(prompts: List<String>): List<Question> {
        require(prompts.size == IDS.size) { "Expected ${IDS.size} prompts" }
        return IDS.zip(prompts) { id, prompt -> Question(id, QuestionType.TEXT, prompt) }
    }
}
