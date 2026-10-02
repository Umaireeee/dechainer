package io.github.warleysr.dechainer.day

import java.time.LocalDate
import java.time.ZoneId

/**
 * The catch-up of the daily checklist (blueprint 6.4), over a repository and a clock passed in, so
 * it is testable without a phone. Safe to run on every wake-up: a day already closed is never touched.
 * It records results only; nothing here locks the phone.
 */
object DayService {

    /**
     * Closes every day from [activatedOn] up to yesterday that is not closed yet (its measured goals
     * settled, its result recorded), and ticks today's focus goals as their minutes are reached.
     */
    fun evaluate(repo: DayRepository, stats: DayStats, activatedOn: LocalDate, now: Long, zone: ZoneId) {
        val today = DayWindow.dateOf(now, zone)
        // Days close in order, so the catch-up starts after the last one closed: one pass costs a few
        // queries however long the app has been in use.
        var d = repo.lastEvaluated()?.plusDays(1)?.takeIf { it.isAfter(activatedOn) } ?: activatedOn
        while (d.isBefore(today)) {
            if (repo.day(d)?.evaluated != true) {
                val settled = DayEvaluator.settle(repo.goals(d), stats.focusMinutes(d), stats.slips(d))
                repo.saveResult(d, settled, now)
                val verdict = DayEvaluator.close(
                    kind = repo.day(d)?.kind ?: DayKind.NORMAL,
                    goals = settled,
                    nextPlanCount = repo.goals(d.plusDays(1)).size
                )
                repo.saveEvaluation(d, verdict)
            }
            d = d.plusDays(1)
        }
        val todays = repo.goals(today)
        DayEvaluator.live(todays, stats.focusMinutes(today)).zip(todays).forEach { (new, old) ->
            if (new.state != old.state) repo.setGoal(new.id, new.state, ResolvedBy.AUTO)
        }
    }

    /** Whether the evening checklist still has something to do: tomorrow has no plan yet, or a manual goal of today is open. */
    fun checklistOpen(repo: DayRepository, now: Long, zone: ZoneId): Boolean {
        val today = DayWindow.dateOf(now, zone)
        if (!DayWindow.inEvening(now, today, zone)) return false
        return repo.goals(today.plusDays(1)).size < DayRules.MIN_GOALS ||
            repo.goals(today).any { it.type == GoalType.MANUAL && it.state == GoalState.OPEN }
    }
}
