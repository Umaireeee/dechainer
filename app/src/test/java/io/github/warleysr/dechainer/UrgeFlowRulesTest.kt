package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.UrgeStart
import io.github.warleysr.dechainer.urge.Breathing
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.urge.UrgeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeFlowRulesTest {
    private val minute = 60_000L
    private val now = 1_000_000_000L
    private val ten = 10 * minute

    private fun entry(
        id: Long = 1, createdAt: Long = now,
        lockStart: Long? = now, lockEnd: Long? = now + ten
    ) = UrgeEntry(id, createdAt, UrgeSource.HOME, lockStart, lockEnd)

    // ---- the breathing window ----

    @Test
    fun aFreshLockBreathesForItsChosenLength() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.Started(now + ten), now, lockMs = ten)
        assertEquals(now, w.startsAt)
        assertEquals(now + ten, w.endsAt)
        assertTrue(w.lockStarted)
        assertEquals(ten, w.totalMs)
    }

    @Test
    fun aFreshLockBreathesFromTheMomentItWasStoredNotFromALaterRead() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.Started(now + ten), now + 50, lockMs = ten, lockStartedAt = now)
        assertEquals(now, w.startsAt)
        assertEquals(ten, w.totalMs)
    }

    @Test
    fun aSecondTapBreathesUntilTheRunningLockEndsAndNeverLonger() {
        val started = now - 4 * minute
        val w = UrgeFlowRules.breathingFor(UrgeStart.AlreadyRunning(now + 6 * minute), now, lockMs = ten, lockStartedAt = started)
        assertEquals(started, w.startsAt)
        assertEquals(now + 6 * minute, w.endsAt)
        assertFalse(w.lockStarted)
    }

    @Test
    fun insideAFocusBlockTheBreathingStillRunsWithoutALock() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + ten), now, lockMs = ten)
        assertEquals(ten, w.totalMs)
        assertFalse(w.lockStarted)

        val none = UrgeFlowRules.breathingFor(UrgeStart.Unavailable(now + ten), now, lockMs = ten)
        assertEquals(ten, none.totalMs)
        assertFalse(none.lockStarted)
    }

    @Test
    fun theChosenLengthIsHonouredForACoveredLockToo() {
        val five = 5 * minute
        val w = UrgeFlowRules.breathingFor(UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + five), now, lockMs = five)
        assertEquals(five, w.totalMs)
    }

    @Test
    fun aLockStartedInTheFutureIsNeverUsedAsTheWindowStart() {
        val w = UrgeFlowRules.breathingFor(UrgeStart.AlreadyRunning(now + minute), now, lockMs = ten, lockStartedAt = now + 5 * minute)
        assertEquals(now, w.startsAt)
    }

    // ---- which screen ----

    @Test
    fun theBreathingHoldsUntilTheLockEndsThenTheFlowIsFinished() {
        val e = entry()
        assertEquals(UrgeScreen.BREATHING, UrgeFlowRules.screenFor(e, now))
        assertEquals(UrgeScreen.BREATHING, UrgeFlowRules.screenFor(e, now + ten - 1))
        assertEquals(UrgeScreen.FINISHED, UrgeFlowRules.screenFor(e, now + ten))
        assertEquals(UrgeScreen.FINISHED, UrgeFlowRules.screenFor(e, now + ten + minute))
    }

    // ---- resuming ----

    @Test
    fun theNewestRunningLockIsResumed() {
        val old = entry(id = 1, createdAt = now - 20 * minute, lockEnd = now - 10 * minute)
        val newer = entry(id = 2, createdAt = now - 5 * minute, lockEnd = now + 5 * minute)
        assertEquals(2L, UrgeFlowRules.resumable(listOf(old, newer), now)?.id)
    }

    @Test
    fun anEntryWhoseLockHasEndedIsNotResumed() {
        val stale = entry(createdAt = now - 3 * 60 * minute, lockEnd = now - 170 * minute)
        assertNull(UrgeFlowRules.resumable(listOf(stale), now))
    }

    @Test
    fun anEntryWithNoLockWindowIsNotResumed() {
        assertNull(UrgeFlowRules.resumable(listOf(entry(lockStart = null, lockEnd = null)), now))
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
    fun thereIsOnePromptPerMinuteAndTheListCyclesForLongSits() {
        assertEquals(0, Breathing.promptIndex(0))
        assertEquals(0, Breathing.promptIndex(59_999))
        assertEquals(1, Breathing.promptIndex(60_000))
        assertEquals(9, Breathing.promptIndex(9 * 60_000L))
        assertEquals(0, Breathing.promptIndex(10 * 60_000L))
        assertEquals(9, Breathing.promptIndex((10 * Breathing.PROMPT_COUNT - 1) * 60_000L))
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
