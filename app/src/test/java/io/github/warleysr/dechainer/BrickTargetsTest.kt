package io.github.warleysr.dechainer

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
    fun aBlackoutLeavesOnlyTheProtectedAppsAndTheIncomingCallScreen() {
        // The owner's choice: only incoming calls get through. The alarm clock, Emergency Info and
        // SMS are blocked too, and the owner's list does not apply.
        assertEquals(
            setOf("chrome", "games", "com.android.emergency", "clock", "notes", "sms"),
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

    // The app itself, the system UI and the phone are never blocked by anything.
    @Test
    fun theAppTheSystemUiAndThePhoneAreNeverBlockedByAnything() {
        val never = LockSafety.neverBlocked("io.github.warleysr.dechainer")
        assertTrue("io.github.warleysr.dechainer" in never)
        assertTrue("com.android.systemui" in never)
        assertTrue("com.android.phone" in never)
    }
}
