package io.github.warleysr.dechainer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.UsageAlertStage
import io.github.warleysr.dechainer.models.UsageAlertType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object UsageWarningSettings {
    const val PREFS_NAME = "usage_warning_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_STAGES = "stages"

    const val DEFAULT_ENABLED = true
    const val MIN_MINUTES_BEFORE = 0.1f
    const val MAX_MINUTES_BEFORE = 180f
    const val MAX_VIBRATION_COUNT = 10

    fun defaultStages(): List<UsageAlertStage> = listOf(
        UsageAlertStage(UUID.randomUUID().toString(), 15f, UsageAlertType.SILENT),
        UsageAlertStage(UUID.randomUUID().toString(), 5f, UsageAlertType.SOUND),
        UsageAlertStage(UUID.randomUUID().toString(), 1f, UsageAlertType.VIBRATE, vibrationCount = 1),
        UsageAlertStage(UUID.randomUUID().toString(), 0.5f, UsageAlertType.VIBRATE, vibrationCount = 3),
    )

    fun loadOrSeedStages(prefs: SharedPreferences): List<UsageAlertStage> {
        val raw = prefs.getString(KEY_STAGES, null) ?: return defaultStages().also {
            prefs.edit { putString(KEY_STAGES, encodeStages(it)) }
        }
        return decodeStages(raw)
    }

    fun decodeStages(json: String?): List<UsageAlertStage> {
        if (json == null) return defaultStages()
        if (json.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                UsageAlertStage(
                    id = obj.getString("id"),
                    minutesBefore = obj.getDouble("minutes").toFloat(),
                    type = try {
                        UsageAlertType.valueOf(obj.getString("type"))
                    } catch (_: Exception) {
                        UsageAlertType.SOUND
                    },
                    vibrationCount = obj.optInt("vibrationCount", 1)
                )
            }
        } catch (_: Exception) {
            defaultStages()
        }
    }

    fun encodeStages(stages: List<UsageAlertStage>): String {
        val array = JSONArray()
        stages.forEach { stage ->
            array.put(JSONObject().apply {
                put("id", stage.id)
                put("minutes", stage.minutesBefore.toDouble())
                put("type", stage.type.name)
                put("vibrationCount", stage.vibrationCount)
            })
        }
        return array.toString()
    }
}
