package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeSource
import timber.log.Timber

/**
 * `urge_entry` (blueprint section 7): the plain log of urge locks. Every write is a transaction.
 * A row that cannot be read (an unknown source, say) is skipped by every read and never touched by
 * a write: reads cannot overwrite stored data.
 */
class UrgeEntryRepository(private val database: DechainerDatabase) {

    /** Creates an entry and returns its id. */
    fun insert(source: UrgeSource, createdAt: Long, lockStartedAt: Long? = null, lockEndedAt: Long? = null): Long {
        val row = ContentValues().apply {
            put("created_at", createdAt)
            put("source", source.name)
            put("lock_started_at", lockStartedAt)
            put("lock_ended_at", lockEndedAt)
        }
        var id = -1L
        inTransaction { id = it.insertOrThrow(TABLE, null, row) }
        return id
    }

    fun get(id: Long): UrgeEntry? =
        database.readableDatabase.query(TABLE, COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    /** Every entry that can be read, oldest first. */
    fun all(): List<UrgeEntry> =
        database.readableDatabase.query(TABLE, COLUMNS, null, null, null, null, "created_at ASC").use { c ->
            buildList { while (c.moveToNext()) read(c)?.let { add(it) } }
        }

    /** The newest entry that can be read, or null. */
    fun latest(): UrgeEntry? =
        database.readableDatabase.query(TABLE, COLUMNS, null, null, null, null, "created_at DESC", "1")
            .use { if (it.moveToFirst()) read(it) else null }

    /** The time of the first entry, or null. */
    fun firstCreatedAt(): Long? =
        database.readableDatabase.rawQuery("SELECT MIN(created_at) FROM $TABLE", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** Every entry that can be read. */
    fun count(): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Sets the lock window once the engine has decided (the entry is written first, the lock second). */
    fun setLockWindow(id: Long, startedAt: Long, endedAt: Long) {
        inTransaction { db ->
            if (!readable(db, id)) return@inTransaction
            db.update(
                TABLE, ContentValues().apply { put("lock_started_at", startedAt); put("lock_ended_at", endedAt) },
                "id = ?", arrayOf(id.toString())
            )
        }
    }

    /** Deletes one entry (blueprint 6.6). Returns whether a row went. A row that cannot be read is left as it is. */
    fun delete(id: Long): Boolean {
        var n = 0
        inTransaction { db -> if (readable(db, id)) n = db.delete(TABLE, "id = ?", arrayOf(id.toString())) }
        return n > 0
    }

    /** Whether the row can be read in full: a row that cannot is never written or deleted (blueprint 7). */
    private fun readable(db: SQLiteDatabase, id: Long): Boolean =
        db.query(TABLE, arrayOf("source"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            c.moveToFirst() && UrgeSource.entries.any { it.name == c.getString(0) }
        }

    private fun read(c: Cursor): UrgeEntry? {
        val source = UrgeSource.entries.firstOrNull { it.name == c.getString(2) }
        if (source == null) {
            Timber.w("Urge entry %d not readable; left as it is", c.getLong(0))
            return null
        }
        return UrgeEntry(
            id = c.getLong(0),
            createdAt = c.getLong(1),
            source = source,
            lockStartedAt = if (c.isNull(3)) null else c.getLong(3),
            lockEndedAt = if (c.isNull(4)) null else c.getLong(4)
        )
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
        const val TABLE = "urge_entry"
        val COLUMNS = arrayOf("id", "created_at", "source", "lock_started_at", "lock_ended_at")
    }
}
