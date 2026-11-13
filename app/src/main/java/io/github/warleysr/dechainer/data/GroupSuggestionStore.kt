package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit

/** Remembers which group suggestions the user turned down, so they are not offered again. */
object GroupSuggestionStore {
    private const val PREFS_NAME = "group_suggestions"
    private const val KEY_DISMISSED = "dismissed"

    fun dismissed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    fun dismiss(context: Context, id: String) {
        val updated = HashSet(dismissed(context)).apply { add(id) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putStringSet(KEY_DISMISSED, updated) }
    }
}
