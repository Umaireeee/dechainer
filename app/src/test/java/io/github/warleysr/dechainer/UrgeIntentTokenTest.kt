package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.urge.UrgeIntentToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeIntentTokenTest {
    @Test fun theIssuedTokenWorksOnceAndOnlyOnce() {
        val t = UrgeIntentToken()
        val a = t.issue(1_000)
        assertTrue(t.consume(a, 2_000))
        assertFalse("a replay fails", t.consume(a, 2_100))
    }

    @Test fun aGuessedOrMissingTokenFailsAndUsesUpTheRealOne() {
        val t = UrgeIntentToken()
        val a = t.issue(0)
        assertFalse(t.consume(null, 10))
        assertFalse("a wrong guess spends the token, so a probe cannot be followed by the real one", t.consume(a, 20))
        assertFalse(t.consume("deadbeef", 30))
    }

    @Test fun aStaleTokenFails() {
        val t = UrgeIntentToken(maxAgeMs = 30_000)
        val a = t.issue(0)
        assertFalse(t.consume(a, 30_001))
        val b = t.issue(100_000)
        assertTrue(t.consume(b, 130_000))
    }

    @Test fun aNewTokenReplacesTheOldOne() {
        val t = UrgeIntentToken()
        val a = t.issue(0); val b = t.issue(5)
        assertNotEquals(a, b)
        assertFalse(t.consume(a, 10))
    }

    @Test fun nothingIssuedMeansNothingPasses() {
        assertFalse(UrgeIntentToken().consume("anything", 0))
    }
}
