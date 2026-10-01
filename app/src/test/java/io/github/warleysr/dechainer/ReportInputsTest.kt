package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.AiPrompts
import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.report.*
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReportInputsTest {
    private val zone = ZoneId.of("Europe/London")
    private val secret = "SECRET-NOTE-the-ugly-details"

    private fun entry(status: UrgeStatus, raw: String?, deep: String?) = UrgeEntry(
        id = 1, createdAt = LocalDate.of(2026, 10, 7).atTime(22, 15).atZone(zone).toInstant().toEpochMilli(),
        kind = UrgeKind.SLIP, source = UrgeSource.HOME, lockStartedAt = null, lockEndedAt = null, status = status,
        rawText = raw, questionsJson = null,
        answersJson = UrgeJson.answersToJson(listOf(Answer("q1", "Where were you?", "In bed"))), deepDive = deep
    )

    private fun data(urges: List<UrgeFact> = emptyList(), past: List<PastSummary> = emptyList()) = ReportData(
        firstDate = LocalDate.of(2026, 10, 5), lastDate = LocalDate.of(2026, 10, 11),
        urges = urges,
        sessions = listOf(FocusFact(LocalDate.of(2026, 10, 6), 50, "Chapter 4", Flavor.USUAL, listOf(CheckinAnswer.YES, CheckinAnswer.NO))),
        days = listOf(DayFact(LocalDate.of(2026, 10, 6), DayKind.PUNISHMENT,
            listOf(GoalFact("Read chapter 4", GoalType.MANUAL, null, GoalState.DONE), GoalFact("Focus", GoalType.FOCUS_MINUTES, 90, GoalState.NOT_DONE)),
            Violation.UNDER_HALF, 50)),
        past = past
    )

    @Test fun theRawNoteIsNeverCarriedIntoAFact() {
        val fact = ReportInputs.urgeFact(entry(UrgeStatus.PENDING_DEEPDIVE, secret, null), zone)
        assertFalse(fact.toString().contains("SECRET"))
        assertEquals(1, fact.answers.size)
        assertEquals(22, fact.at.hour)
    }

    @Test fun theRawNoteIsNeverInTheTextOrTheRequest() {
        val urge = ReportInputs.urgeFact(entry(UrgeStatus.PENDING_DEEPDIVE, secret, null), zone)
        val text = ReportInputs.build(data(listOf(urge)))
        assertFalse(text, text.contains("SECRET"))
        val req = AiPrompts.weeklyReport(text)
        assertFalse(req.user.contains("SECRET"))
        assertFalse(req.system.contains("SECRET"))
        assertTrue(req.user.contains("In bed")) // the answers are derived data and do go
    }

    @Test fun aDoneEntryCarriesItsDeepDiveAndOnlyThat() {
        val fact = ReportInputs.urgeFact(entry(UrgeStatus.DONE, null, "## What happened\nLate scrolling."), zone)
        assertEquals("## What happened\nLate scrolling.", fact.deepDive)
        assertTrue(ReportInputs.build(data(listOf(fact))).contains("Late scrolling."))
    }

    @Test fun theTextHoldsGoalsMinutesAndEarlierAdvice() {
        val past = PastSummary(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4), ReportSummary(3, 1, 4, 200, 3, 1, 10, 14, 0, "Phone out of the bedroom."))
        val text = ReportInputs.build(data(past = listOf(past)))
        assertTrue(text.contains("[done] (manual) Read chapter 4"))
        assertTrue(text.contains("[not_done] (focus_minutes 90 min) Focus"))
        assertTrue(text.contains("punishment day, because under_half; 1 of 2 goals done; focus minutes that day: 50"))
        assertTrue(text.contains("advice given then: Phone out of the bedroom."))
        assertTrue(text.contains("check-in yes: 1; check-in no: 1"))
    }

    @Test fun summaryCountsAndRoundTrips() {
        val urges = listOf(
            UrgeFact(UrgeKind.URGE, LocalDateTime.of(2026, 10, 6, 9, 0), emptyList(), null),
            UrgeFact(UrgeKind.SLIP, LocalDateTime.of(2026, 10, 7, 9, 0), emptyList(), null)
        )
        val s = ReportInputs.summary(data(urges), "Do this")
        assertEquals(ReportSummary(1, 1, 1, 50, 1, 1, 1, 2, 1, "Do this"), s)
        assertEquals(s, ReportInputs.summaryFromJson(ReportInputs.summaryToJson(s)))
        assertNull(ReportInputs.summaryFromJson("not json"))
        assertNull(ReportInputs.summaryFromJson(""))
    }

    @Test fun adviceIsTheNextWeekSection() {
        val md = "## Focus\nGood.\n## Next week\n1. Phone out.\n2. Walk.\n3. Plan.\n## Other\nx"
        assertEquals("1. Phone out.\n2. Walk.\n3. Plan.", ReportInputs.adviceOf(md))
        assertEquals("", ReportInputs.adviceOf("## Focus\nGood."))
    }

    @Test fun aPeriodNeedsADataPoint() {
        assertFalse(ReportData(LocalDate.now(), LocalDate.now(), emptyList(), emptyList(), emptyList(), emptyList()).hasDataPoint)
        val unanswered = FocusFact(LocalDate.now(), 25, "x", Flavor.USUAL, emptyList())
        assertFalse(ReportData(LocalDate.now(), LocalDate.now(), emptyList(), listOf(unanswered), emptyList(), emptyList()).hasDataPoint)
        assertTrue(data().hasDataPoint)
    }

    @Test fun theRunDecisionsAreMapped() {
        assertNull(ReportRun.forGate(io.github.warleysr.dechainer.ai.AiGateResult.OPEN))
        assertEquals(ReportOutcome.RETRY, ReportRun.forGate(io.github.warleysr.dechainer.ai.AiGateResult.OFFLINE))
        assertEquals(ReportOutcome.NEEDS_OWNER, ReportRun.forGate(io.github.warleysr.dechainer.ai.AiGateResult.NO_KEY))
        assertEquals(ReportOutcome.NEEDS_OWNER, ReportRun.forFailure(io.github.warleysr.dechainer.ai.AiError.BAD_KEY))
        assertEquals(ReportOutcome.RETRY, ReportRun.forFailure(io.github.warleysr.dechainer.ai.AiError.NETWORK))
    }
}
