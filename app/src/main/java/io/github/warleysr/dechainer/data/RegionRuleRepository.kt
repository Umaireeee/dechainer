package io.github.warleysr.dechainer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.RegionRule
import org.json.JSONArray
import org.json.JSONObject

object RegionRuleRepository {
    const val PREFS_NAME = "region_rules_prefs"
    private const val KEY_RULES = "rules"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(prefs: SharedPreferences): List<RegionRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val viewId = o.optString("viewId").ifBlank { null }
                val text = o.optString("text").ifBlank { null }
                if (viewId == null && text == null) return@mapNotNull null
                RegionRule(
                    id = o.getString("id"),
                    packageName = o.getString("pkg"),
                    viewId = viewId,
                    text = text,
                    label = o.optString("label"),
                    extendDown = o.optBoolean("extendDown", false),
                    activityClass = o.optString("activity").ifBlank { null }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun load(context: Context) = load(prefs(context))

    fun save(context: Context, rules: List<RegionRule>) {
        val array = JSONArray()
        rules.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("pkg", it.packageName)
                put("viewId", it.viewId ?: "")
                put("text", it.text ?: "")
                put("label", it.label)
                put("extendDown", it.extendDown)
                put("activity", it.activityClass ?: "")
            })
        }
        prefs(context).edit { putString(KEY_RULES, array.toString()) }
    }
}
