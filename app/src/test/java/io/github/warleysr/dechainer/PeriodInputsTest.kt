package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.AiPrompts
import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.report.DayFact
import io.github.warleysr.dechainer.report.FocusFact
import io.github.warleysr.dechainer.report.GoalFact
import io.github.warleysr.dechainer.report.InnerReport
import io.github.warleysr.dechainer.report.PeriodFacts
import io.github.warleysr.dechainer.report.PeriodInputs
import io.github.warleysr.dechainer.report.PeriodKind
import io.github.warleysr.dechainer.report.ReportInputs
import io.github.warleysr.dechainer.report.ReportSummary
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import io.github.warleysr.dechainer.urge.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The monthly and yearly reports' inputs: derived data only, rows, links and the shorter reports' advice. */
class PeriodInputsTest {
    private val zone = ZoneId.of("UTC")
    private val dive = "## What happened\nA\n## The earliest link\nThe phone was on the pillow.\n## What helped and what didn't\nB\n## For next time\nC"

    private fun urge(day: Int, hour: Int, kind: UrgeKind = UrgeKind.URGE, raw: String? = null, deep: String? = dive) = ReportInputs.urgeFact(
        UrgeEntry(day.toLong(), ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli(), kind, UrgeSource.HOME,
            null, null, UrgeStatus.DONE, raw, null, UrgeJson.answersToJson(listOf(Answer("q", "Where?", "Bed"))), deep),
        zone
    )

    private fun facts(kind: PeriodKind = PeriodKind.MONTH, urges: List<io.github.warleysr.dechainer.report.UrgeFact> = listOf(urge(3, 23), urge(10, 23, UrgeKind.SLIP))) = PeriodFacts(
        kind = kind,
        key = if (kind == PeriodKind.MONTH) "2026-10" else "2026",
        firstDate = if (kind == PeriodKind.MONTH) LocalDate.of(2026, 10, 1) else LocalDate.of(2026, 1, 1),
        lastDate = if (kind == PeriodKind.MONTH) LocalDate.of(2026, 10, 31) else LocalDate.of(2026, 12, 31),
        urges = urges,
        sessions = listOf(FocusFact(LocalDate.of(2026, 10, 6), 50, "Chapter 4", Flavor.USUAL, listOf(CheckinAnswer.YES))),
        days = listOf(DayFact(LocalDate.of(2026, 10, 6), DayKind.NORMAL, listOf(GoalFact("Read", GoalType.MANUAL, null, GoalState.NOT_DONE)), Violation.UNDER_HALF, 50)),
        inner = listOf(InnerReport("week of Oct 5, 2026", ReportSummary(1, 1, 1, 50, 1, 0, 0, 1, 1, "Charge the phone in the hall."))),
        past = listOf(InnerReport("2026-09", ReportSummary(5, 2, 3, 120, 2, 1, 4, 9, 2, "Fewer late nights.")))
    )

    @Test
    fun aMonthIsCutIntoMondayWeeksAndAYearIntoMonths() {
        val weeks = PeriodInputs.rows(PeriodKind.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 4), weeks.first())
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 10, 31), weeks.last())
        assertEquals(5, weeks.size)
        val months = PeriodInputs.rows(PeriodKind.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(12, months.size)
        assertEquals(LocalDate.of(2026, 2, 28), months[1].second)
    }

    @Test
    fun theMonthTextHoldsTotalsRowsPatternsLinksAndTheWeeksAdvice() {
        val t = PeriodInputs.build(facts())
        assertTrue(t, t.startsWith("Period: October 2026 (2026-10-01 to 2026-10-31"))
        assertTrue(t.contains("urges: 1; slips: 1"))
        assertTrue(t.contains("WEEK BY WEEK"))
        assertTrue(t.contains("- 2026-10-01 to 2026-10-04: urges 1, slips 0"))
        assertTrue(t.contains("late evening 22-23: urges 1, slips 1"))
        assertTrue(t.contains("- 2026-10-03 23:00 urge: The phone was on the pillow."))
        assertTrue(t.contains("advice given then: Charge the phone in the hall."))
        assertTrue(t.contains("- 2026-09: urges 5, slips 2"))
        assertTrue(t.contains("days that fell short: 1 (under_half 1)"))
    }

    @Test
    fun aYearHasMonthRowsAndNoPerUrgeLines() {
        val t = PeriodInputs.build(facts(PeriodKind.YEAR))
        assertTrue(t.contains("MONTH BY MONTH"))
        assertTrue(t.contains("- 2026-10: urges 1, slips 1"))
        assertTrue(t.contains("- 2026-01: urges 0"))
        assertFalse(t.contains("EARLIEST LINKS"))
        assertTrue(t.contains("THE MONTHLY REPORTS OF THIS YEAR"))
    }

    @Test
    fun theRawNoteNeverReachesTheTextOrTheRequest() {
        val f = facts(urges = listOf(urge(3, 23, raw = "SECRET NOTE", deep = null)))
        val t = PeriodInputs.build(f)
        assertFalse(t.contains("SECRET"))
        assertFalse(AiPrompts.monthlyReport(t).user.contains("SECRET"))
        assertFalse(AiPrompts.yearlyReport(PeriodInputs.build(f.copy(kind = PeriodKind.YEAR, key = "2026"))).user.contains("SECRET"))
    }

    @Test
    fun theStoredSummaryKeepsTheNextPeriodsAdvice() {
        val m = PeriodInputs.summary(facts(), "## The month at a glance\nx\n## Next month\nIf I get home, then I walk first.")
        assertEquals("If I get home, then I walk first.", m.advice)
        assertEquals(1, m.slips)
        val y = PeriodInputs.summary(facts(PeriodKind.YEAR), "## Carry into next year\nSleep by 23:00.")
        assertEquals("Sleep by 23:00.", y.advice)
    }

    @Test
    fun aPeriodWithNothingInItHasNoDataPoint() {
        assertFalse(facts(urges = emptyList()).copy(sessions = emptyList(), days = emptyList()).hasDataPoint)
        assertTrue(facts().hasDataPoint)
    }
}
