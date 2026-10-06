package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.UrgeKind
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * What a weekly report is made from (blueprint 6.5). These types are the privacy boundary: none of
 * them has a field for the raw urge text, so it cannot reach the request however the builder is used.
 */

/** One urge or slip: its kind, time, the answers the owner gave and the deep dive. Never the note. */
data class UrgeFact(
    val kind: UrgeKind,
    val at: LocalDateTime,
    val answers: List<Answer>,
    val deepDive: String?
)

data class GoalFact(val text: String, val type: GoalType, val targetMinutes: Int?, val state: GoalState)

data class FocusFact(
    val date: LocalDate,
    val minutes: Int,
    val purpose: String,
    val flavor: Flavor,
    val answers: List<CheckinAnswer>
)

data class DayFact(
    val date: LocalDate,
    val kind: DayKind,
    val goals: List<GoalFact>,
    val violation: Violation?,
    /** Focus minutes measured that day, so ticks and minutes can be set side by side. */
    val focusMinutes: Int,
    val recordedDone: Int? = null,
    val recordedTotal: Int? = null
) {
    val goalsDone: Int get() = recordedDone ?: goals.count { it.state == GoalState.DONE }
    val goalsTotal: Int get() = recordedTotal ?: goals.size
}

/** What an earlier report said, in its own short form ([ReportSummary]). */
data class PastSummary(val firstDate: LocalDate, val lastDate: LocalDate, val summary: ReportSummary)

data class ReportData(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val urges: List<UrgeFact>,
    val sessions: List<FocusFact>,
    val days: List<DayFact>,
    /** The summaries of the last four reports, oldest first. */
    val past: List<PastSummary>
) {
    /**
     * Whether the period has a data point (blueprint 6.5): an urge or slip, a focus check-in or a goal.
     * A period with none gets no report.
     */
    val hasDataPoint: Boolean get() = urges.isNotEmpty() || sessions.any { it.answers.isNotEmpty() } || days.any { it.goalsTotal > 0 }
}

/** The numbers of one report, stored in `summary_json` and shown to the next four reports. */
data class ReportSummary(
    val urges: Int,
    val slips: Int,
    val focusSessions: Int,
    val focusMinutes: Int,
    val checkinYes: Int,
    val checkinNo: Int,
    val goalsDone: Int,
    val goalsTotal: Int,
    /** Finished days that fell short of their plan (a reason is recorded). */
    val daysMissed: Int,
    /** The "Next week" advice the report gave, so the next one can follow up honestly. Empty when none. */
    val advice: String = ""
)
