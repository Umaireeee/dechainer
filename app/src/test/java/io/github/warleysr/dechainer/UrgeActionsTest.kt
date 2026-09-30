package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.UrgeActions
import io.github.warleysr.dechainer.data.UrgeActions.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeActionsTest {

    @Test
    fun unknownKindsAreIgnored() {
        assertNull(UrgeActions.parseKind(null))
        assertNull(UrgeActions.parseKind("UNBLOCK_EVERYTHING"))
        assertEquals(Kind.IMPULSE_BLOCK, UrgeActions.parseKind("IMPULSE_BLOCK"))
        assertEquals(Kind.FOCUS_BLOCK, UrgeActions.parseKind("FOCUS_BLOCK"))
    }

    @Test
    fun durationsAreCapped() {
        assertEquals(15, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 0))
        assertEquals(360, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 100_000))
        assertEquals(60, UrgeActions.clampMinutes(Kind.IMPULSE_BLOCK, 60))
        assertEquals(25, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, -5))
        assertEquals(120, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, 100_000))
    }

    @Test
    fun aRequestNeverShortensARunningImpulseBlock() {
        val thirtyMinLeft = 30 * 60_000L
        assertFalse(UrgeActions.shouldStartImpulse(thirtyMinLeft, 15))
        assertFalse(UrgeActions.shouldStartImpulse(thirtyMinLeft, 30))
        assertTrue(UrgeActions.shouldStartImpulse(thirtyMinLeft, 60))
        assertTrue(UrgeActions.shouldStartImpulse(0L, 15))
    }
}
