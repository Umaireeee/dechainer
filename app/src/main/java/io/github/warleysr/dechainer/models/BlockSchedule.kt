package io.github.warleysr.dechainer.models

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A recurring block window. While the window is open, [packages] are suspended, the Android user
 * [restrictions] ("services") are applied and [websites] are added to the browser URL blocklist.
 * When it closes, everything the schedule applied is released again, and the cycle repeats on
 * every selected day until the schedule is disabled or deleted.
 *
 * Times are minutes after midnight. When [endMinute] <= [startMinute] the window crosses midnight
 * (e.g. 22:00 -> 06:00) and belongs to the day it *starts* on; equal values mean a full 24h window.
 */
data class BlockSchedule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 6 * 60,
    val packages: Set<String> = emptySet(),
    val restrictions: Set<String> = emptySet(),
    val websites: Set<String> = emptySet(),
    /** While the window is open the schedule can't be edited, disabled or deleted — not even with the recovery code. */
    val lockWhileActive: Boolean = false,
    /**
     * Study mode: [packages] are the only apps allowed during the window — everything else with an
     * icon is suspended (see ScheduleEnforcer).
     */
    val allowOnly: Boolean = false
) {
    val crossesMidnight: Boolean get() = endMinute <= startMinute

    /** Length of one window in minutes (1440 when start == end). */
    val windowMinutes: Int
        get() = if (endMinute > startMinute) endMinute - startMinute else endMinute + 1440 - startMinute

    fun isActiveAt(now: LocalDateTime): Boolean {
        if (!enabled || days.isEmpty()) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        val yesterday = today.minus(1)

        return if (!crossesMidnight) {
            today in days && minute >= startMinute && minute < endMinute
        } else {
            (today in days && minute >= startMinute) || (yesterday in days && minute < endMinute)
        }
    }

    /** Wall-clock end of the window that is open at [now], or null if it isn't open. */
    fun currentWindowEnd(now: LocalDateTime): LocalDateTime? {
        if (!isActiveAt(now)) return null
        val minute = now.hour * 60 + now.minute
        val endTime = LocalTime.of(endMinute / 60, endMinute % 60)
        val endDate = if (crossesMidnight && minute >= startMinute) now.toLocalDate().plusDays(1)
        else now.toLocalDate()
        return LocalDateTime.of(endDate, endTime)
    }

    /** Every start/end instant of this schedule from [from] onward, over the next [daysAhead] days. */
    fun boundariesAfter(from: ZonedDateTime, daysAhead: Int = 8): List<ZonedDateTime> {
        if (!enabled || days.isEmpty()) return emptyList()
        val zone: ZoneId = from.zone
        val result = mutableListOf<ZonedDateTime>()
        // Start one day back so the end of a window that began yesterday is included.
        var date: LocalDate = from.toLocalDate().minusDays(1)
        repeat(daysAhead + 1) {
            if (date.dayOfWeek in days) {
                val start = LocalDateTime.of(date, LocalTime.of(startMinute / 60, startMinute % 60)).atZone(zone)
                val endDate = if (crossesMidnight) date.plusDays(1) else date
                val end = LocalDateTime.of(endDate, LocalTime.of(endMinute / 60, endMinute % 60)).atZone(zone)
                if (start.isAfter(from)) result.add(start)
                if (end.isAfter(from)) result.add(end)
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** Next time this schedule's window opens after [from], or null if it never will. */
    fun nextStartAfter(from: ZonedDateTime): ZonedDateTime? {
        if (!enabled || days.isEmpty()) return null
        var date = from.toLocalDate()
        repeat(8) {
            if (date.dayOfWeek in days) {
                val start = LocalDateTime.of(date, LocalTime.of(startMinute / 60, startMinute % 60))
                    .atZone(from.zone)
                if (start.isAfter(from)) return start
            }
            date = date.plusDays(1)
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("days", JSONArray(days.map { it.value }))
        put("start", startMinute)
        put("end", endMinute)
        put("packages", JSONArray(packages.toList()))
        put("restrictions", JSONArray(restrictions.toList()))
        put("websites", JSONArray(websites.toList()))
        put("lockWhileActive", lockWhileActive)
        put("allowOnly", allowOnly)
    }

    companion object {
        fun fromJson(obj: JSONObject): BlockSchedule = BlockSchedule(
            id = obj.getString("id"),
            name = obj.optString("name"),
            enabled = obj.optBoolean("enabled", true),
            days = obj.optJSONArray("days").toIntList()
                .mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }.toSet(),
            startMinute = obj.optInt("start", 22 * 60).coerceIn(0, 1439),
            endMinute = obj.optInt("end", 6 * 60).coerceIn(0, 1439),
            packages = obj.optJSONArray("packages").toStringList().toSet(),
            restrictions = obj.optJSONArray("restrictions").toStringList().toSet(),
            websites = obj.optJSONArray("websites").toStringList().toSet(),
            lockWhileActive = obj.optBoolean("lockWhileActive", false),
            allowOnly = obj.optBoolean("allowOnly", false)
        )

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        private fun JSONArray?.toIntList(): List<Int> =
            if (this == null) emptyList() else (0 until length()).map { getInt(it) }

        /** Normalises what the user typed into a bare host usable in Chrome's URLBlocklist. */
        fun normaliseWebsite(input: String): String? {
            val host = input.trim().lowercase()
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('/')
                .substringBefore('?')
                .trim()
            return host.takeIf { it.isNotEmpty() && it.contains('.') && !it.contains(' ') }
        }

        fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}
