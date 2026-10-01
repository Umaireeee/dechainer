package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.lock.SettingsFreeze

/**
 * The owner's urge settings: the personal reason (D5, shown as a breathing prompt) and the crisis
 * contact (D4). Both stay on the phone. Writes are refused on a punishment day (blueprint 5.5);
 * reading is always allowed, so the breathing screen and the crisis card work on any day.
 */
class UrgeSettings(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("urge_settings", Context.MODE_PRIVATE)

    val reason: String get() = prefs.getString(KEY_REASON, "") ?: ""

    val contact: CrisisContact
        get() = CrisisContact(prefs.getString(KEY_CONTACT_NAME, "") ?: "", prefs.getString(KEY_CONTACT_NUMBER, "") ?: "")

    val frozen: Boolean get() = SettingsFreeze.isFrozen(ctx)

    /** One line the owner writes on a calm day. Returns false if a punishment day refused it. */
    fun setReason(text: String): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "personal reason")) return false
        prefs.edit { putString(KEY_REASON, text.trim().replace('\n', ' ').take(Rules.MAX_REASON_CHARS)) }
        return true
    }

    fun setContact(name: String, number: String): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "crisis contact")) return false
        val c = CrisisContact.of(name, number)
        prefs.edit { putString(KEY_CONTACT_NAME, c.name); putString(KEY_CONTACT_NUMBER, c.number) }
        return true
    }

    private companion object {
        const val KEY_REASON = "reason"
        const val KEY_CONTACT_NAME = "contact_name"
        const val KEY_CONTACT_NUMBER = "contact_number"
    }
}
