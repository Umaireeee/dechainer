package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.lock.SettingsFreeze

/**
 * The owner's urge settings: the personal reason (D5, shown as a breathing prompt and under every deep
 * dive). It stays on the phone. Writes are refused on a punishment day (blueprint 5.5); reading is
 * always allowed, so the breathing screen works on any day. The saved crisis contact (D4) was
 * dropped on the owner's request: the support card now points to people and numbers the owner already has.
 */
class UrgeSettings(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("urge_settings", Context.MODE_PRIVATE)

    init {
        // The saved contact of earlier builds is dropped from the phone, not just hidden.
        if (prefs.contains("contact_name") || prefs.contains("contact_number")) {
            prefs.edit { remove("contact_name"); remove("contact_number") }
        }
    }

    val reason: String get() = prefs.getString(KEY_REASON, "") ?: ""

    /** The language the AI answers in. Blank means "the language of my note". English by default. */
    val replyLanguage: String get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    fun setReplyLanguage(text: String): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "reply language")) return false
        // Letters, spaces and hyphens only: it goes into a request, so nothing else is let through.
        val clean = text.filter { it.isLetter() || it == ' ' || it == '-' }.trim().take(30)
        prefs.edit { putString(KEY_LANGUAGE, clean) }
        return true
    }

    val frozen: Boolean get() = SettingsFreeze.isFrozen(ctx)

    /** One line the owner writes on a calm day. Returns false if a punishment day refused it. */
    fun setReason(text: String): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "personal reason")) return false
        prefs.edit { putString(KEY_REASON, text.trim().replace('\n', ' ').take(Rules.MAX_REASON_CHARS)) }
        return true
    }

    private companion object {
        const val KEY_REASON = "reason"
        const val KEY_LANGUAGE = "reply_language"
        const val DEFAULT_LANGUAGE = "English"
    }
}
