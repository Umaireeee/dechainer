package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.FullScreenAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadStoredValuesTest {
    @Test
    fun theFullScreenHintShowsOnlyOnAndroid14WhenNotAllowedAndNotDismissed() {
        assertTrue(FullScreenAlerts.shouldHint(sdk = 34, allowed = false, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 33, allowed = false, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 34, allowed = true, dismissed = false))
        assertFalse(FullScreenAlerts.shouldHint(sdk = 34, allowed = false, dismissed = true))
        assertTrue(FullScreenAlerts.shouldHint(sdk = 36, allowed = false, dismissed = false))
    }
}
