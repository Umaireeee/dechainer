package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.UrgeEntry

/** Stores the urge log on the phone only. Nothing here is ever sent anywhere. */
object UrgeRepository {
    private const val PREFS = "urge_log"
    private const val KEY = "entries"
    /** Plenty for years; the oldest go first. */
    private const val LIMIT = 2000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<UrgeEntry> =
        UrgeEntry.listFromJson(prefs(context).getString(KEY, null)).sortedBy { it.time }

    fun add(context: Context, entry: UrgeEntry) {
        val next = (all(context) + entry).takeLast(LIMIT)
        prefs(context).edit(commit = true) { putString(KEY, UrgeEntry.listToJson(next)) }
    }
}
