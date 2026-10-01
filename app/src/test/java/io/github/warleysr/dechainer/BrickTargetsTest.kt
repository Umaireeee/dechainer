package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.JournalLink
import io.github.warleysr.dechainer.data.LockSafety
import io.github.warleysr.dechainer.lock.LockAllow
import io.github.warleysr.dechainer.lock.PhoneFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrickTargetsTest {
    private val phone = PhoneFacts(
        launcherApps = setOf("chrome", "games", "com.android.emergency", "clock", "notes", "sms", "home"),
        protectedApps = setOf("home"),
        alarmApps = setOf("clock"),
        alwaysAllowed = emptySet(),
        emergencyApps = setOf("com.android.emergency"),
        smsApps = setOf("sms")
    )

    @Test
    fun anUrgeLockLeavesOnlyTheAlarmAndTheProtectedApps() {
        // D3: Emergency Info and SMS are blocked too, and the owner's list does not apply.
        assertEquals(
            setOf("chrome", "games", "com.android.emergency", "notes", "sms"),
            LockAllow.blocked(phone, LockAllow.URGE, ownerApps = setOf("notes"))
        )
    }

    @Test
    fun aFocusBlockAlsoLeavesEmergencyAppsAndTheOwnersList() {
        assertEquals(
            setOf("chrome", "games", "sms"),
            LockAllow.blocked(phone, LockAllow.FOCUS, ownerApps = setOf("notes"))
        )
    }

    @Test
    fun aPunishmentDayLeavesTheAlarmAndOnlyTheStudyAppsTheOwnerChose() {
        assertEquals(
            setOf("chrome", "games", "com.android.emergency", "notes", "sms"),
            LockAllow.blocked(phone, LockAllow.PUNISHMENT, ownerApps = emptySet())
        )
        assertEquals(
            setOf("chrome", "games", "com.android.emergency", "sms"),
            LockAllow.blocked(phone, LockAllow.PUNISHMENT, ownerApps = setOf("notes"))
        )
    }

    // The Urge Journal is the safety net for the worst moments (night, inside a bedtime window), so
    // no block may ever suspend it.
    @Test
    fun theUrgeJournalIsNeverBlockedByAnything() {
        val never = LockSafety.neverBlocked("io.github.warleysr.dechainer")
        assertTrue(JournalLink.PACKAGE in never)
        assertEquals("io.github.warleysr.urgejournal", JournalLink.PACKAGE)
        assertTrue("io.github.warleysr.dechainer" in never)
        assertTrue("com.android.systemui" in never)
        assertTrue("com.android.phone" in never)
    }
}
