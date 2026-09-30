package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.ownedAfterBlock
import io.github.warleysr.dechainer.data.ownedAfterRelease
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnershipTest {
    @Test
    fun anAppAndroidRefusedToSuspendIsNotOwned() {
        val owned = ownedAfterBlock(setOf("x"), setOf("a", "b"), failed = setOf("b"))
        assertEquals(setOf("x", "a"), owned)
    }

    @Test
    fun anAppAndroidRefusedToReleaseStaysOwnedSoItIsRetried() {
        val owned = ownedAfterRelease(setOf("a", "b", "c"), toRelease = setOf("a", "b"), notReleased = setOf("b"))
        assertEquals(setOf("b", "c"), owned)
        // Nothing failed: everything asked for is given up.
        assertEquals(setOf("c"), ownedAfterRelease(setOf("a", "b", "c"), setOf("a", "b"), emptySet()))
    }
}
