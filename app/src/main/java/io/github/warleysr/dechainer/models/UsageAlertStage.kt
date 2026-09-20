package io.github.warleysr.dechainer.models

enum class UsageAlertType { SILENT, SOUND, VIBRATE }

data class UsageAlertStage(
    val id: String,
    val minutesBefore: Float,
    val type: UsageAlertType = UsageAlertType.SOUND,
    val vibrationCount: Int = 1
)
