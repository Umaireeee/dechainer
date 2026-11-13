package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.models.DowntimeRule
import org.json.JSONArray
import org.json.JSONObject

/** Stores the downtime rules, and which apps downtime itself has suspended. */
object DowntimeRepository {
    private const val PREFS_NAME = "downtime"
    private const val KEY_RULES = "rules"
    private const val KEY_HELD = "held"

    private fun prefs(context: Context = DechainerApplication.getInstance()) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRules(context: Context = DechainerApplication.getInstance()): List<DowntimeRule> =
        decode(prefs(context).getString(KEY_RULES, null))

    /** Saves the rules and applies them right away: a window that is running now takes effect now. */
    fun save(rules: List<DowntimeRule>, context: Context = DechainerApplication.getInstance()) {
        prefs(context).edit { putString(KEY_RULES, encode(rules)) }
        DowntimeManager.sync(context)
    }

    /** Apps that downtime suspended and has not given back yet. */
    fun getHeld(context: Context): Set<String> = prefs(context).getStringSet(KEY_HELD, emptySet()) ?: emptySet()

    fun setHeld(context: Context, held: Set<String>) {
        // A copy: the set SharedPreferences hands out must never be modified or reused.
        prefs(context).edit { putStringSet(KEY_HELD, HashSet(held)) }
    }

    internal fun encode(rules: List<DowntimeRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("packages", JSONArray(rule.packageNames.toList()))
                put("groups", JSONArray(rule.groupIds.toList()))
                put("windows", AppTimeWindows.toJson(rule.windows))
            })
        }
        return array.toString()
    }

    internal fun decode(json: String?): List<DowntimeRule> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                fun strings(key: String) = obj.optJSONArray(key)
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: emptySet()
                DowntimeRule(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    packageNames = strings("packages"),
                    groupIds = strings("groups"),
                    windows = AppTimeWindows.fromJson(obj.getJSONArray("windows"))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
