package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.AppLockRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the app lock: what a PIN or pattern may be, how guesses are slowed, when it asks again. */
class AppLockRulesTest {

    @Test
    fun aPinIsFourToTwelveDigits() {
        assertTrue(AppLockRules.validPin("4821"))
        assertTrue(AppLockRules.validPin("482159374026"))
        assertFalse(AppLockRules.validPin("482"))
        assertFalse(AppLockRules.validPin("4821593740261"))
        assertFalse(AppLockRules.validPin("48a1"))
        assertFalse(AppLockRules.validPin(""))
    }

    @Test
    fun easyPinsAreWeak() {
        for (weak in listOf("0000", "1111", "1234", "4321", "2345", "9876", "123456")) assertTrue(weak, AppLockRules.weakPin(weak))
        for (fine in listOf("4821", "1357", "2580", "1212", "7391")) assertFalse(fine, AppLockRules.weakPin(fine))
    }

    @Test
    fun aPatternNeedsFourDifferentDotsOfTheGrid() {
        assertTrue(AppLockRules.validPattern(listOf(0, 1, 4, 8)))
        assertFalse(AppLockRules.validPattern(listOf(0, 1, 4)))
        assertFalse(AppLockRules.validPattern(listOf(0, 1, 1, 4)))
        assertFalse(AppLockRules.validPattern(listOf(0, 1, 4, 9)))
        assertFalse(AppLockRules.validPattern(listOf(0, 1, 4, -1)))
    }

    @Test
    fun aDotBetweenTwoOthersCountsWhenCrossed() {
        assertEquals(1, AppLockRules.skippedDot(0, 2))
        assertEquals(4, AppLockRules.skippedDot(0, 8))
        assertEquals(4, AppLockRules.skippedDot(2, 6))
        assertEquals(3, AppLockRules.skippedDot(0, 6))
        assertEquals(5, AppLockRules.skippedDot(8, 2))
        assertNull("neighbours cross nothing", AppLockRules.skippedDot(0, 1))
        assertNull("a knight's move crosses nothing", AppLockRules.skippedDot(0, 5))
        assertNull(AppLockRules.skippedDot(0, 7))
        assertNull(AppLockRules.skippedDot(1, 5))
    }

    @Test
    fun drawingAcrossAnUntouchedDotAddsIt_butNotOneAlreadyOnThePath() {
        assertEquals(listOf(0, 1, 2), AppLockRules.extend(listOf(0), 2))
        assertEquals(listOf(1, 0, 2), AppLockRules.extend(listOf(1, 0), 2))
        assertEquals("a dot already drawn is not drawn twice", listOf(0, 1), AppLockRules.extend(listOf(0, 1), 0))
        assertEquals(listOf(4), AppLockRules.extend(emptyList(), 4))
    }

    @Test
    fun theSameDrawingAlwaysGivesTheSameSecret() {
        val a = listOf(0, 2, 8).fold(emptyList<Int>()) { p, d -> AppLockRules.extend(p, d) }
        val b = listOf(0, 1, 2, 5, 8).fold(emptyList<Int>()) { p, d -> AppLockRules.extend(p, d) }
        assertEquals("0 to 2 across 1 and 2 to 8 across 5 is the same pattern as touching them", b, a)
        assertEquals("01258", AppLockRules.patternSecret(a))
    }

    @Test
    fun wrongGuessesAreFreeAtFirstThenSlowedDown() {
        for (n in 0..4) assertEquals(0L, AppLockRules.delayAfter(n))
        assertEquals(30_000L, AppLockRules.delayAfter(5))
        assertEquals(60_000L, AppLockRules.delayAfter(6))
        assertEquals(5 * 60_000L, AppLockRules.delayAfter(7))
        assertEquals(15 * 60_000L, AppLockRules.delayAfter(8))
        assertEquals(60 * 60_000L, AppLockRules.delayAfter(9))
        assertEquals(60 * 60_000L, AppLockRules.delayAfter(500))
    }

    @Test
    fun theWaitCountsDownFromTheLastFailure() {
        assertEquals(30_000L, AppLockRules.waitRemaining(5, lastFailureAt = 1_000, now = 1_000))
        assertEquals(10_000L, AppLockRules.waitRemaining(5, 1_000, 21_000))
        assertEquals(0L, AppLockRules.waitRemaining(5, 1_000, 31_000))
        assertEquals(0L, AppLockRules.waitRemaining(5, 1_000, 99_000_000))
        assertEquals("no wait before the limit", 0L, AppLockRules.waitRemaining(3, 1_000, 1_000))
    }

    @Test
    fun aClockSetBackNeverShortensTheWait() {
        assertEquals(30_000L, AppLockRules.waitRemaining(5, lastFailureAt = 1_000_000, now = 10))
    }

    @Test
    fun triesLeftBeforeTheWaitingStarts() {
        assertEquals(4, AppLockRules.freeTriesLeft(0))
        assertEquals(1, AppLockRules.freeTriesLeft(3))
        assertEquals(0, AppLockRules.freeTriesLeft(4))
        assertEquals(0, AppLockRules.freeTriesLeft(9))
    }

    @Test
    fun theAppAsksAgainAfterBeingAwayHalfAMinute() {
        assertFalse(AppLockRules.shouldRelock(29_999))
        assertTrue(AppLockRules.shouldRelock(30_000))
        assertTrue(AppLockRules.shouldRelock(10 * 60_000))
    }
}
