package io.github.warleysr.dechainer.urge

import io.github.warleysr.dechainer.Rules

/**
 * The breathing screen's maths (blueprint 6.2): a circle that grows for 4 seconds and shrinks for 6,
 * a ring that shows the time left, and one text prompt per minute, ten in all. Pure: the screen
 * hands in how long it has been breathing, never the clock.
 */
object Breathing {
    enum class Phase { INHALE, EXHALE }

    /** How many prompts there are, one per minute. */
    const val PROMPT_COUNT = 10

    /** The prompt that the owner's personal reason replaces (D5): the fifth, by position 4. */
    const val REASON_SLOT = 4

    private const val CYCLE_MS = Rules.BREATH_INHALE_MS + Rules.BREATH_EXHALE_MS

    fun phaseAt(elapsedMs: Long): Phase =
        if (elapsedMs.coerceAtLeast(0L) % CYCLE_MS < Rules.BREATH_INHALE_MS) Phase.INHALE else Phase.EXHALE

    /** The circle's size from 0 (empty) to 1 (full) at [elapsedMs]: up over the inhale, down over the exhale. */
    fun circleAt(elapsedMs: Long): Float {
        val t = elapsedMs.coerceAtLeast(0L) % CYCLE_MS
        return if (t < Rules.BREATH_INHALE_MS) t.toFloat() / Rules.BREATH_INHALE_MS
        else 1f - (t - Rules.BREATH_INHALE_MS).toFloat() / Rules.BREATH_EXHALE_MS
    }

    /** The prompt on screen after [elapsedMs]: one per minute, cycling through the list so a long sit stays gentle. */
    fun promptIndex(elapsedMs: Long): Int =
        ((elapsedMs.coerceAtLeast(0L) / Rules.BREATH_PROMPT_MS) % PROMPT_COUNT).toInt()

    /** True when the prompt at [index] is shown as the owner's own reason instead of the written one. */
    fun usesReason(index: Int, reason: String): Boolean = index == REASON_SLOT && reason.isNotBlank()

    /** The ring: the share of the breathing time still left, 1 at the start and 0 at the end. */
    fun ringLeft(elapsedMs: Long, totalMs: Long): Float =
        if (totalMs <= 0L) 0f else (1f - elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}
