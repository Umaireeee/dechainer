package io.github.warleysr.dechainer.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit

/**
 * Since Android 14 an app can be refused permission to show a full-screen alert over the lock
 * screen. Then the session-end question still works from the notification's own buttons; this
 * only tracks whether to point out the setting that brings the full-screen version back.
 */
object FullScreenAlerts {
    private const val PREFS = "full_screen_hint"
    private const val KEY_DISMISSED = "dismissed"

    /** True where a full-screen alert may be shown: always before Android 14, and on 14+ when allowed. */
    fun isAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return nm.canUseFullScreenIntent()
    }

    /** Whether Settings should offer the hint: only on Android 14+, only when not allowed, and only until dismissed. */
    fun shouldHint(sdk: Int, allowed: Boolean, dismissed: Boolean): Boolean = sdk >= 34 && !allowed && !dismissed

    fun isHintDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISMISSED, false)

    fun dismissHint(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DISMISSED, true) }
    }

    /** The system screen where the permission is switched on. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
