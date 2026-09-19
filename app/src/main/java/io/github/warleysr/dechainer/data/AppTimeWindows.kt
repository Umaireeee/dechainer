package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.models.TimeWindow
import org.json.JSONArray
import org.json.JSONObject

object AppTimeWindows {
    const val PREFS_NAME = "app_time_windows"

    fun decode(json: String?): List<TimeWindow> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TimeWindow(obj.getInt("s"), obj.getInt("e"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(windows: List<TimeWindow>): String {
        val array = JSONArray()
        windows.forEach { window ->
            array.put(JSONObject().apply {
                put("s", window.startMinute)
                put("e", window.endMinute)
            })
        }
        return array.toString()
    }
}
