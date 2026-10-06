package io.github.warleysr.dechainer

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.data.TimeLimits
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsagePersistenceTest {
    private lateinit var ctx: Context
    private val now = LocalDate.of(2026, 10, 6).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("app_time_limits", Context.MODE_PRIVATE).edit().clear().putInt("app", 30).commit()
        ctx.getSharedPreferences("app_time_limits_reached", Context.MODE_PRIVATE).edit().clear()
            .putString("day", "2026-10-06").putStringSet("apps", setOf("app")).commit()
        shadowOf(ctx.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName, AppOpsManager.MODE_ALLOWED)
    }

    @Test fun thrownUsageQueryPreservesReachedAppsAndSchedulesRetry() {
        val status = TimeLimits.evaluate(ctx, now) { _, _, _ -> error("usage database unavailable") }
        assertEquals(setOf("app"), status.reached)
        assertNotNull(status.nextCheckDelayMs)
        assertEquals(setOf("app"), ctx.getSharedPreferences("app_time_limits_reached", Context.MODE_PRIVATE).getStringSet("apps", emptySet()))
    }

    @Test fun validEmptyMeasurementCanReleaseRaisedLimit() {
        assertTrue(TimeLimits.evaluate(ctx, now) { _, _, _ -> emptyMap() }.reached.isEmpty())
    }

    @Test fun permissionLossRetainsTodayButNotYesterdaysRecord() {
        shadowOf(ctx.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName, AppOpsManager.MODE_IGNORED)
        assertEquals(setOf("app"), TimeLimits.evaluate(ctx, now).reached)
        assertTrue(TimeLimits.evaluate(ctx, now + 24 * 3_600_000L).reached.isEmpty())
    }
}
