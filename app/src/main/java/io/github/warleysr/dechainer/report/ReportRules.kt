package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.day.Goal
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.DayWindow
import java.time.LocalDate
import java.time.ZoneId

/**
 * What may be deleted, and when (blueprint 6.4 and 6.6). Pure. Deleting data never touches `app_state`
 * or the `day` rows, so the daily results the progress graphs read stay.
 */
object ReportRules {
    /**
     * Goal text can be deleted once a generated report covers its day (the day ends at or before the
     * newest report's period end). Goals in the open week cannot. [latestReportEnd] counts a deleted
     * report too, so deleting a report never makes more goals deletable.
     */
    fun canDeleteGoals(date: LocalDate, latestReportEnd: Long?, zone: ZoneId): Boolean =
        latestReportEnd != null && DayWindow.endOf(date, zone) <= latestReportEnd

    /**
     * A slip or a focus session can be deleted from a day before today, once that day's measured goals
     * (a NO_SLIP or FOCUS_MINUTES goal still open) have been settled. Deleting a slip on the day itself
     * would otherwise turn tonight's NO_SLIP goal into a pass.
     */
    fun canDeleteMeasured(entryAt: Long, now: Long, zone: ZoneId, goalsThatDay: List<Goal>): Boolean {
        val date = DayWindow.dateOf(entryAt, zone)
        if (!date.isBefore(DayWindow.dateOf(now, zone))) return false
        return goalsThatDay.none { it.type != GoalType.MANUAL && it.state == GoalState.OPEN }
    }
}
