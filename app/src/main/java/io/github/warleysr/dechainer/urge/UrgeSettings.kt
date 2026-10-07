package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.Rules

/**
 * The owner's urge settings: the personal reason (D5, shown as a breathing prompt) and how long an
 * urge lock lasts. Both stay on the phone.
 */
class UrgeSettings(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("urge_settings", Context.MODE_PRIVATE)

    val reason: String get() = prefs.getString(KEY_REASON, "") ?: ""

    /** How long an urge lock lasts, in minutes, between the blueprint's bounds. */
    val durationMinutes: Int
        get() = prefs.getInt(KEY_DURATION_MIN, Rules.URGE_LOCK_DEFAULT_MINUTES)
            .coerceIn(Rules.URGE_LOCK_MIN_MINUTES, Rules.URGE_LOCK_MAX_MINUTES)

    val durationMs: Long get() = durationMinutes * 60_000L

    /** One line the owner writes on a calm day. */
    fun setReason(text: String): Boolean {
        prefs.edit { putString(KEY_REASON, text.trim().replace('\n', ' ').take(Rules.MAX_REASON_CHARS)) }
        return true
    }

    /** The chosen length, kept inside the blueprint's bounds. */
    fun setDurationMinutes(minutes: Int): Boolean {
        prefs.edit { putInt(KEY_DURATION_MIN, minutes.coerceIn(Rules.URGE_LOCK_MIN_MINUTES, Rules.URGE_LOCK_MAX_MINUTES)) }
        return true
    }

    private companion object {
        const val KEY_REASON = "reason"
        const val KEY_DURATION_MIN = "duration_min"
    }
}
