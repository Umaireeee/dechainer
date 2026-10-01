package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.focus.Phase
import io.github.warleysr.dechainer.focus.PomodoroCore
import io.github.warleysr.dechainer.focus.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PomodoroBlockTest {
    // ---- #4: a second block request cannot replace a running block ----

    @Test
    fun aBlockStartsOnAnIdleTimer() {
        val now = 1_000_000L
        val started = PomodoroCore.beginBlockIfIdle(PomodoroState(), endsAt = now + 3 * 3_600_000L, now = now, firstSessionMinutes = 50)
        assertTrue(started.inBlock)
        assertEquals(now + 3 * 3_600_000L, started.blockEndsAt)
        assertEquals(Phase.FOCUS, started.phase)
    }

    @Test
    fun aSecondBlockRequestLeavesTheRunningBlockAlone() {
        val now = 1_000_000L
        val long = PomodoroCore.beginBlockIfIdle(PomodoroState(), now + 3 * 3_600_000L, now, 50)
        val again = PomodoroCore.beginBlockIfIdle(long, now + 25 * 60_000L, now + 1_000L, 25)
        assertEquals(long, again)
        assertEquals(now + 3 * 3_600_000L, again.blockEndsAt)
    }

    // ---- A block that runs as one stretch (Phase 4) ----

    private val hour = 3_600_000L

    @Test
    fun aContinuousBlockHoldsNoPhaseButCountsAsABlock() {
        val now = 1_000_000L
        val s = PomodoroCore.beginContinuousBlockIfIdle(PomodoroState(), now + 2 * hour)
        assertTrue(s.inBlock)
        assertTrue("no phase runs", s.isIdle)
        assertTrue(s.blockActiveAt(now + hour))
        assertEquals(now + 2 * hour, s.blockEndsAt)
    }

    @Test
    fun aRequestForAnotherBlockCannotReplaceAContinuousBlock() {
        val now = 1_000_000L
        val one = PomodoroCore.beginContinuousBlockIfIdle(PomodoroState(), now + 2 * hour)
        assertEquals(one, PomodoroCore.beginContinuousBlockIfIdle(one, now + 30 * 60_000L))
        assertEquals("the phased kind cannot replace it either", one, PomodoroCore.beginBlockIfIdle(one, now + 30 * 60_000L, now, 25))
    }

    @Test
    fun aPlainStartDoesNotBeginASessionInAContinuousBlock() {
        val now = 1_000_000L
        val one = PomodoroCore.beginContinuousBlockIfIdle(PomodoroState(), now + 2 * hour)
        assertEquals(one, PomodoroCore.start(one, now + 1_000L, io.github.warleysr.dechainer.focus.PomodoroSettings()))
    }

    @Test
    fun theUsualPhasesBeginInsideAContinuousBlockAndKeepItsEnd() {
        val now = 1_000_000L
        val one = PomodoroCore.beginContinuousBlockIfIdle(PomodoroState(), now + 2 * hour)
        val phased = PomodoroCore.startPhasesInBlock(one, now + 60_000L, 25)
        assertTrue(phased.isRunning)
        assertEquals(now + 60_000L + 25 * 60_000L, phased.endsAt)
        assertEquals(now + 2 * hour, phased.blockEndsAt)
        assertEquals(Phase.FOCUS, phased.phase)
    }

    @Test
    fun startingThePhasesOnlyWorksInAnIdleBlock() {
        val now = 1_000_000L
        assertEquals(PomodoroState(), PomodoroCore.startPhasesInBlock(PomodoroState(), now, 25))
        val running = PomodoroCore.beginBlockIfIdle(PomodoroState(), now + 2 * hour, now, 25)
        assertEquals(running, PomodoroCore.startPhasesInBlock(running, now + 1_000L, 10))
    }

    @Test
    fun droppingThePhasesLeavesAContinuousBlockWithTheSameEndAndCycle() {
        val now = 1_000_000L
        val running = PomodoroCore.beginBlockIfIdle(PomodoroState(focusDoneInCycle = 2), now + 2 * hour, now, 25).copy(focusDoneInCycle = 2)
        val plain = PomodoroCore.dropPhasesInBlock(running)
        assertTrue(plain.isIdle)
        assertTrue(plain.inBlock)
        assertEquals(now + 2 * hour, plain.blockEndsAt)
        assertEquals(2, plain.focusDoneInCycle)
        assertEquals(PomodoroState(), PomodoroCore.dropPhasesInBlock(PomodoroState()))
    }

    @Test
    fun aPausedPhaseInABlockStillResumesWhereItStopped() {
        val now = 1_000_000L
        val running = PomodoroCore.beginBlockIfIdle(PomodoroState(), now + 2 * hour, now, 25)
        val paused = PomodoroCore.pause(running, now + 10 * 60_000L)
        assertTrue(paused.isPaused)
        val resumed = PomodoroCore.start(paused, now + 30 * 60_000L, io.github.warleysr.dechainer.focus.PomodoroSettings())
        assertEquals(now + 30 * 60_000L + 15 * 60_000L, resumed.endsAt)
    }
}
