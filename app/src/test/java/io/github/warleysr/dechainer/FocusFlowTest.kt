package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FlowEffect
import io.github.warleysr.dechainer.focus.FlowStage
import io.github.warleysr.dechainer.focus.FlowState
import io.github.warleysr.dechainer.focus.FlowStep
import io.github.warleysr.dechainer.focus.FocusFlow
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blueprint 6.3: every transition of a focus block, and every timeout, with no Android. */
class FocusFlowTest {
    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L
    private val end = t0 + 120 * minute

    private fun scheduled() = FocusFlow.begin(FocusSource.SCHEDULED, null, null, t0, end)!!
    private fun usual() = FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, "Tax chapter 4", t0, end)!!
    private fun special() = FocusFlow.begin(FocusSource.MANUAL, Flavor.SPECIAL, "Mock exam", t0, end)!!

    /** A usual block just after its first focus phase of 25 minutes, with the check-in showing. */
    private fun checkin(): FlowState = FocusFlow.onFocusPhaseEnded(usual().state, 25, t0 + 25 * minute).state

    /** A usual block in the reset, "No" given at [t0] + 25 minutes. */
    private fun inReset(): FlowState = FocusFlow.onAnswer(checkin(), false, t0 + 26 * minute).state

    // ---- Starting ----

    @Test
    fun aManualUsualStartBeginsThePhasesAtOnce() {
        val step = usual()
        assertEquals(FlowStage.RUNNING, step.state.stage)
        assertEquals(Flavor.USUAL, step.state.flavor)
        assertEquals("Tax chapter 4", step.state.purpose)
        assertEquals(listOf<FlowEffect>(FlowEffect.StartPhases), step.effects)
    }

    @Test
    fun aManualSpecialStartIsOneContinuousBrick() {
        val step = special()
        assertEquals(FlowStage.SPECIAL, step.state.stage)
        assertTrue("no phases", step.effects.isEmpty())
    }

    @Test
    fun aManualStartNeedsAPurposeOfThreeCharacters() {
        assertNull(FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, "ab", t0, end))
        assertNull(FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, "   ", t0, end))
        assertNull(FocusFlow.begin(FocusSource.MANUAL, Flavor.USUAL, null, t0, end))
        assertNotNull(FocusFlow.begin(FocusSource.MANUAL, Flavor.SPECIAL, "abc", t0, end))
    }

    @Test
    fun aManualStartNeedsAFlavor() {
        assertNull(FocusFlow.begin(FocusSource.MANUAL, null, "Tax chapter 4", t0, end))
    }

    @Test
    fun aScheduledStartWaitsAtThePromptWhateverItIsGiven() {
        val step = FocusFlow.begin(FocusSource.SCHEDULED, Flavor.USUAL, "ignored", t0, end)!!
        assertEquals(FlowStage.PROMPT, step.state.stage)
        assertNull(step.state.flavor)
        assertEquals("", step.state.purpose)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aPurposeIsOneLineOfAtMostEightyCharacters() {
        assertEquals("a b", FocusFlow.cleanPurpose("  a\n b  "))
        assertEquals(80, FocusFlow.cleanPurpose("x".repeat(200))!!.length)
        assertNull(FocusFlow.cleanPurpose("ab"))
    }

    // ---- The prompt ----

    @Test
    fun choosingUsualAtThePromptStartsThePhasesAndStoresTheChoice() {
        val step = FocusFlow.onChosen(scheduled().state, Flavor.USUAL, "Audit notes", t0 + 30_000)
        assertEquals(FlowStage.RUNNING, step.state.stage)
        assertEquals("Audit notes", step.state.purpose)
        assertEquals(listOf(FlowEffect.StartPhases, FlowEffect.UpdateSession), step.effects)
    }

    @Test
    fun choosingSpecialAtThePromptRunsOneContinuousBrick() {
        val step = FocusFlow.onChosen(scheduled().state, Flavor.SPECIAL, "Audit notes", t0 + 30_000)
        assertEquals(FlowStage.SPECIAL, step.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.UpdateSession), step.effects)
    }

    @Test
    fun aTooShortPurposeAtThePromptChangesNothing() {
        val s = scheduled().state
        assertEquals(FlowStep(s), FocusFlow.onChosen(s, Flavor.USUAL, "ab", t0 + 1_000))
    }

    @Test
    fun noAnswerWithinTwoMinutesRunsAsSpecialWithNoPurpose() {
        val before = FocusFlow.tick(scheduled().state, t0 + 2 * minute - 1)
        assertEquals(FlowStage.PROMPT, before.state.stage)
        val step = FocusFlow.tick(scheduled().state, t0 + 2 * minute)
        assertEquals(FlowStage.SPECIAL, step.state.stage)
        assertEquals(Flavor.SPECIAL, step.state.flavor)
        assertEquals("", step.state.purpose)
        assertEquals(listOf<FlowEffect>(FlowEffect.UpdateSession), step.effects)
    }

    @Test
    fun aPromptIgnoredForTheWholeWindowEndsAsSpecialWithTheFinalQuestion() {
        val step = FocusFlow.tick(scheduled().state, end)
        assertEquals(FlowStage.FINAL_ASK, step.state.stage)
        assertEquals(Flavor.SPECIAL, step.state.flavor)
        assertTrue(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 120) in step.effects)
        assertTrue(FlowEffect.RingFinalAsk in step.effects)
    }

    @Test
    fun anAnswerAfterTheTimeoutIsTooLate() {
        val special = FocusFlow.tick(scheduled().state, t0 + 3 * minute).state
        assertEquals(FlowStep(special), FocusFlow.onChosen(special, Flavor.USUAL, "Audit notes", t0 + 3 * minute))
    }

    // ---- Check-ins in a usual block ----

    @Test
    fun aFinishedFocusPhaseRingsTheCheckin() {
        val step = FocusFlow.onFocusPhaseEnded(usual().state, 25, t0 + 25 * minute)
        assertEquals(FlowStage.CHECKIN, step.state.stage)
        assertEquals(25, step.state.focusedMinutes)
        assertEquals(t0 + 25 * minute, step.state.stageStartedAt)
        assertEquals(listOf<FlowEffect>(FlowEffect.RingCheckin), step.effects)
    }

    @Test
    fun yesCarriesOnAsScheduled() {
        val step = FocusFlow.onAnswer(checkin(), true, t0 + 26 * minute)
        assertEquals(FlowStage.RUNNING, step.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.RecordCheckin(t0 + 26 * minute, CheckinAnswer.YES, ResetResult.NONE)), step.effects)
    }

    @Test
    fun noStartsTheResetAndPausesThePhaseClock() {
        val step = FocusFlow.onAnswer(checkin(), false, t0 + 26 * minute)
        assertEquals(FlowStage.RESET_MEDITATION, step.state.stage)
        assertEquals(t0 + 26 * minute, step.state.stageStartedAt)
        assertEquals(listOf<FlowEffect>(FlowEffect.PausePhases), step.effects)
    }

    @Test
    fun noAnswerForFiveMinutesIsRecordedAsUnansweredAndCarriesOn() {
        val asked = checkin()
        assertEquals(FlowStage.CHECKIN, FocusFlow.tick(asked, asked.stageStartedAt + 5 * minute - 1).state.stage)
        val step = FocusFlow.tick(asked, asked.stageStartedAt + 5 * minute)
        assertEquals(FlowStage.RUNNING, step.state.stage)
        assertEquals(
            listOf<FlowEffect>(FlowEffect.RecordCheckin(asked.stageStartedAt + 5 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE)),
            step.effects
        )
    }

    @Test
    fun aCheckinStillOpenWhenTheNextFocusPhaseEndsIsClosedAsUnansweredFirst() {
        val step = FocusFlow.onFocusPhaseEnded(checkin(), 25, t0 + 33 * minute)
        assertEquals(FlowStage.CHECKIN, step.state.stage)
        assertEquals(50, step.state.focusedMinutes)
        assertEquals(
            listOf(FlowEffect.RecordCheckin(t0 + 33 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE), FlowEffect.RingCheckin),
            step.effects
        )
    }

    // ---- The reset ----

    @Test
    fun theResetIsFiveMinutesOfMeditationThenFiveOutsideThenTheQuestion() {
        val reset = inReset()
        assertEquals(FlowStage.RESET_MEDITATION, FocusFlow.tick(reset, reset.stageStartedAt + 5 * minute - 1).state.stage)
        val outside = FocusFlow.tick(reset, reset.stageStartedAt + 5 * minute)
        assertEquals(FlowStage.RESET_OUTSIDE, outside.state.stage)
        assertEquals(reset.stageStartedAt + 5 * minute, outside.state.stageStartedAt)
        assertTrue(outside.effects.isEmpty())
        val ask = FocusFlow.tick(outside.state, reset.stageStartedAt + 10 * minute)
        assertEquals(FlowStage.RESET_ASK, ask.state.stage)
        assertTrue("nothing is written until the reset settles", ask.effects.isEmpty())
    }

    @Test
    fun aLateWakeUpCrossesBothHalvesOfTheResetAtOnce() {
        val reset = inReset()
        val step = FocusFlow.tick(reset, reset.stageStartedAt + 40 * minute)
        assertEquals(FlowStage.RESET_ASK, step.state.stage)
        assertEquals(reset.stageStartedAt + 10 * minute, step.state.stageStartedAt)
    }

    @Test
    fun readyResumesThePhasesAndRecordsTheNoWithItsResult() {
        val ask = FocusFlow.tick(inReset(), t0 + 60 * minute).state
        val step = FocusFlow.onReady(ask, true, t0 + 61 * minute)
        assertEquals(FlowStage.RUNNING, step.state.stage)
        assertEquals(
            listOf(FlowEffect.ResumePhases, FlowEffect.RecordCheckin(t0 + 26 * minute, CheckinAnswer.NO, ResetResult.READY)),
            step.effects
        )
    }

    @Test
    fun notReadyShowsTheMessageThenAPlainTimerWithNoMorePhases() {
        val ask = FocusFlow.tick(inReset(), t0 + 60 * minute).state
        val step = FocusFlow.onReady(ask, false, t0 + 61 * minute)
        assertEquals(FlowStage.NOT_READY, step.state.stage)
        assertEquals(
            listOf(FlowEffect.StopPhases, FlowEffect.RecordCheckin(t0 + 26 * minute, CheckinAnswer.NO, ResetResult.NOT_READY)),
            step.effects
        )
        val still = FocusFlow.tick(step.state, t0 + 61 * minute + 14_999)
        assertEquals(FlowStage.NOT_READY, still.state.stage)
        val plain = FocusFlow.tick(step.state, t0 + 61 * minute + 15_000)
        assertEquals(FlowStage.PLAIN_TIMER, plain.state.stage)
        assertTrue(plain.effects.isEmpty())
    }

    @Test
    fun thePlainTimerRunsToTheEndAndTheSessionIsLoggedAsPlainTimer() {
        val plain = FocusFlow.tick(FocusFlow.onReady(FocusFlow.tick(inReset(), t0 + 60 * minute).state, false, t0 + 61 * minute).state, t0 + 70 * minute).state
        assertEquals(FlowStage.PLAIN_TIMER, plain.stage)
        val over = FocusFlow.tick(plain, end)
        assertEquals(FlowStage.DONE, over.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.EndSession(end, SessionOutcome.PLAIN_TIMER, 25)), over.effects)
    }

    @Test
    fun answersOutsideTheirStageChangeNothing() {
        val running = usual().state
        assertEquals(FlowStep(running), FocusFlow.onAnswer(running, true, t0 + minute))
        assertEquals(FlowStep(running), FocusFlow.onReady(running, true, t0 + minute))
        val reset = inReset()
        assertEquals(FlowStep(reset), FocusFlow.onAnswer(reset, true, t0 + 27 * minute))
        assertEquals(FlowStep(reset), FocusFlow.onReady(reset, true, t0 + 27 * minute))
    }

    @Test
    fun aFocusPhaseEndingOutsideAUsualBlockChangesNothing() {
        val sp = special().state
        assertEquals(FlowStep(sp), FocusFlow.onFocusPhaseEnded(sp, 25, t0 + 25 * minute))
        val reset = inReset()
        assertEquals(FlowStep(reset), FocusFlow.onFocusPhaseEnded(reset, 25, t0 + 40 * minute))
    }

    // ---- The end of the block ----

    @Test
    fun aUsualBlockEndsWhenItsTimeComesAndIsLoggedAsCompleted() {
        val step = FocusFlow.tick(usual().state, end)
        assertEquals(FlowStage.DONE, step.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 0)), step.effects)
    }

    @Test
    fun theLastFocusPhaseEndingAtTheBlockEndAsksTheFinalQuestionAfterTheLock() {
        val running = usual().state.copy(focusedMinutes = 75)
        val step = FocusFlow.onFocusPhaseEnded(running, 25, end - 200)
        assertEquals(FlowStage.FINAL_ASK, step.state.stage)
        assertEquals(100, step.state.focusedMinutes)
        assertEquals(
            listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 100), FlowEffect.RingFinalAsk),
            step.effects
        )
    }

    @Test
    fun aCheckinStillOpenAtTheBlockEndBecomesTheFinalQuestion() {
        val asked = FocusFlow.onFocusPhaseEnded(usual().state, 25, end - 3 * minute).state
        val step = FocusFlow.tick(asked, end)
        assertEquals(FlowStage.FINAL_ASK, step.state.stage)
        assertEquals(end, step.state.stageStartedAt)
        assertEquals(listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 25), FlowEffect.RingFinalAsk), step.effects)
    }

    @Test
    fun aCheckinThatTimedOutBeforeTheBlockEndIsRecordedAsUnansweredAndTheBlockThenJustEnds() {
        val asked = FocusFlow.onFocusPhaseEnded(usual().state, 25, end - 8 * minute).state
        val step = FocusFlow.tick(asked, end + minute)
        assertEquals(FlowStage.DONE, step.state.stage)
        assertEquals(
            listOf(
                FlowEffect.RecordCheckin(end - 3 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE),
                FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 25)
            ),
            step.effects
        )
    }

    @Test
    fun aBlockThatEndsInsideTheResetKeepsTheNoWithNoResetResult() {
        val reset = inReset()
        val step = FocusFlow.tick(reset, end)
        assertEquals(FlowStage.DONE, step.state.stage)
        assertEquals(
            listOf(
                FlowEffect.RecordCheckin(t0 + 26 * minute, CheckinAnswer.NO, ResetResult.NONE),
                FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 25)
            ),
            step.effects
        )
    }

    // ---- Special: one yes/no at the end (D15) ----

    @Test
    fun aSpecialBlockAsksOneQuestionAtItsEndAndCountsTheWholeWindow() {
        val step = FocusFlow.tick(special().state, end)
        assertEquals(FlowStage.FINAL_ASK, step.state.stage)
        assertEquals(listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, 120), FlowEffect.RingFinalAsk), step.effects)
    }

    @Test
    fun theFinalAnswerIsRecordedYesOrNo() {
        val ask = FocusFlow.tick(special().state, end).state
        val yes = FocusFlow.onAnswer(ask, true, end + minute)
        assertEquals(FlowStage.DONE, yes.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.RecordCheckin(end + minute, CheckinAnswer.YES, ResetResult.NONE)), yes.effects)
        val no = FocusFlow.onAnswer(ask, false, end + minute)
        assertEquals(listOf<FlowEffect>(FlowEffect.RecordCheckin(end + minute, CheckinAnswer.NO, ResetResult.NONE)), no.effects)
    }

    @Test
    fun anUnansweredFinalQuestionIsRecordedAsUnansweredAfterFiveMinutes() {
        val ask = FocusFlow.tick(special().state, end).state
        assertEquals(FlowStage.FINAL_ASK, FocusFlow.tick(ask, end + 5 * minute - 1).state.stage)
        val step = FocusFlow.tick(ask, end + 5 * minute)
        assertEquals(FlowStage.DONE, step.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.RecordCheckin(end + 5 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE)), step.effects)
    }

    @Test
    fun aSpecialBlockHasNoMidSessionCheckins() {
        val sp = special().state
        assertEquals(FlowStep(sp), FocusFlow.tick(sp, t0 + 100 * minute))
        assertNull(FocusFlow.nextWake(sp)?.takeIf { it != end })
    }

    // ---- The system, the clock and storage ----

    @Test
    fun theSystemEndingABlockIsOnRecordAsSuch() {
        val step = FocusFlow.abort(checkin(), t0 + 30 * minute)
        assertEquals(FlowStage.DONE, step.state.stage)
        assertEquals(listOf<FlowEffect>(FlowEffect.EndSession(t0 + 30 * minute, SessionOutcome.ENDED_EARLY_BY_SYSTEM, 25)), step.effects)
        assertTrue(FocusFlow.abort(step.state, t0 + 31 * minute).effects.isEmpty())
    }

    @Test
    fun anAbortDoesNotOverwriteAnEndedSession() {
        val done = FocusFlow.tick(usual().state, end).state
        assertEquals(FlowStep(done), FocusFlow.abort(done, end + minute))
    }

    @Test
    fun nextWakeIsTheNearestTimeoutOrTheBlockEnd() {
        assertEquals(t0 + 2 * minute, FocusFlow.nextWake(scheduled().state))
        assertEquals(end, FocusFlow.nextWake(usual().state))
        assertEquals(t0 + 30 * minute, FocusFlow.nextWake(checkin()))
        assertEquals(t0 + 31 * minute, FocusFlow.nextWake(inReset()))
        val ask = FocusFlow.tick(special().state, end).state
        assertEquals(end + 5 * minute, FocusFlow.nextWake(ask))
        assertNull(FocusFlow.nextWake(FocusFlow.tick(usual().state, end).state))
        assertEquals("only the owner moves the question on, but the block still ends", end, FocusFlow.nextWake(FocusFlow.tick(inReset(), t0 + 60 * minute).state))
    }

    @Test
    fun theFlowSurvivesBeingStoredAndReadBack() {
        val s = inReset().copy(sessionId = 42L)
        assertEquals(s, FlowState.fromJson(s.toJson()))
        val prompt = scheduled().state
        assertEquals(prompt, FlowState.fromJson(prompt.toJson()))
    }

    @Test
    fun unreadableStoredFlowIsNullNotACrash() {
        assertNull(FlowState.fromJson(null))
        assertNull(FlowState.fromJson(""))
        assertNull(FlowState.fromJson("{not json"))
        assertNull(FlowState.fromJson("""{"stage":"NOPE","source":"MANUAL","startedAt":1,"plannedEndAt":2,"stageStartedAt":1}"""))
    }

    @Test
    fun aDoneFlowStaysDone() {
        val done = FocusFlow.tick(usual().state, end).state
        assertEquals(FlowStep(done), FocusFlow.tick(done, end + 100 * minute))
        assertNull(FocusFlow.nextWake(done))
    }
}
