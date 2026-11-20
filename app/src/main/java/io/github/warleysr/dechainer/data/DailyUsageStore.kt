package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Persists daily foreground + background usage counters together with the date they belong to.
 * Using a persisted date prevents stale yesterday usage from surviving across reboots/restarts and
 * refuses to clear counters when the wall clock is moved backwards.
 */
object DailyUsageStore {
    const val APP_USAGE_PREFS = "internal_usage_stats"
    const val GROUP_USAGE_PREFS = "group_usage_stats"
    private const val META_PREFS = "usage_meta"
    private const val KEY_LAST_USAGE_DATE = "last_usage_date"

    private fun appPrefs(context: Context) =
        context.getSharedPreferences(APP_USAGE_PREFS, Context.MODE_PRIVATE)

    private fun groupPrefs(context: Context) =
        context.getSharedPreferences(GROUP_USAGE_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureCurrentDate(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val meta = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val storedStr = meta.getString(KEY_LAST_USAGE_DATE, null)
        if (storedStr == null) {
            meta.edit(commit = true) { putString(KEY_LAST_USAGE_DATE, today.toString()) }
            return false
        }
        val storedDate = runCatching { LocalDate.parse(storedStr) }.getOrNull()
        if (storedDate != null && !today.isAfter(storedDate)) {
            return false
        }
        appPrefs(context).edit(commit = true) { clear() }
        groupPrefs(context).edit(commit = true) { clear() }
        meta.edit(commit = true) { putString(KEY_LAST_USAGE_DATE, today.toString()) }
        return true
    }

    @Synchronized
    fun getAppUsageMillis(context: Context, pkg: String): Long {
        ensureCurrentDate(context)
        return appPrefs(context).getLong(pkg, 0L)
    }

    @Synchronized
    fun addAppUsageMillis(context: Context, pkg: String, deltaMillis: Long) {
        ensureCurrentDate(context)
        if (deltaMillis <= 0L) return
        val prefs = appPrefs(context)
        prefs.edit { putLong(pkg, prefs.getLong(pkg, 0L) + deltaMillis) }
    }

    @Synchronized
    fun getGroupUsageMillis(context: Context, groupId: String): Long {
        ensureCurrentDate(context)
        return groupPrefs(context).getLong(groupId, 0L)
    }

    @Synchronized
    fun addGroupUsageMillis(context: Context, groupId: String, deltaMillis: Long) {
        ensureCurrentDate(context)
        if (deltaMillis <= 0L) return
        val prefs = groupPrefs(context)
        prefs.edit { putLong(groupId, prefs.getLong(groupId, 0L) + deltaMillis) }
    }
}
