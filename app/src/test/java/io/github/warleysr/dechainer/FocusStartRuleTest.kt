package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.FocusStart
import io.github.warleysr.dechainer.lock.FocusStartRule
import io.github.warleysr.dechainer.lock.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Test

/** Blueprint 5.3, last rule, and D18: what a scheduled FOCUS start does around other locks. */
class FocusStartRuleTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val windowEnd = now + 90 * minute

    private fun decide(rest: Boolean = false, urgeEnds: Long = 0L, at: Long = now, end: Long = windowEnd) =
        FocusStartRule.decide(at, end, rest, urgeEnds)

    @Test
    fun withNothingElseRunningItStartsNow() {
        assertEquals(FocusStart.Now, decide())
    }

    @Test
    fun aStartOnARestDayIsSkippedBecauseARestDaySkipsScheduledFocus() {
        assertEquals(FocusStart.Skip(SkipReason.REST_DAY), decide(rest = true))
    }

    @Test
    fun aStartDuringAnUrgeLockWaitsForItToEndWhileTheWindowIsStillOpen() {
        assertEquals(FocusStart.At(now + 10 * minute), decide(urgeEnds = now + 10 * minute))
    }

    @Test
    fun aStartDuringAnUrgeLockThatOutlastsTheWindowIsSkipped() {
        assertEquals(FocusStart.Skip(SkipReason.URGE_LOCK_OUTLASTS_WINDOW), decide(urgeEnds = windowEnd + 1))
        assertEquals("ending exactly with the window leaves nothing", FocusStart.Skip(SkipReason.URGE_LOCK_OUTLASTS_WINDOW), decide(urgeEnds = windowEnd))
        assertEquals(FocusStart.At(windowEnd - 1), decide(urgeEnds = windowEnd - 1))
    }

    @Test
    fun anUrgeLockThatHasAlreadyEndedChangesNothing() {
        assertEquals(FocusStart.Now, decide(urgeEnds = now))
        assertEquals(FocusStart.Now, decide(urgeEnds = now - minute))
    }

    @Test
    fun aWindowThatHasAlreadyEndedIsNeverStarted() {
        assertEquals(FocusStart.Skip(SkipReason.WINDOW_OVER), decide(end = now))
        assertEquals(FocusStart.Skip(SkipReason.WINDOW_OVER), decide(end = now - minute))
    }

    @Test
    fun aRestDayBeatsAnUrgeLockWhenBothApply() {
        assertEquals(FocusStart.Skip(SkipReason.REST_DAY), decide(rest = true, urgeEnds = now + minute))
    }
}
