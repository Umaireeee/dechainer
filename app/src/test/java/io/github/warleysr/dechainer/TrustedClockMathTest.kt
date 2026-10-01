package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.clock.ClockCheckpoint
import io.github.warleysr.dechainer.clock.TrustedClockMath
import io.github.warleysr.dechainer.security.ForcedRemovalClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedClockMathTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_800_000_000_000L   // an arbitrary wall time

    private fun read(wall: Long, elapsed: Long, boot: Int, last: ClockCheckpoint?) =
        TrustedClockMath.read(wall, elapsed, boot, last)

    @Test
    fun theFirstReadingIsTheWallTime() {
        val r = read(t0, elapsed = 5_000, boot = 7, last = null)
        assertEquals(t0, r.trustedMs)
        assertFalse(r.wallDistrusted)
        assertEquals(ClockCheckpoint(t0, 5_000, 7), r.checkpoint)
    }

    @Test
    fun anOrdinaryClockIsFollowedAsItIs() {
        val first = read(t0, 1_000, 7, null)
        val second = read(t0 + 10 * minute, 1_000 + 10 * minute, 7, first.checkpoint)
        assertEquals(t0 + 10 * minute, second.trustedMs)
        assertFalse(second.wallDistrusted)
    }

    @Test
    fun aSmallStepBackIsHeldNotFollowed() {
        val first = read(t0, 1_000, 7, null)
        // A network time correction pulls the wall 90 seconds back: inside the 2 minute tolerance.
        val second = read(t0 - 90_000, 1_000 + 5_000, 7, first.checkpoint)
        assertEquals("never goes backwards", t0, second.trustedMs)
        assertFalse("not an attack", second.wallDistrusted)
    }

    @Test
    fun theToleranceIsExactlyTwoMinutes() {
        val last = ClockCheckpoint(t0, 1_000, 7)
        assertFalse(read(t0 - 2 * minute, 2_000, 7, last).wallDistrusted)
        assertTrue(read(t0 - 2 * minute - 1, 2_000, 7, last).wallDistrusted)
    }

    @Test
    fun aClockSetBackAnHourKeepsCountingFromTheRunningTime() {
        val first = read(t0, 1_000, 7, null)
        // Ten real minutes later the wall says it is an hour earlier than before.
        val r = read(t0 - hour, 1_000 + 10 * minute, 7, first.checkpoint)
        assertTrue(r.wallDistrusted)
        assertEquals(t0 + 10 * minute, r.trustedMs)
    }

    @Test
    fun readingsKeepAdvancingWhileTheWallStaysWrongAndNothingIsCountedTwice() {
        var cp = read(t0, 0, 7, null).checkpoint
        val wrongWall = t0 - 3 * hour
        var elapsed = 0L
        var lastTrusted = t0
        repeat(6) {
            elapsed += 5 * minute
            val r = read(wrongWall + elapsed, elapsed, 7, cp)
            assertEquals("each step adds exactly the elapsed time", lastTrusted + 5 * minute, r.trustedMs)
            lastTrusted = r.trustedMs
            cp = r.checkpoint
        }
        assertEquals(t0 + 30 * minute, lastTrusted)
    }

    @Test
    fun anOldCheckpointGivesTheSameAnswerAsAChainOfNewerOnes() {
        // The process restarted and only an old checkpoint was stored: same result, no drift.
        val old = ClockCheckpoint(t0, 0, 7)
        val direct = read(t0 - 3 * hour + 30 * minute, 30 * minute, 7, old)
        assertEquals(t0 + 30 * minute, direct.trustedMs)
    }

    @Test
    fun aRebootCountsOnlyTheTimeSinceBoot() {
        val last = ClockCheckpoint(t0, 90 * minute, 7)
        // After the reboot elapsed-realtime restarted: 4 minutes since boot. The wall is wrong (1970).
        val r = read(wall = 3_000, elapsed = 4 * minute, boot = 8, last = last)
        assertTrue(r.wallDistrusted)
        assertEquals(t0 + 4 * minute, r.trustedMs)
        assertEquals(8, r.checkpoint.bootCount)
    }

    @Test
    fun aRebootWithAGoodWallClockJustFollowsTheWall() {
        val last = ClockCheckpoint(t0, 90 * minute, 7)
        val r = read(t0 + 2 * hour, 5 * minute, 8, last)
        assertEquals(t0 + 2 * hour, r.trustedMs)
        assertFalse(r.wallDistrusted)
    }

    @Test
    fun withoutABootCountTheElapsedClockGoingBackwardsMeansARestart() {
        val last = ClockCheckpoint(t0, 90 * minute, ForcedRemovalClock.UNKNOWN_BOOT)
        val r = read(wall = 0, elapsed = 3 * minute, boot = ForcedRemovalClock.UNKNOWN_BOOT, last = last)
        assertEquals(t0 + 3 * minute, r.trustedMs)
    }

    @Test
    fun aForwardJumpIsFollowedBecauseTheDateLockIsWhatStopsIt() {
        val last = ClockCheckpoint(t0, 1_000, 7)
        val r = read(t0 + 24 * hour, 2_000, 7, last)
        assertEquals(t0 + 24 * hour, r.trustedMs)
        assertFalse(r.wallDistrusted)
    }

    @Test
    fun theTrustedTimeNeverGoesBackwardsOverAnyMixOfWallReadings() {
        var cp: ClockCheckpoint? = null
        var elapsed = 0L
        var previous = Long.MIN_VALUE
        val wallOffsets = listOf(0L, 10 * minute, -5 * hour, -5 * hour + minute, 3 * hour, -1 * minute, -9 * hour, 0L, 2 * hour)
        for (offset in wallOffsets) {
            elapsed += minute
            val r = read(t0 + offset, elapsed, 7, cp)
            assertTrue("trusted ${r.trustedMs} < previous $previous", r.trustedMs >= previous)
            previous = r.trustedMs
            cp = r.checkpoint
        }
    }

    @Test
    fun aBlockStillEndsOnTimeWhenTheClockIsMovedBackAfterItStarted() {
        // A 30 minute block starts at t0. Ten minutes in, the clock is set back two hours.
        val blockEnd = t0 + 30 * minute
        val start = read(t0, 0, 7, null)
        val tenIn = read(t0 + 10 * minute, 10 * minute, 7, start.checkpoint)
        val movedBack = read(t0 - 2 * hour + 11 * minute, 11 * minute, 7, tenIn.checkpoint)
        assertTrue("wall now says before the block end by hours", t0 - 2 * hour + 11 * minute < blockEnd)
        val thirtyOneIn = read(t0 - 2 * hour + 31 * minute, 31 * minute, 7, movedBack.checkpoint)
        assertTrue("the trusted clock says the block is over", thirtyOneIn.trustedMs >= blockEnd)
    }

    @Test
    fun anAlarmIsAimedAtTheWallTimeThatMatchesTheTrustedTime() {
        // Wall is 3 hours behind trusted: an alarm for trusted + 5 min must be set 5 minutes after the wall now.
        val trustedNow = t0
        val wallNow = t0 - 3 * hour
        assertEquals(wallNow + 5 * minute, TrustedClockMath.toWall(trustedNow + 5 * minute, trustedNow, wallNow))
        // Agreeing clocks: unchanged.
        assertEquals(t0 + 7, TrustedClockMath.toWall(t0 + 7, t0, t0))
    }

    @Test
    fun aHandSetForwardIsIgnoredWhenAutomaticTimeIsOffAndFollowedWhenItIsOn() {
        val first = read(t0, 1_000, 7, null)
        // Ten minutes of running time, but the wall jumped a whole day.
        val wall = t0 + 24 * hour
        val ignored = TrustedClockMath.read(wall, 1_000 + 10 * minute, 7, first.checkpoint, autoTimeOn = false)
        assertTrue(ignored.wallDistrusted)
        assertEquals("time goes on by the running time only", t0 + 10 * minute, ignored.trustedMs)
        val followed = TrustedClockMath.read(wall, 1_000 + 10 * minute, 7, first.checkpoint, autoTimeOn = true)
        assertFalse(followed.wallDistrusted)
        assertEquals("a network correction is believed", wall, followed.trustedMs)
    }

    @Test
    fun theIgnoredJumpStaysIgnoredOnTheNextReading() {
        val first = read(t0, 1_000, 7, null)
        val wall = t0 + 24 * hour
        val a = TrustedClockMath.read(wall, 1_000 + 10 * minute, 7, first.checkpoint, autoTimeOn = false)
        val b = TrustedClockMath.read(wall + 5 * minute, 1_000 + 15 * minute, 7, a.checkpoint, autoTimeOn = false)
        assertEquals(t0 + 15 * minute, b.trustedMs)
    }

    @Test
    fun anOrdinaryClockWithAutomaticTimeOffIsStillFollowed() {
        val first = read(t0, 1_000, 7, null)
        val second = TrustedClockMath.read(t0 + 10 * minute + 3_000, 1_000 + 10 * minute, 7, first.checkpoint, autoTimeOn = false)
        assertFalse("three seconds of drift is inside the tolerance", second.wallDistrusted)
        assertEquals(t0 + 10 * minute + 3_000, second.trustedMs)
    }

    @Test
    fun afterARebootAForwardJumpCannotBeTold() {
        val first = read(t0, 1_000, 7, null)
        val r = TrustedClockMath.read(t0 + 24 * hour, 30_000, 8, first.checkpoint, autoTimeOn = false)
        assertFalse(r.wallDistrusted)
        assertEquals(t0 + 24 * hour, r.trustedMs)
    }

    @Test
    fun aBogusHugeJumpIsNotBelievedEvenWithAutomaticTimeOn_andTheClockRecoversWhenTheWallComesBack() {
        val first = read(t0, 1_000, 7, null)
        val bogus = TrustedClockMath.read(t0 + 800 * 24 * hour, 1_000 + 10 * minute, 7, first.checkpoint, autoTimeOn = true)
        assertTrue(bogus.wallDistrusted)
        assertEquals(t0 + 10 * minute, bogus.trustedMs)
        // The wall is honest again: it agrees with the running time, so it is followed.
        val back = TrustedClockMath.read(t0 + 20 * minute, 1_000 + 20 * minute, 7, bogus.checkpoint, autoTimeOn = true)
        assertFalse(back.wallDistrusted)
        assertEquals(t0 + 20 * minute, back.trustedMs)
    }
}
