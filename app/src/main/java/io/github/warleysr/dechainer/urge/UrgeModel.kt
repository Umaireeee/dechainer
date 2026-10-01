package io.github.warleysr.dechainer.urge

/**
 * The urge journal's data, as plain types (blueprint section 7, table `urge_entry`). No Android
 * here: the repository stores these, the flow rules decide on them, and both are tested on the JVM.
 */
enum class UrgeKind { URGE, SLIP }

/** Where the owner started it from. */
enum class UrgeSource { HOME, TILE, SHORTCUT, FOCUS }

/**
 * Where an entry is in the flow. The screen shown is derived from this and the clock
 * ([UrgeFlowRules.screenFor]), so the flow survives a killed app.
 */
enum class UrgeStatus {
    /** The ongoing path started; the breathing runs, and the note has not been written yet. */
    LOCKED,

    /** The writing screen is up. A slip starts here. */
    WRITING,

    /** The note is saved; the questions are being answered. */
    QUESTIONS,

    /** The answers are saved; the deep dive is not. The note is still on the phone. */
    PENDING_DEEPDIVE,

    /** The deep dive is saved and the note has been deleted. */
    DONE,

    /** "Not now": a counted stub with no text. */
    SKIPPED;

    /** Only these end an entry: nothing moves out of them. */
    val isFinal: Boolean get() = this == DONE || this == SKIPPED
}

/**
 * One urge or slip. [rawText] is the owner's note and is null once the deep dive exists (or if the
 * note was skipped). [questionsJson] and [answersJson] are the stored question list and answers, so
 * a deep dive can be retried later without asking again.
 */
data class UrgeEntry(
    val id: Long,
    val createdAt: Long,
    val kind: UrgeKind,
    val source: UrgeSource,
    val lockStartedAt: Long?,
    val lockEndedAt: Long?,
    val status: UrgeStatus,
    val rawText: String?,
    val questionsJson: String?,
    val answersJson: String?,
    val deepDive: String?
)

enum class QuestionType { CHOICE, TEXT, SCALE }

/** One question of the interview, from the AI or from the fixed list. */
data class Question(
    val id: String,
    val type: QuestionType,
    val prompt: String,
    /** The choices of a [QuestionType.CHOICE] question; empty for the others. */
    val options: List<String> = emptyList()
)

/** What the owner answered to one question. */
data class Answer(val questionId: String, val prompt: String, val answer: String)
