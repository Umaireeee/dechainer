package io.github.warleysr.dechainer.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class NotificationGuardTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
        NotificationGuard.resetForTests()
        NotificationGuard.setListenerWanted(ctx, false)
    }

    @Test
    fun pureRuleEnforcesDeviceOwnerApiLevelAndCooldown() {
        assertTrue(
            NotificationGuardRule.shouldRestore(
                sdk = 33,
                deviceOwner = true,
                grantState = 0,
                notificationsEnabled = false,
                lastAttemptElapsedMs = null,
                nowElapsedMs = 1_000L
            )
        )
        assertFalse(
            NotificationGuardRule.shouldRestore(
                sdk = 32,
                deviceOwner = true,
                grantState = 0,
                notificationsEnabled = false,
                lastAttemptElapsedMs = null,
                nowElapsedMs = 1_000L
            )
        )
        assertFalse(
            NotificationGuardRule.shouldRestore(
                sdk = 34,
                deviceOwner = true,
                grantState = NotificationGuardRule.GRANT_STATE_GRANTED,
                notificationsEnabled = true,
                lastAttemptElapsedMs = null,
                nowElapsedMs = 1_000L
            )
        )
        assertFalse(
            NotificationGuardRule.shouldRestore(
                sdk = 34,
                deviceOwner = true,
                grantState = 0,
                notificationsEnabled = false,
                lastAttemptElapsedMs = 10_000L,
                nowElapsedMs = 11_000L,
                cooldownMs = 2_000L
            )
        )
    }

    @Test
    fun enforceRestoresPostNotificationsWhenDeviceOwner() {
        shadowOf(dpm).setDeviceOwner(admin)
        dpm.setPermissionGrantState(
            admin,
            ctx.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        )

        NotificationGuard.enforcePostNotifications(ctx)

        assertEquals(
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            dpm.getPermissionGrantState(admin, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun enforceDoesNothingWithoutDeviceOwner() {
        NotificationGuard.enforcePostNotifications(ctx)
        shadowOf(dpm).setDeviceOwner(admin)
        assertEquals(
            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
            dpm.getPermissionGrantState(admin, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun blockBroadcastFilterIgnoresUnblockAndHandlesBlock() {
        val blocked = Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, true)
        val unblocked = Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)

        assertTrue(NotificationGuard.shouldHandleBlockBroadcast(blocked))
        assertFalse(NotificationGuard.shouldHandleBlockBroadcast(unblocked))
    }

    @Test
    fun accessibilityWatchdogSchedulesNoPeriodicWakeups() {
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        AccessibilityWatchdog.setWanted(ctx, true)
        AccessibilityWatchdog.schedulePeriodic(ctx)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
}
