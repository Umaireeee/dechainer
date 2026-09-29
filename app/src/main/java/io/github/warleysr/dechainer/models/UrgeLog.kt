package io.github.warleysr.dechainer.models

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What was behind the urge, in the person's own words. Naming it is most of the point. */
enum class UrgeTrigger { BORED, STRESSED, LONELY, TIRED, ANXIOUS, HABIT, OTHER }

/** One urge: when it hit, what set it off, and whether it was ridden out or given in to. */
data class UrgeEntry(val time: Long, val trigger: UrgeTrigger, val resisted: Boolean) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", time)
        put("k", trigger.name)
        put("r", resisted)
    }

    companion object {
        fun fromJson(o: JSONObject): UrgeEntry? {
            val trigger = runCatching { UrgeTrigger.valueOf(o.getString("k")) }.getOrNull() ?: return null
            return UrgeEntry(o.getLong("t"), trigger, o.optBoolean("r", false))
        }

        fun listFromJson(text: String?): List<UrgeEntry> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(entries: List<UrgeEntry>): String =
            JSONArray(entries.map { it.toJson() }).toString()
    }
}

/** The past seven days, boiled down to what someone can act on. */
data class UrgeWeek(
    val total: Int,
    val resisted: Int,
    val gaveIn: Int,
    /** The trigger that came up most, or null with no urges. */
    val topTrigger: UrgeTrigger?,
    /** Hour of day (0-23) when urges cluster, or null with no urges. */
    val peakHour: Int?,
    /** Whole days since the last time the person gave in, or null if they never have (in the log). */
    val daysSinceGaveIn: Int?
) {
    /** Share of urges ridden out, 0-100, or null with no urges. */
    val resistedPercent: Int? get() = if (total == 0) null else resisted * 100 / total
}

object UrgeStats {
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    fun week(entries: List<UrgeEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): UrgeWeek {
        val recent = entries.filter { it.time in (now - WEEK_MS)..now }
        val top = recent.groupingBy { it.trigger }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<UrgeTrigger, Int>> { it.value }.thenBy { -it.key.ordinal })?.key
        val peak = recent.groupingBy { Instant.ofEpochMilli(it.time).atZone(zone).hour }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })?.key
        return UrgeWeek(
            total = recent.size,
            resisted = recent.count { it.resisted },
            gaveIn = recent.count { !it.resisted },
            topTrigger = top,
            peakHour = peak,
            daysSinceGaveIn = daysSinceGaveIn(entries, now, zone)
        )
    }

    /** Calendar days from the most recent give-in to today; 0 means today. Null if none. */
    fun daysSinceGaveIn(entries: List<UrgeEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val last = entries.filter { !it.resisted && it.time <= now }.maxOfOrNull { it.time } ?: return null
        val day: LocalDate = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(day, today).toInt().coerceAtLeast(0)
    }
}
