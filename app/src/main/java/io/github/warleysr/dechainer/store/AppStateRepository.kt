package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * `app_state` (blueprint section 7): small facts the app must not lose, as key and value text.
 *
 * Reads never write. A value that cannot be parsed as the type asked for comes back as null and the
 * row is left exactly as it was, so a failed read can never overwrite stored data. Every write is
 * a transaction: [setAll] stores all of its values or none.
 */
class AppStateRepository(private val database: DechainerDatabase) {

    fun get(key: String): String? =
        database.readableDatabase.query(TABLE, arrayOf(COL_VALUE), "$COL_KEY = ?", arrayOf(key), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun getLong(key: String): Long? = get(key)?.toLongOrNull()

    fun getInt(key: String): Int? = get(key)?.toIntOrNull()

    fun all(): Map<String, String> =
        database.readableDatabase.query(TABLE, arrayOf(COL_KEY, COL_VALUE), null, null, null, null, COL_KEY).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun set(key: String, value: String) = setAll(mapOf(key to value))

    /** Stores every pair in one transaction. */
    fun setAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        inTransaction { db ->
            values.forEach { (key, value) ->
                val row = ContentValues().apply { put(COL_KEY, key); put(COL_VALUE, value) }
                check(db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                    "Could not store $key"
                }
            }
        }
    }

    /** Deletes every key in one transaction: all of them go, or none do. */
    fun removeAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        inTransaction { db -> keys.forEach { db.delete(TABLE, "$COL_KEY = ?", arrayOf(it)) } }
    }

    fun remove(key: String) {
        inTransaction { it.delete(TABLE, "$COL_KEY = ?", arrayOf(key)) }
    }

    /**
     * Runs [work] once ever, keyed by [name]. [work] returns whether it succeeded; only a success is
     * remembered, so a failed one-off migration tries again on the next start.
     */
    fun runOnce(name: String, work: () -> Boolean) {
        val flag = AppStateKeys.MIGRATED_PREFIX + name
        if (get(flag) == DONE) return
        if (work()) set(flag, DONE)
    }

    private fun inTransaction(work: (SQLiteDatabase) -> Unit) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            work(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TABLE = "app_state"
        const val COL_KEY = "key"
        const val COL_VALUE = "value"
        const val DONE = "1"
    }
}
