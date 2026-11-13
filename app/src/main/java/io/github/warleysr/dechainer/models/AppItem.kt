package io.github.warleysr.dechainer.models

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val isHidden: Boolean = false,
    val isUninstallBlocked: Boolean = false,
    val timeLimit: TimeLimit = TimeLimit.NONE,
    val reopeningSeconds: Int = 0,
    val timeWindows: List<TimeWindow> = emptyList(),
    val isSuspended: Boolean = false,
    /** Has an icon in the launcher, i.e. something the user can actually open. */
    val isLaunchable: Boolean = true
)
