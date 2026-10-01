package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.UrgeStart
import io.github.warleysr.dechainer.urge.Breathing
import io.github.warleysr.dechainer.urge.FixedQuestions
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeFlowRulesTest {
    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun entry(
        id: Long = 1, createdAt: Long = now, kind: UrgeKind = UrgeKind.URGE, status: UrgeStatus = UrgeStatus.LOCKED,
        lockStart: Long? = now, lockEnd: Long? = now + 10 * minute
    ) = UrgeEntry(id, createdAt, kind, UrgeSource.HOME, lockStart, lockEnd, status, null, null, null, null)

    // ---- transitions ----

    @Test
    fun theDeepDiveIsOnlyReachedThroughSavedAnswers() {
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.LOCKED, UrgeStatus.WRITING))
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.WRITING, UrgeStatus.QUESTIONS))
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.QUESTIONS, UrgeStatus.PENDING_DEEPDIVE))
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.PENDING_DEEPDIVE, UrgeStatus.DONE))
        // A note cannot be deleted by jumping to DONE from anywhere earlier.
        for (from in listOf(UrgeStatus.LOCKED, UrgeStatus.WRITING, UrgeStatus.QUESTIONS)) {
            assertFalse("$from -> DONE", UrgeFlowRules.canMove(from, UrgeStatus.DONE))
        }
    }

    @Test
    fun nothingMovesOutOfAFinishedEntry() {
        for (to in UrgeStatus.entries) {
            assertFalse(UrgeFlowRules.canMove(UrgeStatus.DONE, to))
            assertFalse(UrgeFlowRules.canMove(UrgeStatus.SKIPPED, to))
        }
        assertTrue(UrgeStatus.DONE.isFinal && UrgeStatus.SKIPPED.isFinal)
        assertFalse(UrgeStatus.PENDING_DEEPDIVE.isFinal)
    }

    @Test
    fun anAnsweredEntryCannotBeSkippedOrGoBack() {
        assertFalse(UrgeFlowRules.canMove(UrgeStatus.QUESTIONS, UrgeStatus.SKIPPED))
        assertFalse(UrgeFlowRules.canMove(UrgeStatus.PENDING_DEEPDIVE, UrgeStatus.QUESTIONS))
        assertFalse(UrgeFlowRules.canMove(UrgeStatus.WRITING, UrgeStatus.LOCKED))
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.LOCKED, UrgeStatus.SKIPPED))
        assertTrue(UrgeFlowRules.canMove(UrgeStatus.WRITING, UrgeStatus.SKIPPED))
    }

    // ---- the breathing window ----

    @Test
    fun aFreshLockBreathesForItsTenMinutes() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.Started(now + 10 * minute), now)
        assertEquals(BreathingWindowOf(now, now + 10 * minute, true), BreathingWindowOf(w.startsAt, w.endsAt, w.lockStarted))
        assertEquals(10 * minute, w.totalMs)
    }

    @Test
    fun aSecondTapBreathesUntilTheRunningLockEndsAndNeverLonger() {
        val started = now - 4 * minute
        val w = UrgeFlowRules.breathingFor(UrgeStart.AlreadyRunning(now + 6 * minute), now, runningLockStartedAt = started)
        assertEquals(started, w.startsAt)
        assertEquals(now + 6 * minute, w.endsAt)
        assertFalse(w.lockStarted)
    }

    @Test
    fun insideAFocusBlockOrAPunishmentDayTheBreathingStillRunsTenMinutesWithoutALock() {
        for (by in listOf(LockMode.FOCUS_BLOCK, LockMode.PUNISHMENT_DAY)) {
            val w = UrgeFlowRules.breathingFor(UrgeStart.Covered(by, now + 10 * minute), now)
            assertEquals(10 * minute, w.totalMs)
            assertFalse(w.lockStarted)
        }
        val none = UrgeFlowRules.breathingFor(UrgeStart.Unavailable(now + 10 * minute), now)
        assertEquals(10 * minute, none.totalMs)
        assertFalse(none.lockStarted)
    }

    @Test
    fun aLockStartedInTheFutureIsNeverUsedAsTheWindowStart() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.AlreadyRunning(now + minute), now, runningLockStartedAt = now + 5 * minute)
        assertEquals(now, w.startsAt)
    }

    private data class BreathingWindowOf(val a: Long, val b: Long, val c: Boolean)

    // ---- which screen ----

    @Test
    fun theBreathingHoldsUntilTheLockEndsThenTheWritingScreenShows() {
        val e = entry()
        assertEquals(UrgeScreen.BREATHING, UrgeFlowRules.screenFor(e, now))
        assertEquals(UrgeScreen.BREATHING, UrgeFlowRules.screenFor(e, now + 10 * minute - 1))
        assertEquals(UrgeScreen.WRITING, UrgeFlowRules.screenFor(e, now + 10 * minute))
    }

    @Test
    fun aSlipHasNoBreathingEvenIfItCarriesLockTimes() {
        val slip = entry(kind = UrgeKind.SLIP, status = UrgeStatus.WRITING)
        assertEquals(UrgeScreen.WRITING, UrgeFlowRules.screenFor(slip, now))
        assertEquals(UrgeScreen.WRITING, UrgeFlowRules.screenFor(slip.copy(status = UrgeStatus.LOCKED), now))
    }

    @Test
    fun anEntryWithNoLockTimesGoesStraightToWriting() {
        assertEquals(UrgeScreen.WRITING, UrgeFlowRules.screenFor(entry(lockStart = null, lockEnd = null), now))
    }

    @Test
    fun everyStatusHasAScreen() {
        assertEquals(UrgeScreen.QUESTIONS, UrgeFlowRules.screenFor(entry(status = UrgeStatus.QUESTIONS), now))
        assertEquals(UrgeScreen.DEEP_DIVE, UrgeFlowRules.screenFor(entry(status = UrgeStatus.PENDING_DEEPDIVE), now))
        assertEquals(UrgeScreen.DEEP_DIVE, UrgeFlowRules.screenFor(entry(status = UrgeStatus.DONE), now))
        assertEquals(UrgeScreen.FINISHED, UrgeFlowRules.screenFor(entry(status = UrgeStatus.SKIPPED), now))
        UrgeStatus.entries.forEach { UrgeFlowRules.screenFor(entry(status = it), now) }
    }

    // ---- resuming ----

    @Test
    fun theNewestUnfinishedEntryInsideTheWindowIsResumed() {
        val old = entry(id = 1, createdAt = now - 20 * minute, status = UrgeStatus.WRITING, lockEnd = now - 10 * minute)
        val newer = entry(id = 2, createdAt = now - 5 * minute, status = UrgeStatus.LOCKED, lockEnd = now + 5 * minute)
        assertEquals(2L, UrgeFlowRules.resumable(listOf(old, newer), now)?.id)
    }

    @Test
    fun anEntryPastTheWindowStaysAStub() {
        val stale = entry(createdAt = now - 3 * 60 * minute, status = UrgeStatus.LOCKED, lockEnd = now - 170 * minute)
        assertNull(UrgeFlowRules.resumable(listOf(stale), now))
    }

    @Test
    fun theWindowCountsFromTheEndOfTheLockNotFromItsStart() {
        // Started 65 minutes ago but the lock only ended 55 minutes ago: still resumable.
        val e = entry(createdAt = now - 65 * minute, status = UrgeStatus.WRITING, lockEnd = now - 55 * minute)
        assertEquals(1L, UrgeFlowRules.resumable(listOf(e), now)?.id)
    }

    @Test
    fun finishedAndPendingEntriesAreNeverResumedByTheScreen() {
        val all = listOf(UrgeStatus.DONE, UrgeStatus.SKIPPED, UrgeStatus.PENDING_DEEPDIVE).mapIndexed { i, s ->
            entry(id = i + 1L, status = s)
        }
        assertNull(UrgeFlowRules.resumable(all, now))
    }

    // ---- small rules ----

    @Test
    fun aSlipStartsWritingAndAnUrgeStartsLocked() {
        assertEquals(UrgeStatus.LOCKED, UrgeFlowRules.startingStatus(UrgeKind.URGE))
        assertEquals(UrgeStatus.WRITING, UrgeFlowRules.startingStatus(UrgeKind.SLIP))
    }

    @Test
    fun aNoteNeedsAFewRealCharacters() {
        assertFalse(UrgeFlowRules.noteReady(""))
        assertFalse(UrgeFlowRules.noteReady("  a "))
        assertTrue(UrgeFlowRules.noteReady(" abc "))
    }

    @Test
    fun theFixedQuestionsAreUsedWhenTheAiDidNotAnswerOrTookTooLong() {
        assertTrue(UrgeFlowRules.useFixedQuestions(aiAnswered = false, waitedMs = 0))
        assertTrue(UrgeFlowRules.useFixedQuestions(aiAnswered = true, waitedMs = Rules.AI_QUESTIONS_TIMEOUT_MS + 1))
        assertFalse(UrgeFlowRules.useFixedQuestions(aiAnswered = true, waitedMs = Rules.AI_QUESTIONS_TIMEOUT_MS))
        assertEquals(20_000L, Rules.AI_QUESTIONS_TIMEOUT_MS)
    }

    @Test
    fun theThreeFixedQuestionsAreTheBlueprintsInOrder() {
        val q = FixedQuestions.build(listOf("a", "b", "c"))
        assertEquals(listOf("before", "feeling", "where"), q.map { it.id })
        assertEquals(listOf("a", "b", "c"), q.map { it.prompt })
        assertEquals(3, FixedQuestions.IDS.size)
        try {
            FixedQuestions.build(listOf("only one"))
            org.junit.Assert.fail("expected a failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- breathing ----

    @Test
    fun theCircleGrowsForFourSecondsAndShrinksForSix() {
        assertEquals(Breathing.Phase.INHALE, Breathing.phaseAt(0))
        assertEquals(Breathing.Phase.INHALE, Breathing.phaseAt(3_999))
        assertEquals(Breathing.Phase.EXHALE, Breathing.phaseAt(4_000))
        assertEquals(Breathing.Phase.EXHALE, Breathing.phaseAt(9_999))
        assertEquals(Breathing.Phase.INHALE, Breathing.phaseAt(10_000))
        assertEquals(0f, Breathing.circleAt(0), 0.001f)
        assertEquals(0.5f, Breathing.circleAt(2_000), 0.001f)
        assertEquals(1f, Breathing.circleAt(4_000), 0.001f)
        assertEquals(0.5f, Breathing.circleAt(7_000), 0.001f)
        assertEquals(0f, Breathing.circleAt(10_000), 0.001f)
        assertEquals(Breathing.circleAt(2_500), Breathing.circleAt(12_500), 0.001f)
    }

    @Test
    fun aNegativeElapsedTimeIsTreatedAsTheStart() {
        assertEquals(0f, Breathing.circleAt(-5_000), 0.001f)
        assertEquals(0, Breathing.promptIndex(-1))
    }

    @Test
    fun thereIsOnePromptPerMinuteAndTheLastOneStays() {
        assertEquals(0, Breathing.promptIndex(0))
        assertEquals(0, Breathing.promptIndex(59_999))
        assertEquals(1, Breathing.promptIndex(60_000))
        assertEquals(9, Breathing.promptIndex(9 * 60_000L))
        assertEquals(9, Breathing.promptIndex(10 * 60_000L))
        assertEquals(9, Breathing.promptIndex(99 * 60_000L))
    }

    @Test
    fun thePersonalReasonReplacesOnlyTheFifthPrompt() {
        for (i in 0 until Breathing.PROMPT_COUNT) assertFalse(Breathing.usesReason(i, ""))
        assertTrue(Breathing.usesReason(4, "For my daughter"))
        assertFalse(Breathing.usesReason(3, "For my daughter"))
        assertFalse(Breathing.usesReason(4, "   "))
    }

    @Test
    fun theRingShowsTheTimeLeft() {
        assertEquals(1f, Breathing.ringLeft(0, 600_000), 0.001f)
        assertEquals(0.5f, Breathing.ringLeft(300_000, 600_000), 0.001f)
        assertEquals(0f, Breathing.ringLeft(700_000, 600_000), 0.001f)
        assertEquals(0f, Breathing.ringLeft(0, 0), 0.001f)
    }
}
