package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.JournalLink
import io.github.warleysr.dechainer.data.UrgeActions
import io.github.warleysr.dechainer.data.UrgeActions.Kind
import io.github.warleysr.dechainer.focus.FocusLogMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun focusDurationsAreCappedAndTheUrgeLockHasOneLength() {
        assertEquals(25, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, -5))
        assertEquals(120, UrgeActions.clampMinutes(Kind.FOCUS_BLOCK, 100_000))
        // Whatever the journal asks for, an urge lock is the fixed ten minutes (5.2).
        listOf(Kind.IMPULSE_BLOCK, Kind.RIDE_LOCK).forEach { kind ->
            listOf(0, 10, 60, 100_000).forEach { asked ->
                assertEquals(10, UrgeActions.clampMinutes(kind, asked))
            }
        }
        assertEquals(Rules.URGE_LOCK_MS, 10 * 60_000L)
    }

    @Test
    fun theOldRideKindStillParsesSoTheJournalKeepsWorking() {
        assertEquals(Kind.RIDE_LOCK, UrgeActions.parseKind("RIDE_LOCK"))
    }

    // The journal's "first move" rides along with a focus block as the intention of its first session.
    @Test
    fun aFirstMoveArrivesAsAShortSingleLineIntention() {
        assertEquals("intention", UrgeActions.EXTRA_INTENTION)
        assertEquals("FAR ch. 6 Q1 to 10", FocusLogMath.cleanIntention("FAR ch. 6\nQ1 to 10"))
        assertEquals(80, FocusLogMath.cleanIntention("x".repeat(200))!!.length)
        assertNull(FocusLogMath.cleanIntention("  \n "))
        assertNull(FocusLogMath.cleanIntention(null))
    }

    @Test
    fun theJournalIsReachedWithTheRideExtra() {
        assertEquals("io.github.warleysr.urgejournal", JournalLink.PACKAGE)
        assertEquals("action", JournalLink.RIDE_EXTRA)
        assertEquals("ride", JournalLink.RIDE_VALUE)
    }
}
