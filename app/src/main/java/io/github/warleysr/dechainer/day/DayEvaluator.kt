package io.github.warleysr.dechainer.day

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The numbers of section 6.4 and D9. One place, so a veto is a one-line change. */
object DayRules {
    const val MIN_GOALS = 3
    const val MAX_GOALS = 7
    const val MAX_REST_PER_WEEK = 1
    const val EVENING_START_HOUR = 20
    const val REMINDER_LATE_HOUR = 23
}

/** Day and window maths on the trusted clock. Pure: `now` and the zone are always passed in. */
object DayWindow {
    fun dateOf(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The first moment after [date] (00:00 of the next day); the exclusive end of the day. */
    fun endOf(date: LocalDate, zone: ZoneId): Long = startOf(date.plusDays(1), zone)

    fun eveningStart(date: LocalDate, zone: ZoneId, hour: Int = DayRules.EVENING_START_HOUR): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 20:00:00 to 23:59:59 local of [date]. */
    fun inEvening(now: Long, date: LocalDate, zone: ZoneId): Boolean =
        now >= eveningStart(date, zone) && now < endOf(date, zone)

    /** Tomorrow's plan can be written or changed only in today's evening window; it locks at 23:59:59. */
    fun canEditPlan(now: Long, planDate: LocalDate, zone: ZoneId): Boolean =
        inEvening(now, planDate.minusDays(1), zone)

    /**
     * DONE on a manual goal: only in the evening window of its own day, the same window as NOT_DONE.
     * Ticking a goal during the day let a goal be closed early to get around a lock (owner decision,
     * after the blueprint's "any time during the day").
     */
    fun canMarkDone(now: Long, goalDate: LocalDate, zone: ZoneId): Boolean = inEvening(now, goalDate, zone)

    /** NOT_DONE: only in the evening window of the goal's own day. */
    fun canMarkNotDone(now: Long, goalDate: LocalDate, zone: ZoneId): Boolean = inEvening(now, goalDate, zone)

    /** A rest day may be declared for tomorrow, in the evening window, if no other rest day is within 6 days of it. */
    fun canDeclareRest(now: Long, restDate: LocalDate, existingRestDates: Collection<LocalDate>, zone: ZoneId): Boolean =
        inEvening(now, restDate.minusDays(1), zone) &&
            existingRestDates.filter { it != restDate }
                .count { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, restDate)) < 7 } < DayRules.MAX_REST_PER_WEEK
}

data class DayVerdict(val kind: DayKind, val violation: Violation?)

object DayEvaluator {

    /**
     * Closes a day at 23:59:59: measured goals still open are settled by AUTO from [focusMinutes] and
     * [slips]. Manual goals are left as they are (an open one is a violation). Re-running changes nothing.
     */
    fun settle(goals: List<Goal>, focusMinutes: Int, slips: Int): List<Goal> = goals.map { g ->
        if (g.state != GoalState.OPEN) g
        else when (g.type) {
            GoalType.MANUAL -> g
            GoalType.FOCUS_MINUTES ->
                g.copy(state = if (focusMinutes >= (g.targetMinutes ?: Int.MAX_VALUE)) GoalState.DONE else GoalState.NOT_DONE, resolvedBy = ResolvedBy.AUTO)
            GoalType.NO_SLIP ->
                g.copy(state = if (slips == 0) GoalState.DONE else GoalState.NOT_DONE, resolvedBy = ResolvedBy.AUTO)
        }
    }

    /** During the day a focus goal ticks DONE as soon as the minutes are reached; it never ticks NOT_DONE early. */
    fun live(goals: List<Goal>, focusMinutes: Int): List<Goal> = goals.map { g ->
        if (g.state == GoalState.OPEN && g.type == GoalType.FOCUS_MINUTES && g.targetMinutes != null && focusMinutes >= g.targetMinutes)
            g.copy(state = GoalState.DONE, resolvedBy = ResolvedBy.AUTO)
        else g
    }

    /**
     * Day D, decided at the first wake-up after its 00:00.
     * @param prevKind the kind of D-1
     * @param prevGoals D-1's goals, already [settle]d
     * @param planGoalCount how many goals the plan for D has
     * @param declaredRest whether D was declared a REST day
     */
    fun evaluate(prevKind: DayKind, prevGoals: List<Goal>, planGoalCount: Int, declaredRest: Boolean): DayVerdict {
        val violation: Violation? = when (prevKind) {
            // Back-to-back guard: a punishment day never follows a punishment day.
            DayKind.PUNISHMENT -> null
            else -> when {
                planGoalCount < DayRules.MIN_GOALS -> Violation.PLAN_MISSING
                prevKind == DayKind.REST -> null // a rest day waives its own goals only; the plan above still counts
                prevGoals.any { it.type == GoalType.MANUAL && it.state == GoalState.OPEN } -> Violation.UNRESOLVED
                prevGoals.count { it.state == GoalState.DONE } * 2 < prevGoals.size -> Violation.UNDER_HALF
                else -> null
            }
        }
        val kind = when {
            violation != null -> DayKind.PUNISHMENT
            declaredRest -> DayKind.REST
            else -> DayKind.NORMAL
        }
        return DayVerdict(kind, violation)
    }
}
