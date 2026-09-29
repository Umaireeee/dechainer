package io.github.warleysr.dechainer.models

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val isUninstallBlocked: Boolean = false,
    val isSuspended: Boolean = false
)
