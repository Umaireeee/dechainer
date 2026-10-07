package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.security.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pattern rules: what counts as a pattern, which dots a line takes, and where a touch lands. */
class PatternTest {
    @Test fun aPatternIsFourToNineDifferentDots() {
        assertFalse(Pattern.isValid(listOf(0, 1, 2)))
        assertTrue(Pattern.isValid(listOf(0, 1, 2, 5)))
        assertTrue(Pattern.isValid((0..8).toList()))
        assertFalse(Pattern.isValid(listOf(0, 1, 1, 2)))
        assertFalse(Pattern.isValid(listOf(0, 1, 2, 9)))
        assertFalse(Pattern.isValid(listOf(0, 1, 2, -1)))
    }

    @Test fun theOrderMattersWhenEncoded() {
        assertEquals("01258", Pattern.encode(listOf(0, 1, 2, 5, 8)))
        assertFalse(Pattern.encode(listOf(0, 1, 2, 5)) == Pattern.encode(listOf(5, 2, 1, 0)))
    }

    @Test fun aLineOverADotTakesIt() {
        assertEquals(1, Pattern.between(0, 2))
        assertEquals(3, Pattern.between(0, 6))
        assertEquals(4, Pattern.between(0, 8))
        assertEquals(4, Pattern.between(2, 6))
        assertEquals(4, Pattern.between(3, 5))
        assertNull(Pattern.between(0, 1))
        assertNull(Pattern.between(0, 5))
        assertNull(Pattern.between(4, 4))
    }

    @Test fun aTouchLandsOnTheNearestDotOrNone() {
        // A 300 wide pad: cells of 100, centres at 50, 150, 250.
        assertEquals(0, Pattern.dotAt(50f, 50f, 300f))
        assertEquals(4, Pattern.dotAt(150f, 150f, 300f))
        assertEquals(8, Pattern.dotAt(260f, 245f, 300f))
        assertNull(Pattern.dotAt(100f, 100f, 300f))
        assertNull(Pattern.dotAt(-1f, 50f, 300f))
        assertNull(Pattern.dotAt(300f, 50f, 300f))
        assertNull(Pattern.dotAt(50f, 50f, 0f))
    }
}
