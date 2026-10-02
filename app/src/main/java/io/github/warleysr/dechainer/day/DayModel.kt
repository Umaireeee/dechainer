package io.github.warleysr.dechainer.day

import java.time.LocalDate

enum class DayKind {
    NORMAL, REST;

    companion object {
        /** A stored kind; anything unknown (an older build's PUNISHMENT, say) reads as NORMAL. */
        fun parse(name: String?): DayKind = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

enum class GoalType { MANUAL, FOCUS_MINUTES, NO_SLIP }

enum class GoalState { OPEN, DONE, NOT_DONE }

enum class ResolvedBy { USER, AUTO }

/** Why a day missed its plan (blueprint 6.4): the first reason found. Recorded only; nothing is locked by it. */
enum class Violation { PLAN_MISSING, UNRESOLVED, UNDER_HALF }

data class Goal(
    val id: Long,
    val position: Int,
    val text: String,
    val type: GoalType = GoalType.MANUAL,
    val targetMinutes: Int? = null,
    val state: GoalState = GoalState.OPEN,
    val resolvedBy: ResolvedBy? = null
)

/** A goal as the owner writes it, before it has an id. */
data class NewGoal(val text: String, val type: GoalType = GoalType.MANUAL, val targetMinutes: Int? = null)

data class DayRow(
    val date: LocalDate,
    val kind: DayKind,
    val planWrittenAt: Long?,
    val resolvedAt: Long?,
    val doneCount: Int,
    val totalCount: Int,
    val evaluated: Boolean,
    val violation: Violation?
)

/** What the other features measured for one day. Zero until focus sessions and slip entries exist (Phases 3 and 4). */
interface DayStats {
    fun focusMinutes(date: LocalDate): Int
    fun slips(date: LocalDate): Int
}

object NoDayStats : DayStats {
    override fun focusMinutes(date: LocalDate) = 0
    override fun slips(date: LocalDate) = 0
}
