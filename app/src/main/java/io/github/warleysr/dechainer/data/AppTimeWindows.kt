package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.models.TimeWindow
import org.json.JSONArray
import org.json.JSONObject

object AppTimeWindows {
    const val PREFS_NAME = "app_time_windows"

    fun decode(json: String?): List<TimeWindow> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            fromJson(JSONArray(json))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(windows: List<TimeWindow>): String = toJson(windows).toString()

    /**
     * Windows saved before days and the on/off switch existed only have "s" and "e": they
     * apply every day and are enabled.
     */
    fun fromJson(array: JSONArray): List<TimeWindow> = (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val days = obj.optJSONArray("d")
            ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..7 }.toSet() }
            ?: TimeWindow.ALL_DAYS
        TimeWindow(obj.getInt("s"), obj.getInt("e"), days, obj.optBoolean("on", true))
    }

    fun toJson(windows: List<TimeWindow>): JSONArray {
        val array = JSONArray()
        windows.forEach { window ->
            array.put(JSONObject().apply {
                put("s", window.startMinute)
                put("e", window.endMinute)
                if (!window.isEveryDay) put("d", JSONArray(window.days.sorted()))
                if (!window.enabled) put("on", false)
            })
        }
        return array
    }
}
