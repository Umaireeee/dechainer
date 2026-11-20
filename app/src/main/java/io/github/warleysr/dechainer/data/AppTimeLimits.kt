package io.github.warleysr.dechainer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.TimeLimit
import java.time.DayOfWeek

/**
 * Per-app limits live in two preference files: the original [PREFS_NAME] keeps a plain daily
 * limit (an Int per package, as it always has), while [WEEKLY_PREFS_NAME] holds the per-weekday
 * limits of apps that use them. A package is in at most one of the two.
 */
object AppTimeLimits {
    const val PREFS_NAME = "app_limits"
    const val WEEKLY_PREFS_NAME = "app_weekly_limits"

    fun read(daily: SharedPreferences, weekly: SharedPreferences, packageName: String): TimeLimit {
        decodeWeekly(weekly.getString(packageName, null))?.let { return TimeLimit.perDay(it) }
        return TimeLimit.daily(runCatching { daily.getInt(packageName, 0) }.getOrDefault(0))
    }

    fun readAll(daily: SharedPreferences, weekly: SharedPreferences): Map<String, TimeLimit> {
        val result = mutableMapOf<String, TimeLimit>()
        daily.all.forEach { (pkg, minutes) ->
            (minutes as? Int)?.takeIf { it > 0 }?.let { result[pkg] = TimeLimit.daily(it) }
        }
        weekly.all.forEach { (pkg, value) ->
            decodeWeekly(value as? String)?.let { result[pkg] = TimeLimit.perDay(it) }
        }
        return result.filterValues { it.isSet }
    }

    fun write(daily: SharedPreferences, weekly: SharedPreferences, packageName: String, limit: TimeLimit) {
        val perDay = limit.weeklyMinutes?.takeIf { limit.isSet }
        // Write destination first so OnSharedPreferenceChangeListener never observes a 0-limit window.
        if (perDay != null) {
            weekly.edit { putString(packageName, encodeWeekly(perDay)) }
            daily.edit { remove(packageName) }
        } else {
            daily.edit { if (limit.dailyMinutes > 0) putInt(packageName, limit.dailyMinutes) else remove(packageName) }
            weekly.edit { remove(packageName) }
        }
    }

    fun encodeWeekly(minutes: List<Int>): String = minutes.joinToString(",")

    fun decodeWeekly(value: String?): List<Int>? {
        if (value.isNullOrEmpty()) return null
        val minutes = value.split(",").map { it.trim().toIntOrNull() ?: return null }
        return minutes.takeIf { it.size == DayOfWeek.entries.size && it.all { m -> m >= 0 } }
    }
}
