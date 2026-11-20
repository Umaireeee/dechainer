package io.github.warleysr.dechainer.screens.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.SecurityManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryGateTest {

    private lateinit var context: Context
    private lateinit var gate: RecoveryGate

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityManager.resetRateLimitForTests()
        gate = RecoveryGate(context)
        SecurityManager.endSession()
    }

    @After
    fun tearDown() {
        SecurityManager.endSession()
        SecurityManager.resetRateLimitForTests()
    }

    @Test
    fun `change executes action immediately when session is active`() {
        SecurityManager.startSession()

        var tighteningExecuted = false
        gate.change(loosening = false) {
            tighteningExecuted = true
        }

        assertTrue(tighteningExecuted)

        var looseningExecuted = false
        gate.change(loosening = true) {
            looseningExecuted = true
        }

        assertTrue(looseningExecuted)
    }

    @Test
    fun `change does not execute tightening action immediately when session is not active`() {
        SecurityManager.endSession()

        var tighteningExecuted = false
        gate.change(loosening = false) {
            tighteningExecuted = true
        }

        // Tightening without active session schedules a delayed change (5 seconds undo window)
        assertFalse(tighteningExecuted)
    }

    @Test
    fun `saveRecoveryCode stores salted hash without plaintext and verifies accurately`() {
        val secret = "ABCDEFGHIJKLMNOP"
        SecurityManager.saveRecoveryCode(context, secret)

        val prefs = context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)
        assertNull(prefs.getString("recovery_code", null))
        assertTrue(prefs.getString("recovery_code_hash", null) != null)
        assertTrue(prefs.getString("recovery_code_salt", null) != null)
        assertTrue(SecurityManager.isRecoveryCodeSet(context))

        assertFalse(SecurityManager.verifyRecoveryCode(context, "WRONGWRONGWRONGW"))
        assertTrue(SecurityManager.verifyRecoveryCode(context, secret))
    }

    @Test
    fun `legacy plaintext recovery code migrates automatically to hash`() {
        val secret = "ZYXWVUTSRQPONMLK"
        val prefs = context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("recovery_code", secret).commit()

        assertTrue(SecurityManager.isRecoveryCodeSet(context))
        assertNull(prefs.getString("recovery_code", null))
        assertTrue(prefs.getString("recovery_code_hash", null) != null)
        assertTrue(SecurityManager.verifyRecoveryCode(context, secret))
    }

    @Test
    fun `repeated wrong recovery attempts trigger rate-limit lockout`() {
        val secret = "ABCDEFGHIJKLMNOP"
        SecurityManager.saveRecoveryCode(context, secret)

        repeat(5) {
            assertFalse(SecurityManager.verifyRecoveryCode(context, "BADBADBADBADBADB"))
        }

        assertTrue(SecurityManager.getRecoveryLockoutRemainingMillis() > 0L)
        // Even the correct code is rejected during active lockout.
        assertFalse(SecurityManager.verifyRecoveryCode(context, secret))
    }

    @Test
    fun `monotonic session tracks remaining time and clears on endSession`() {
        assertFalse(SecurityManager.isSessionActive())
        SecurityManager.startSession()
        assertTrue(SecurityManager.isSessionActive())
        assertTrue(SecurityManager.getSessionRemainingMillis() > 0L)
        SecurityManager.endSession()
        assertFalse(SecurityManager.isSessionActive())
        assertTrue(SecurityManager.getSessionRemainingMillis() == 0L)
    }

    @Test
    fun `loosening requires recovery confirm, rejects bad code, executes on right code, and cancels on dismiss`() {
        val secret = "QWERTYUIOPASDFGH"
        SecurityManager.saveRecoveryCode(context, secret)
        SecurityManager.setTetrisUnlockEnabled(context, false)

        var executed = false
        var cancelled = false
        gate.change(loosening = true, onCancel = { cancelled = true }) {
            executed = true
        }

        assertFalse(executed)
        assertTrue(gate.isDialogVisible)

        assertFalse(gate.confirm("AAAAAAAAAAAAAAAA"))
        assertFalse(executed)
        assertTrue(gate.isDialogVisible)

        assertTrue(gate.confirm(secret))
        assertTrue(executed)
        assertFalse(gate.isDialogVisible)

        SecurityManager.endSession()
        var secondExecuted = false
        gate.change(loosening = true, onCancel = { cancelled = true }) {
            secondExecuted = true
        }
        assertTrue(gate.isDialogVisible)
        gate.dismiss()
        assertTrue(cancelled)
        assertFalse(secondExecuted)
        assertFalse(gate.isDialogVisible)
    }
}
