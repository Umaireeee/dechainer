package io.github.warleysr.dechainer.models

data class AppGroup(
    val id: String,
    val name: String,
    val packageNames: Set<String> = emptySet(),
    val timeLimitMinutes: Int = 0,
    val timeWindows: List<TimeWindow> = emptyList()
)
