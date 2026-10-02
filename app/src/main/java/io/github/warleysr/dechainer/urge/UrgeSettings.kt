package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.Rules

/**
 * The owner's urge settings: the personal reason (D5, shown as a breathing prompt) and the crisis
 * contact (D4). Both stay on the phone.
 */
class UrgeSettings(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("urge_settings", Context.MODE_PRIVATE)

    val reason: String get() = prefs.getString(KEY_REASON, "") ?: ""

    val contact: CrisisContact
        get() = CrisisContact(prefs.getString(KEY_CONTACT_NAME, "") ?: "", prefs.getString(KEY_CONTACT_NUMBER, "") ?: "")

    /** One line the owner writes on a calm day. */
    fun setReason(text: String): Boolean {
        prefs.edit { putString(KEY_REASON, text.trim().replace('\n', ' ').take(Rules.MAX_REASON_CHARS)) }
        return true
    }

    fun setContact(name: String, number: String): Boolean {
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
