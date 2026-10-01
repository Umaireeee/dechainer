package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.LimitMath
import io.github.warleysr.dechainer.data.UsageEvt
import io.github.warleysr.dechainer.data.UsageMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLimitsTest {
    private fun fg(ev: List<UsageEvt>, pkg: String, now: Long) = UsageMath.foregroundMillisAll(ev, setOf(pkg), now).getValue(pkg)

    private val min = 60_000L
    private fun e(type: Int, pkg: String, cls: String, t: Long) = UsageEvt(type, pkg, cls, t)

    @Test
    fun foregroundTimeIsResumedToPaused() {
        val ev = listOf(e(UsageMath.RESUMED, "a", "A1", 1_000), e(UsageMath.PAUSED, "a", "A1", 61_000))
        assertEquals(60_000L, fg(ev, "a", 200_000))
    }

    @Test
    fun otherAppsDontCount() {
        val ev = listOf(
            e(UsageMath.RESUMED, "a", "A1", 0), e(UsageMath.PAUSED, "a", "A1", 10_000),
            e(UsageMath.RESUMED, "b", "B1", 10_000), e(UsageMath.PAUSED, "b", "B1", 40_000)
        )
        val all = UsageMath.foregroundMillisAll(ev, setOf("a", "b"), 100_000)
        assertEquals(10_000L, all["a"])
        assertEquals(30_000L, all["b"])
    }

    @Test
    fun theScreenGoingOffEndsTheSession() {
        // A late PAUSED after the screen went off must not add the time in between.
        val ev = listOf(
            e(UsageMath.RESUMED, "a", "A1", 0), e(UsageMath.SCREEN_OFF, "", "", 30_000),
            e(UsageMath.PAUSED, "a", "A1", 90_000)
        )
        assertEquals(30_000L, fg(ev, "a", 200_000))
    }

    @Test
    fun anAppStillInFrontCountsUntilNow() {
        val ev = listOf(e(UsageMath.RESUMED, "a", "A1", 0))
        assertEquals(45_000L, fg(ev, "a", 45_000))
    }

    @Test
    fun switchingScreensInsideAnAppKeepsCounting() {
        val ev = listOf(
            e(UsageMath.RESUMED, "a", "A1", 0), e(UsageMath.RESUMED, "a", "A2", 5_000),
            e(UsageMath.PAUSED, "a", "A1", 5_100), e(UsageMath.PAUSED, "a", "A2", 20_000)
        )
        // A1: 5.1 s, A2: 15 s. The 0.1 s overlap is counted twice; that's the accepted rounding.
        assertEquals(20_100L, fg(ev, "a", 100_000))
    }

    @Test
    fun limitsAreReachedAtTheirMinute() {
        val limits = mapOf("a" to 30, "b" to 60)
        assertTrue(LimitMath.reached(limits, mapOf("a" to 29 * min, "b" to 0L)).isEmpty())
        assertEquals(setOf("a"), LimitMath.reached(limits, mapOf("a" to 30 * min, "b" to 0L)))
    }

    @Test
    fun theNextLookIsWhenAnAppCouldFirstRunOut() {
        val limits = mapOf("a" to 30, "b" to 60)
        val fiveHours = 300 * min
        // a has 1 minute left, b has 60: look in a minute.
        assertEquals(1 * min, LimitMath.nextCheckDelay(limits, mapOf("a" to 29 * min, "b" to 0L), fiveHours))
        // a is done: only b is left to watch.
        assertEquals(60 * min, LimitMath.nextCheckDelay(limits, mapOf("a" to 30 * min, "b" to 0L), fiveHours))
        // everything done: wait for midnight, when it all resets.
        assertEquals(fiveHours, LimitMath.nextCheckDelay(limits, mapOf("a" to 30 * min, "b" to 60 * min), fiveHours))
        // midnight comes first when it's close.
        assertEquals(10 * min, LimitMath.nextCheckDelay(limits, mapOf("a" to 0L, "b" to 0L), 10 * min))
    }

    @Test
    fun theLookIsNeverMoreOftenThanEveryFifteenSeconds() {
        val limits = mapOf("a" to 30)
        assertEquals(LimitMath.MIN_DELAY_MS, LimitMath.nextCheckDelay(limits, mapOf("a" to 30 * min - 2_000L), 300 * min))
    }

    @Test
    fun anAppOutOfTimeTodayStaysOutUntilMidnight() {
        val limits = mapOf("a" to 30, "b" to 60)
        // Recorded today: still out, even if the usage log can no longer be read.
        assertEquals(setOf("a"), LimitMath.carried("2026-09-30", setOf("a"), "2026-09-30", limits))
        // A new day starts clean.
        assertTrue(LimitMath.carried("2026-09-29", setOf("a"), "2026-09-30", limits).isEmpty())
        // A limit that was removed (with the recovery code) frees its app at once.
        assertTrue(LimitMath.carried("2026-09-30", setOf("a"), "2026-09-30", mapOf("b" to 60)).isEmpty())
        // Nothing recorded yet.
        assertTrue(LimitMath.carried(null, emptySet(), "2026-09-30", limits).isEmpty())
    }
}
