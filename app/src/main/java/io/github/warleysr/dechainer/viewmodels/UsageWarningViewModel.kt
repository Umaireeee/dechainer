package io.github.warleysr.dechainer.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.UsageWarningSettings
import io.github.warleysr.dechainer.models.UsageAlertStage
import io.github.warleysr.dechainer.models.UsageAlertType
import java.util.UUID

class UsageWarningViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()
    private val prefs = context.getSharedPreferences(UsageWarningSettings.PREFS_NAME, Context.MODE_PRIVATE)

    var enabled by mutableStateOf(
        prefs.getBoolean(UsageWarningSettings.KEY_ENABLED, UsageWarningSettings.DEFAULT_ENABLED)
    )
        private set

    var stages by mutableStateOf(UsageWarningSettings.loadOrSeedStages(prefs))
        private set

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit { putBoolean(UsageWarningSettings.KEY_ENABLED, value) }
    }

    fun addStage() {
        val minutes = ((stages.minOfOrNull { it.minutesBefore } ?: 10f) / 2).coerceAtLeast(UsageWarningSettings.MIN_MINUTES_BEFORE)
        persist(stages + UsageAlertStage(UUID.randomUUID().toString(), minutes, UsageAlertType.SOUND))
    }

    fun removeStage(id: String) {
        persist(stages.filterNot { it.id == id })
    }

    fun updateStageMinutes(id: String, minutes: Float) {
        persist(stages.map { if (it.id == id) it.copy(minutesBefore = minutes) else it })
    }

    fun updateStageType(id: String, type: UsageAlertType) {
        persist(stages.map { if (it.id == id) it.copy(type = type) else it })
    }

    fun updateStageVibrationCount(id: String, count: Int) {
        persist(stages.map { if (it.id == id) it.copy(vibrationCount = count) else it })
    }

    private fun persist(newStages: List<UsageAlertStage>) {
        stages = newStages
        prefs.edit { putString(UsageWarningSettings.KEY_STAGES, UsageWarningSettings.encodeStages(newStages)) }
    }
}
