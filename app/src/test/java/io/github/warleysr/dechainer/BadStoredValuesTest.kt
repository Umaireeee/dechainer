package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.FullScreenAlerts
import io.github.warleysr.dechainer.security.SecurityManager.EntryChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadStoredValuesTest {
    @Test
    fun aBadStoredEntryChallengeMeansOffInsteadOfACrash() {
        assertEquals(EntryChallenge.OFF, EntryChallenge.parse(null))
        assertEquals(EntryChallenge.OFF, EntryChallenge.parse("NOT_A_MODE"))
        assertEquals(EntryChallenge.OFF, EntryChallenge.parse(""))
        assertEquals(EntryChallenge.HARD, EntryChallenge.parse("HARD"))
        assertEquals(EntryChallenge.NORMAL, EntryChallenge.parse("NORMAL"))
    }

    @Test
    fun theFullScreenHintShowsOnlyOnAndroid14WhenNotAllowedAndNotDismissed() {
        assertTrue(FullScreenAlerts.shouldHint(sdk = 34, allowed = false, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 33, allowed = false, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 34, allowed = true, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 34, allowed = false, dismissed = true))
        assertTrue(FullScreenAlerts.shouldHint(sdk = 36, allowed = false, dismissed = false))
    }
}
