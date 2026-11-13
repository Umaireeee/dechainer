package io.github.warleysr.dechainer

import android.Manifest
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityWatchdogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
        repeat(seconds * 2) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }

    @Test
    fun switchedOffServiceIsBroughtBackWhileTheUserWantsItOn() {
        assumeTrue(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val resolver = context.contentResolver
        try {
            AccessibilityWatchdog.setWanted(context, true)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            assertTrue("service should be off", waitFor(20) { !DechainerAccessibilityService.isRunning })

            AccessibilityWatchdog.ensureEnabled(context)
            assertTrue("watchdog should switch the service back on", waitFor(40) { DechainerAccessibilityService.isRunning })

            // Killed-but-still-listed case: take it out of the list, then let the watchdog fix it.
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            assertTrue(waitFor(20) { !DechainerAccessibilityService.isRunning })
            AccessibilityWatchdog.ensureEnabled(context)
            assertTrue(waitFor(40) { DechainerAccessibilityService.isRunning })
        } finally {
            AccessibilityWatchdog.setWanted(context, false)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        }
    }

    @Test
    fun aServiceTheUserSwitchedOffIsLeftOff() {
        assumeTrue(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val resolver = context.contentResolver
        AccessibilityWatchdog.setWanted(context, false)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertTrue(waitFor(20) { !DechainerAccessibilityService.isRunning })

        AccessibilityWatchdog.ensureEnabled(context)
        Thread.sleep(6_000)
        assertTrue("a deliberate off must stay off", !DechainerAccessibilityService.isRunning)
    }
}
