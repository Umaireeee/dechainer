package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.urge.BlackoutTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The blackout countdown's formatting: MM:SS under an hour, HH:MM:SS from an hour on. */
class BlackoutTimerTest {
    @Test
    fun underAnHourItShowsMinutesAndSeconds() {
        assertEquals("00:00", BlackoutTimer.format(0))
        assertEquals("00:00", BlackoutTimer.format(999))
        assertEquals("00:01", BlackoutTimer.format(1_000))
        assertEquals("00:59", BlackoutTimer.format(59_000))
        assertEquals("01:00", BlackoutTimer.format(60_000))
        assertEquals("59:59", BlackoutTimer.format(59 * 60_000L + 59_000L))
    }

    @Test
    fun anHourOrMoreItShowsHoursMinutesAndSeconds() {
        assertEquals("1:00:00", BlackoutTimer.format(3_600_000))
        assertEquals("1:01:05", BlackoutTimer.format(3_600_000 + 65_000))
        assertEquals("12:00:00", BlackoutTimer.format(12 * 3_600_000L))
    }

    @Test
    fun aTimeInThePastOrAtTheEndIsZero() {
        assertEquals("00:00", BlackoutTimer.format(-5_000))
        assertEquals("00:00", BlackoutTimer.format(Long.MIN_VALUE))
    }
}
