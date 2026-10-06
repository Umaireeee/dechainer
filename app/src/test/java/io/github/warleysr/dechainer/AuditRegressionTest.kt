package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.*
import io.github.warleysr.dechainer.focus.*
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import io.github.warleysr.dechainer.report.ReportInputs

/** Regression invariants found in the October 2026 audit. */
class AuditRegressionTest {
    private val minute = 60_000L
    private val start = 1_800_000_000_000L
    private val end = start + 120 * minute

    @Test fun streamWithoutCompletionMustNotBeAccepted() {
        val truncated = "data: {\"choices\":[{\"delta\":{\"content\":\"## What happened\\nHalf an answer\"}}]}\n"
        var rejected = false
        try { Sse.read(BufferedReader(StringReader(truncated))) {} }
        catch (_: Sse.StreamFailure) { rejected = true }
        assertTrue("EOF without a completion signal must fail, preserving the original note", rejected)
    }

    @Test fun streamWithTokenLimitMustNotBeAccepted() {
        val truncated = "data: {\"choices\":[{\"delta\":{\"content\":\"Half an answer\"}}]}\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n" +
            "data: [DONE]\n"
        var rejected = false
        try { Sse.read(BufferedReader(StringReader(truncated))) {} }
        catch (_: Sse.StreamFailure) { rejected = true }
        assertTrue("A token-limited answer is incomplete even when [DONE] arrives", rejected)
    }

    @Test fun nonStreamingTokenLimitMustNotBeAccepted() {
        val result = AiClient.interpret(200,
            """{"choices":[{"message":{"content":"Half an answer"},"finish_reason":"length"}]}""")
        assertTrue("finish_reason=length must fail instead of saving an incomplete report", result is AiResult.Failed)
    }

    @Test fun latePromptChoiceMustApplyTimeoutBeforeAction() {
        val s = FocusFlow.begin(FocusSource.SCHEDULED, null, null, start, end)!!.state
        val at = start + 3 * minute
        val actual = FocusFlow.transition(s, at) { state, time ->
            FocusFlow.onChosen(state, Flavor.USUAL, "Study accounting", time)
        }
        assertEquals("The two-minute timeout should already have chosen SPECIAL", Flavor.SPECIAL, actual.state.flavor)
    }

    @Test fun lateCheckinMustRemainUnanswered() {
        val running = FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, "Study accounting", start, end)!!.state
        val s = FocusFlow.onFocusPhaseEnded(running, 25, start + 25 * minute).state
        val at = start + 31 * minute
        val caught = FocusFlow.transition(s, at) { state, time -> FocusFlow.onAnswer(state, false, time) }
        val effects = caught.effects
        assertTrue("A check-in more than five minutes late must be recorded UNANSWERED",
            effects.any { it is FlowEffect.RecordCheckin && it.answer == CheckinAnswer.UNANSWERED })
        assertEquals("A stale No must not initiate a reset", FlowStage.RUNNING, caught.state.stage)
    }

    @Test fun localizedAdviceMustRemainAvailableForFollowup() {
        val reply = "## اگلا ہفتہ\nاگر دباؤ ہو تو فون دور رکھوں گا۔"
        assertFalse("Localized headings are required by the prompt; advice must survive them",
            ReportInputs.adviceOf(reply).isBlank())
    }

    @Test fun taggedAdviceWorksInAnyLanguageAndIsNotDisplayed() {
        val reply = "<!-- advice:next-week -->\n## La semana próxima\nSi tengo estrés, guardaré el teléfono.\n## Otro\nNo es consejo."
        assertEquals("Si tengo estrés, guardaré el teléfono.", ReportInputs.adviceOf(reply))
        assertFalse(MarkdownBlocks.parse(reply).any { it is MdBlock.Paragraph && it.spans.any { span -> span.text.contains("<!--") } })
    }

    @Test fun deletingGoalTextDoesNotLoseRecordedTotals() {
        val date = java.time.LocalDate.of(2026, 9, 1)
        val day = io.github.warleysr.dechainer.report.DayFact(date,
            io.github.warleysr.dechainer.day.DayKind.NORMAL, emptyList(), null, 0, 2, 3)
        val data = io.github.warleysr.dechainer.report.ReportData(date, date, emptyList(), emptyList(), listOf(day), emptyList())
        assertTrue(data.hasDataPoint)
        assertEquals(2, ReportInputs.summary(data).goalsDone)
        assertEquals(3, ReportInputs.summary(data).goalsTotal)
        assertTrue(ReportInputs.build(data).contains("2 of 3 goals done"))
    }

    @Test fun staleCheckinCannotAnswerTheFinalQuestion() {
        val running = FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, "Study accounting", start, end)!!.state
        val checkin = FocusFlow.onFocusPhaseEnded(running, 25, end - minute).state
        val result = FocusFlow.transition(checkin, end) { state, time -> FocusFlow.onAnswer(state, false, time) }
        assertEquals(FlowStage.FINAL_ASK, result.state.stage)
        assertFalse(result.effects.any { it is FlowEffect.RecordCheckin && it.answer == CheckinAnswer.NO })
    }

    @Test fun onlySelectedHomeIsExemptAndDefaultChangesAreHonored() {
        val homes = setOf("home", "browser.home")
        assertEquals(setOf("home"), io.github.warleysr.dechainer.data.LockSafety.protectedHomes(homes, "home"))
        assertEquals(setOf("browser.home"), io.github.warleysr.dechainer.data.LockSafety.protectedHomes(homes, "browser.home"))
        assertEquals(homes, io.github.warleysr.dechainer.data.LockSafety.protectedHomes(homes, null))
    }
}
