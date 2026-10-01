package io.github.warleysr.dechainer.day

import java.time.LocalDate
import java.time.ZoneId

/**
 * The catch-up evaluation (blueprint 6.4), over a repository and a clock passed in, so it is
 * testable without a phone. Safe to run on every wake-up: a day already evaluated is never touched.
 */
object DayService {

    /**
     * Evaluates every day from the one after [activatedOn] up to today, closes the goals of the days
     * before, and ticks today's focus goals. Returns whether today is a punishment day.
     */
    fun evaluate(repo: DayRepository, stats: DayStats, activatedOn: LocalDate, now: Long, zone: ZoneId): Boolean {
        val today = DayWindow.dateOf(now, zone)
        var d = activatedOn.plusDays(1)
        while (!d.isAfter(today)) {
            if (repo.day(d)?.evaluated != true) {
                val prev = d.minusDays(1)
                val settled = DayEvaluator.settle(repo.goals(prev), stats.focusMinutes(prev), stats.slips(prev))
                repo.saveResult(prev, settled, now)
                val prevKind = repo.day(prev)?.kind ?: DayKind.NORMAL
                val verdict = DayEvaluator.evaluate(
                    prevKind = prevKind,
                    prevGoals = settled,
                    planGoalCount = repo.goals(d).size,
                    declaredRest = repo.day(d)?.kind == DayKind.REST
                )
                repo.saveEvaluation(d, verdict)
            }
            d = d.plusDays(1)
        }
        val todays = repo.goals(today)
        DayEvaluator.live(todays, stats.focusMinutes(today)).zip(todays).forEach { (new, old) ->
            if (new.state != old.state) repo.setGoal(new.id, new.state, ResolvedBy.AUTO)
        }
        return repo.day(today)?.kind == DayKind.PUNISHMENT
    }

    /** Whether the evening checklist still has something to do: tomorrow has no plan yet, or a manual goal of today is open. */
    fun checklistOpen(repo: DayRepository, now: Long, zone: ZoneId): Boolean {
        val today = DayWindow.dateOf(now, zone)
        if (!DayWindow.inEvening(now, today, zone)) return false
        return repo.goals(today.plusDays(1)).size < DayRules.MIN_GOALS ||
            repo.goals(today).any { it.type == GoalType.MANUAL && it.state == GoalState.OPEN }
    }
}
