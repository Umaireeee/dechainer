package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.UrgeStart
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

    // ---- the blackout window ----

    @Test
    fun aFreshLockRunsForItsChosenLength() {
        val w = UrgeFlowRules.windowFor(UrgeStart.Started(now + ten), now, lockMs = ten)
        assertEquals(now, w.startsAt)
        assertEquals(now + ten, w.endsAt)
        assertTrue(w.lockStarted)
        assertEquals(ten, w.totalMs)
    }

    @Test
    fun aFreshLockRunsFromTheMomentItWasStoredNotFromALaterRead() {
        val w = UrgeFlowRules.windowFor(UrgeStart.Started(now + ten), now + 50, lockMs = ten, lockStartedAt = now)
        assertEquals(now, w.startsAt)
        assertEquals(ten, w.totalMs)
    }

    @Test
    fun aSecondTapRunsUntilTheRunningLockEndsAndNeverLonger() {
        val started = now - 4 * minute
        val w = UrgeFlowRules.windowFor(UrgeStart.AlreadyRunning(now + 6 * minute), now, lockMs = ten, lockStartedAt = started)
        assertEquals(started, w.startsAt)
        assertEquals(now + 6 * minute, w.endsAt)
        assertFalse(w.lockStarted)
    }

    @Test
    fun insideAFocusBlockTheBlackoutStillRunsWithoutALock() {
        val w = UrgeFlowRules.windowFor(UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + ten), now, lockMs = ten)
        assertEquals(ten, w.totalMs)
        assertFalse(w.lockStarted)

        val none = UrgeFlowRules.windowFor(UrgeStart.Unavailable(now + ten), now, lockMs = ten)
        assertEquals(ten, none.totalMs)
        assertFalse(none.lockStarted)
    }

    @Test
    fun theChosenLengthIsHonouredForACoveredLockToo() {
        val five = 5 * minute
        val w = UrgeFlowRules.windowFor(UrgeStart.Covered(LockMode.FOCUS_BLOCK, now + five), now, lockMs = five)
        assertEquals(five, w.totalMs)
    }

    @Test
    fun aLockStartedInTheFutureIsNeverUsedAsTheWindowStart() {
        val w = UrgeFlowRules.windowFor(UrgeStart.AlreadyRunning(now + minute), now, lockMs = ten, lockStartedAt = now + 5 * minute)
        assertEquals(now, w.startsAt)
    }

    // ---- which screen ----

    @Test
    fun theBlackoutHoldsUntilTheLockEndsThenTheFlowIsFinished() {
        val e = entry()
        assertEquals(UrgeScreen.BLACKOUT, UrgeFlowRules.screenFor(e, now))
        assertEquals(UrgeScreen.BLACKOUT, UrgeFlowRules.screenFor(e, now + ten - 1))
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
}
