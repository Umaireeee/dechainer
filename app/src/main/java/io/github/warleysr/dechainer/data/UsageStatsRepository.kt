package io.github.warleysr.dechainer.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import io.github.warleysr.dechainer.utils.ShizukuRunner
import java.util.concurrent.TimeUnit

/**
 * Reads how long each app was in the foreground over the last week, to rank the Apps screen.
 * Read once when the screen opens; nothing runs in the background.
 */
object UsageStatsRepository {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground milliseconds per package over the last 7 days, or null without usage access. */
    fun weeklyForegroundMillis(context: Context): Map<String, Long>? {
        if (!hasAccess(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return try {
            manager.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(7), now)
                .mapValues { it.value.totalTimeInForeground }
        } catch (_: Exception) {
            null
        }
    }

    /** Grants usage access through Shizuku when it is running, so no trip to Settings is needed. */
    fun tryGrantViaShizuku(context: Context): Boolean {
        if (hasAccess(context)) return true
        ShizukuRunner.exec("appops", "set", context.packageName, "GET_USAGE_STATS", "allow")
        return hasAccess(context)
    }
}
