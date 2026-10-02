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

    /** DONE on a manual goal: any time during its own day. */
    fun canMarkDone(now: Long, goalDate: LocalDate, zone: ZoneId): Boolean = dateOf(now, zone) == goalDate

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
     * The result of day D, recorded at the first wake-up after it ended. Nothing is locked by it: it
     * is the honest record the Today screen, the reports and the progress graphs read.
     * @param kind D's kind (a REST day has no result of its own)
     * @param goals D's goals, already [settle]d
     * @param nextPlanCount how many goals the plan for D+1 has (written in D's evening)
     * @return D's kind and, when the day missed, the first reason it did
     */
    fun close(kind: DayKind, goals: List<Goal>, nextPlanCount: Int): DayVerdict {
        val missed: Violation? = when {
            kind == DayKind.REST -> if (nextPlanCount < DayRules.MIN_GOALS) Violation.PLAN_MISSING else null
            goals.any { it.type == GoalType.MANUAL && it.state == GoalState.OPEN } -> Violation.UNRESOLVED
            goals.count { it.state == GoalState.DONE } * 2 < goals.size -> Violation.UNDER_HALF
            nextPlanCount < DayRules.MIN_GOALS -> Violation.PLAN_MISSING
            else -> null
        }
        return DayVerdict(kind, missed)
    }

    /** The share of goals done, 0 to 100, or null for a day with no goals. */
    fun percentDone(goals: List<Goal>): Int? =
        if (goals.isEmpty()) null else goals.count { it.state == GoalState.DONE } * 100 / goals.size
}
