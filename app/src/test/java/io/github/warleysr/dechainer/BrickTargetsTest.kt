package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.LockSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrickTargetsTest {
    // ---- #8: emergency apps stay open in a brick ----

    @Test
    fun aBrickNeverSuspendsEmergencyAlarmOrAllowedApps() {
        val launcher = setOf("chrome", "games", "com.android.emergency", "clock", "notes", "com.google.android.apps.safetyhub")
        val out = LockSafety.brickTargets(launcher, protectedPkgs = setOf("home"), alarms = setOf("clock"), allowed = setOf("notes"))
        assertEquals(setOf("chrome", "games"), out)
        assertNotEquals(launcher, out)
    }
}
