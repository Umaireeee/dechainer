package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.Rules

/**
 * The owner's blackout settings: how long a blackout lasts. Stays on the phone.
 */
class UrgeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("urge_settings", Context.MODE_PRIVATE)

    /** How long a blackout lasts, in minutes, between the fixed bounds. */
    val durationMinutes: Int
        get() = prefs.getInt(KEY_DURATION_MIN, Rules.URGE_LOCK_DEFAULT_MINUTES)
            .coerceIn(Rules.URGE_LOCK_MIN_MINUTES, Rules.URGE_LOCK_MAX_MINUTES)

    val durationMs: Long get() = durationMinutes * 60_000L

    /** The chosen length, kept inside the fixed bounds. */
    fun setDurationMinutes(minutes: Int): Boolean {
        prefs.edit { putInt(KEY_DURATION_MIN, minutes.coerceIn(Rules.URGE_LOCK_MIN_MINUTES, Rules.URGE_LOCK_MAX_MINUTES)) }
        return true
    }

    private companion object {
        const val KEY_DURATION_MIN = "duration_min"
    }
}
