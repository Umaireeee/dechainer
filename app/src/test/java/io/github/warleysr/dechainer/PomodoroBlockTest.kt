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
}
