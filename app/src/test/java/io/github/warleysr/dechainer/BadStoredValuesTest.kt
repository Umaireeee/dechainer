package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.FullScreenAlerts
import io.github.warleysr.dechainer.security.SecurityManager.ImpulseLockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadStoredValuesTest {
    @Test
    fun aBadStoredImpulseModeMeansOffInsteadOfACrash() {
        assertEquals(ImpulseLockMode.OFF, ImpulseLockMode.parse(null))
        assertEquals(ImpulseLockMode.OFF, ImpulseLockMode.parse("NOT_A_MODE"))
        assertEquals(ImpulseLockMode.OFF, ImpulseLockMode.parse(""))
        assertEquals(ImpulseLockMode.HARD, ImpulseLockMode.parse("HARD"))
        assertEquals(ImpulseLockMode.NORMAL, ImpulseLockMode.parse("NORMAL"))
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
