package io.github.warleysr.dechainer.day

/**
 * One line of tomorrow's plan while it is being written. Pure, so the type cycle, the carry-over and
 * what is saved are tested without a screen.
 */
data class PlanDraft(
    val text: String = "",
    val type: GoalType = GoalType.MANUAL,
    val targetMinutes: Int? = null
) {
    /** The next type a tap on the label gives: manual, focus 30, 60, 90, 120 minutes, no slip, manual again. */
    fun nextType(): PlanDraft = when (type) {
        GoalType.MANUAL -> copy(type = GoalType.FOCUS_MINUTES, targetMinutes = FOCUS_STEPS.first())
        GoalType.FOCUS_MINUTES -> {
            val next = FOCUS_STEPS.firstOrNull { it > (targetMinutes ?: 0) }
            if (next != null) copy(targetMinutes = next) else copy(type = GoalType.NO_SLIP, targetMinutes = null)
        }
        GoalType.NO_SLIP -> copy(type = GoalType.MANUAL, targetMinutes = null)
    }

    /**
     * What is saved, or null for an empty manual line. A measured goal with no text of its own gets
     * a plain one ([focusText] with `%d` for the minutes, [noSlipText]), so it is never dropped.
     */
    fun toNewGoal(focusText: String, noSlipText: String): NewGoal? {
        val clean = text.trim()
        return when (type) {
            GoalType.MANUAL -> clean.takeIf { it.isNotEmpty() }?.let { NewGoal(it) }
            GoalType.FOCUS_MINUTES -> {
                val minutes = targetMinutes ?: FOCUS_STEPS.first()
                NewGoal(clean.ifEmpty { focusText.replace("%d", minutes.toString()) }, GoalType.FOCUS_MINUTES, minutes)
            }
            GoalType.NO_SLIP -> NewGoal(clean.ifEmpty { noSlipText }, GoalType.NO_SLIP)
        }
    }

    companion object {
        const val MAX_TEXT = 120
        val FOCUS_STEPS = listOf(30, 60, 90, 120)

        /** The drafts for a saved plan, or three empty lines when there is none. */
        fun fromGoals(goals: List<Goal>): List<PlanDraft> =
            goals.map { PlanDraft(it.text, it.type, it.targetMinutes) }.ifEmpty { List(DayRules.MIN_GOALS) { PlanDraft() } }

        /**
         * Carries today's unfinished goals into the drafts, only on the owner's tap (blueprint 6.4):
         * empty lines are filled first, a goal already written is not added twice, and the plan
         * never grows past [DayRules.MAX_GOALS].
         */
        fun carryOver(drafts: List<PlanDraft>, unfinished: List<Goal>): List<PlanDraft> {
            val out = drafts.toMutableList()
            val written = out.map { it.text.trim().lowercase() }.toMutableSet()
            for (g in unfinished) {
                val key = g.text.trim().lowercase()
                if (key.isEmpty() || key in written) continue
                val draft = PlanDraft(g.text.trim(), g.type, g.targetMinutes)
                val empty = out.indexOfFirst { it.text.isBlank() && it.type == GoalType.MANUAL }
                when {
                    empty >= 0 -> out[empty] = draft
                    out.size < DayRules.MAX_GOALS -> out += draft
                    else -> break
                }
                written += key
            }
            return out
        }
    }
}
