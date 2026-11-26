package io.github.warleysr.dechainer.screens.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.EnqueueResult
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.security.TimelockManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelockGateTest {

    private lateinit var context: Context
    private lateinit var gate: TimelockGate

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TimelockManager.cancelAll(context)
        gate = TimelockGate(context)
    }

    @After
    fun tearDown() {
        TimelockManager.cancelAll(context)
    }

    @Test
    fun `tightening schedules pending change without opening timelock dialog`() {
        var tightened = false
        gate.change(
            loosening = false,
            title = "Unused",
            description = "Unused",
            dedupeKey = "tighten_key",
            pendingAction = PendingAction.UnsuspendApp("com.example.app")
        ) {
            tightened = true
        }

        assertFalse(gate.isDialogVisible)
        // PendingChange waits 5 seconds before executing so user can undo
        assertFalse(tightened)
        assertTrue(TimelockManager.requests.value.isEmpty())
    }

    @Test
    fun `loosening opens timelock dialog, queues on confirm, and invokes onCancel`() {
        var tightened = false
        var reverted = false
        gate.change(
            loosening = true,
            title = "Unsuspend Instagram",
            description = "com.instagram.android",
            dedupeKey = "unsuspend:com.instagram.android",
            pendingAction = PendingAction.UnsuspendApp("com.instagram.android"),
            onCancel = { reverted = true }
        ) {
            tightened = true
        }

        assertTrue(gate.isDialogVisible)
        assertFalse(tightened)

        val result = gate.confirmQueue()
        assertTrue(result is EnqueueResult.Queued)
        assertFalse(gate.isDialogVisible)
        assertTrue(reverted)
        assertFalse(tightened)
        assertEquals(1, TimelockManager.requests.value.size)
    }

    @Test
    fun `dismissing timelock dialog leaves queue unchanged and invokes onCancel`() {
        var cancelled = false
        gate.requestUnlock(
            title = "Remove Device Owner",
            description = "Remove",
            dedupeKey = "remove_do",
            pendingAction = PendingAction.RemoveDeviceOwner,
            onCancel = { cancelled = true }
        )

        assertTrue(gate.isDialogVisible)
        gate.dismiss()
        assertFalse(gate.isDialogVisible)
        assertTrue(cancelled)
        assertTrue(TimelockManager.requests.value.isEmpty())
    }
}
