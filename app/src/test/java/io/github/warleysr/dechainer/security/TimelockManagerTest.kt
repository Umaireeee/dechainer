package io.github.warleysr.dechainer.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelockManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("timelock_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TimelockManager.cancelAll(context)
    }

    @After
    fun tearDown() {
        TimelockManager.cancelAll(context)
        SecurityManager.cancelForcedRemoval(context)
    }

    @Test
    fun `dual-clock countdown ignores wall clock forward jump without reboot`() {
        val t0Rtc = 1_700_000_000_000L
        val t0Elapsed = 10_000L
        val initial = CountdownState.start(
            durationMs = CountdownState.SEVEN_DAYS_MS,
            claimWindowMs = CountdownState.TWENTY_FOUR_HOURS_MS,
            nowRtc = t0Rtc,
            nowElapsed = t0Elapsed,
            bootCount = 5
        )

        // User rolls wall clock forward by 10 days without rebooting: monotonic elapsed only moved 60s.
        val advanced = initial.advance(
            nowRtc = t0Rtc + 10L * 24 * 60 * 60 * 1000L,
            nowElapsed = t0Elapsed + 60_000L,
            currentBootCount = 5
        )
        val status = advanced.status() as CountdownStatus.Waiting
        assertEquals(CountdownState.SEVEN_DAYS_MS - 60_000L, status.remainingMs)
    }

    @Test
    fun `dual-clock countdown survives reboot and clamps clock jump across reboot`() {
        val t0Rtc = 1_700_000_000_000L
        val t0Elapsed = 500_000L
        val initial = CountdownState.start(
            durationMs = CountdownState.SEVEN_DAYS_MS,
            claimWindowMs = CountdownState.TWENTY_FOUR_HOURS_MS,
            nowRtc = t0Rtc,
            nowElapsed = t0Elapsed,
            bootCount = 10
        )

        // Rebooted (bootCount=11) with wall clock pushed forward by 30 days: clamped to MAX_REBOOT_ADVANCE_MS (24h).
        val afterReboot = initial.advance(
            nowRtc = t0Rtc + 30L * 24 * 60 * 60 * 1000L,
            nowElapsed = 5_000L,
            currentBootCount = 11
        )
        assertEquals(CountdownState.MAX_REBOOT_ADVANCE_MS, afterReboot.accumulatedMs)
        assertFalse(afterReboot.isReady)
    }

    @Test
    fun `dual-clock countdown handles clock rollback on reboot without negative progress`() {
        val t0Rtc = 1_700_000_000_000L
        val initial = CountdownState.start(
            durationMs = CountdownState.SEVEN_DAYS_MS,
            claimWindowMs = CountdownState.TWENTY_FOUR_HOURS_MS,
            nowRtc = t0Rtc,
            nowElapsed = 1_000_000L,
            bootCount = 10
        )
        val afterRollback = initial.advance(
            nowRtc = t0Rtc - 500_000L,
            nowElapsed = 30_000L,
            currentBootCount = 11
        )
        assertEquals(30_000L, afterRollback.accumulatedMs)
    }

    @Test
    fun `countdown transitions from waiting to ready 24h window then expires`() {
        val t0Rtc = 1_700_000_000_000L
        val t0Elapsed = 1_000L
        val res = TimelockManager.enqueue(
            context = context,
            dedupeKey = "test_key",
            title = "Unblock activity",
            description = "com.example.Test",
            action = PendingAction.RemoveBlockedActivity("com.example.Test"),
            nowRtc = t0Rtc,
            nowElapsed = t0Elapsed,
            bootCount = 1
        )
        assertTrue(res is EnqueueResult.Queued)
        val id = (res as EnqueueResult.Queued).request.id

        // Duplicate dedupeKey returns AlreadyPending
        val dup = TimelockManager.enqueue(
            context = context,
            dedupeKey = "test_key",
            title = "Unblock activity",
            description = "com.example.Test",
            action = PendingAction.RemoveBlockedActivity("com.example.Test"),
            nowRtc = t0Rtc + 1_000L,
            nowElapsed = t0Elapsed + 1_000L,
            bootCount = 1
        )
        assertTrue(dup is EnqueueResult.AlreadyPending)

        // Cannot apply while still waiting (6 days elapsed)
        val sixDays = 6L * 24 * 60 * 60 * 1000L
        assertFalse(
            TimelockManager.applyIfReady(
                context, id,
                nowRtc = t0Rtc + sixDays,
                nowElapsed = t0Elapsed + sixDays,
                bootCount = 1
            )
        )

        // At 7 days + 1 hour: ready with 23h left in claim window
        val sevenDaysPlus1h = CountdownState.SEVEN_DAYS_MS + 3_600_000L
        val syncedReady = TimelockManager.syncAll(
            context,
            nowRtc = t0Rtc + sevenDaysPlus1h,
            nowElapsed = t0Elapsed + sevenDaysPlus1h,
            bootCount = 1
        )
        assertEquals(1, syncedReady.size)
        val status = syncedReady.first().status
        assertTrue(status is CountdownStatus.Ready)
        assertEquals(CountdownState.TWENTY_FOUR_HOURS_MS - 3_600_000L, (status as CountdownStatus.Ready).claimRemainingMs)

        // At 7 days + 24 hours: claim window expired and request pruned
        val expiredAt = CountdownState.SEVEN_DAYS_MS + CountdownState.TWENTY_FOUR_HOURS_MS
        val syncedExpired = TimelockManager.syncAll(
            context,
            nowRtc = t0Rtc + expiredAt,
            nowElapsed = t0Elapsed + expiredAt,
            bootCount = 1
        )
        assertTrue(syncedExpired.isEmpty())
    }

    @Test
    fun `applyIfReady executes action and removes request when ready`() {
        context.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("blocked_activities", setOf("com.example.BadActivity")).commit()

        val t0Rtc = 1_700_000_000_000L
        val t0Elapsed = 1_000L
        val req = (TimelockManager.enqueue(
            context = context,
            dedupeKey = "unblock:BadActivity",
            title = "Unblock",
            description = "BadActivity",
            action = PendingAction.RemoveBlockedActivity("com.example.BadActivity"),
            nowRtc = t0Rtc,
            nowElapsed = t0Elapsed,
            bootCount = 1
        ) as EnqueueResult.Queued).request

        val readyElapsed = t0Elapsed + CountdownState.SEVEN_DAYS_MS + 10_000L
        val readyRtc = t0Rtc + CountdownState.SEVEN_DAYS_MS + 10_000L
        assertTrue(TimelockManager.applyIfReady(context, req.id, readyRtc, readyElapsed, 1))

        val remaining = context.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE)
            .getStringSet("blocked_activities", emptySet())!!
        assertFalse("com.example.BadActivity" in remaining)
        assertTrue(TimelockManager.syncAll(context, readyRtc, readyElapsed, 1).isEmpty())
    }

    @Test
    fun `forced removal enforces 7 days and expires after 24h claim window`() {
        val t0Rtc = 1_700_000_000_000L
        val t0Elapsed = 1_000L
        SecurityManager.startForcedRemoval(context, t0Rtc, t0Elapsed, bootCount = 2)

        val day3 = 3L * 24 * 60 * 60 * 1000L
        val waitingStatus = SecurityManager.getForcedRemovalStatus(context, t0Rtc + day3, t0Elapsed + day3, 2)
        assertTrue(waitingStatus is CountdownStatus.Waiting)
        assertEquals(CountdownState.SEVEN_DAYS_MS - day3, (waitingStatus as CountdownStatus.Waiting).remainingMs)

        val day7 = CountdownState.SEVEN_DAYS_MS + 1_000L
        val readyStatus = SecurityManager.getForcedRemovalStatus(context, t0Rtc + day7, t0Elapsed + day7, 2)
        assertTrue(readyStatus is CountdownStatus.Ready)
        assertEquals(0L, SecurityManager.getForcedRemovalRemainingTime(context, t0Rtc + day7, t0Elapsed + day7, 2))

        val day8 = CountdownState.SEVEN_DAYS_MS + CountdownState.TWENTY_FOUR_HOURS_MS + 1_000L
        assertNull(SecurityManager.getForcedRemovalStatus(context, t0Rtc + day8, t0Elapsed + day8, 2))
        assertEquals(-1L, SecurityManager.getForcedRemovalRemainingTime(context, t0Rtc + day8, t0Elapsed + day8, 2))
    }

    @Test
    fun `apk downgrade pass expires cleanly after 15 minutes`() {
        assertFalse(TimelockManager.hasActiveApkDowngradePass(context, "com.example.app", 1_000L))
        TimelockManager.grantApkDowngradePass(context, "com.example.app", 1_000L)
        assertTrue(TimelockManager.hasActiveApkDowngradePass(context, "com.example.app", 5_000L))
        assertFalse(TimelockManager.hasActiveApkDowngradePass(context, "com.example.app", 1_000L + 16L * 60 * 1000L))
        assertNotNull(TimelockManager.formatRemaining(CountdownState.SEVEN_DAYS_MS))
    }

    @Test
    fun `monotonic countdown caps total RTC reboot credit across repeated reboots`() {
        var cur = CountdownState(
            durationMs = CountdownState.SEVEN_DAYS_MS,
            claimWindowMs = CountdownState.TWENTY_FOUR_HOURS_MS,
            lastSyncRtcMs = 1_700_000_000_000L,
            lastSyncElapsedMs = 10_000L,
            lastBootCount = 1
        )
        // Simulate 30 reboots each jumping wall clock by 2 hours with almost zero uptime:
        // Total RTC credit must be capped at 24h (MAX_TOTAL_REBOOT_CREDIT_MS), not 60h!
        var rtc = 1_700_000_000_000L
        for (boot in 2..31) {
            rtc += 2L * 3_600_000L
            cur = cur.advance(nowRtc = rtc, nowElapsed = 5_000L, currentBootCount = boot)
        }
        assertTrue(cur.accumulatedMs <= 24L * 3_600_000L + 200_000L)
        val status = cur.status() as CountdownStatus.Waiting
        assertTrue(status.remainingMs >= CountdownState.SEVEN_DAYS_MS - 25L * 3_600_000L)
    }

    @Test
    fun `encodeBundle and decodeBundle preserve nested bundle arrays`() {
        val item = android.os.Bundle().apply { putString("pattern", "https://example.com") }
        val root = android.os.Bundle().apply {
            putParcelableArray("ManagedBookmarks", arrayOf(item))
        }
        val decoded = TimelockManager.decodeBundle(TimelockManager.encodeBundle(root))
        val items = decoded.getParcelableArray("ManagedBookmarks")
        assertNotNull(items)
        assertEquals(1, items!!.size)
        assertEquals("https://example.com", (items[0] as android.os.Bundle).getString("pattern"))
    }
}
