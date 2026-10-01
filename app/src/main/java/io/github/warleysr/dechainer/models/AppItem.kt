package io.github.warleysr.dechainer.models

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    /**
     * Loads the icon the first time a screen draws it. Decoding every installed app's icon while
     * building the list made each reload slow, and most icons are never shown.
     */
    val iconLoader: () -> Drawable,
    val isSystem: Boolean,
    val isUninstallBlocked: Boolean = false,
    val isSuspended: Boolean = false
) {
    val icon: Drawable get() = iconLoader()
}
