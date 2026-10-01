package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import timber.log.Timber

/**
 * `urge_entry` (blueprint section 7). Every write is a transaction. A status moves only along
 * [UrgeFlowRules.canMove], checked inside the same transaction as the write, so two screens (or the
 * screen and the retry job) can never both finish the same step. The deep dive is saved and the note
 * deleted in one transaction: the note is never gone without its deep dive being there.
 *
 * A row that cannot be read (an unknown kind or status, say) is skipped by every read and never
 * touched by a write: reads cannot overwrite stored data.
 */
class UrgeEntryRepository(private val database: DechainerDatabase) {

    /** Creates an entry in its starting status and returns its id. */
    fun insert(
        kind: UrgeKind, source: UrgeSource, createdAt: Long,
        lockStartedAt: Long? = null, lockEndedAt: Long? = null
    ): Long {
        val row = ContentValues().apply {
            put("created_at", createdAt)
            put("kind", kind.name)
            put("source", source.name)
            put("lock_started_at", lockStartedAt)
            put("lock_ended_at", lockEndedAt)
            put("status", UrgeFlowRules.startingStatus(kind).name)
        }
        var id = -1L
        inTransaction { id = it.insertOrThrow(TABLE, null, row) }
        return id
    }

    fun get(id: Long): UrgeEntry? =
        database.readableDatabase.query(TABLE, COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    /** Entries still in the flow (breathing, writing, answering), newest first, for resuming. */
    fun unfinished(): List<UrgeEntry> =
        list("status IN (?, ?, ?)", arrayOf(UrgeStatus.LOCKED.name, UrgeStatus.WRITING.name, UrgeStatus.QUESTIONS.name))

    /** Entries waiting for a deep dive, oldest first: the retry job's list. */
    fun pendingDeepDives(): List<UrgeEntry> =
        database.readableDatabase.query(
            TABLE, COLUMNS, "status = ?", arrayOf(UrgeStatus.PENDING_DEEPDIVE.name),
            null, null, "created_at ASC"
        ).use { c -> buildList { while (c.moveToNext()) read(c)?.let { add(it) } } }

    /** Entries created in [from, to), oldest first (the weekly report's read). */
    fun between(from: Long, to: Long): List<UrgeEntry> =
        list("created_at >= ? AND created_at < ?", arrayOf(from.toString(), to.toString())).sortedBy { it.createdAt }

    /** Every entry that can be read, oldest first. */
    fun all(): List<UrgeEntry> = list("1 = 1", emptyArray()).sortedBy { it.createdAt }

    /** The time of the first entry, or null. */
    fun firstCreatedAt(): Long? =
        database.readableDatabase.rawQuery("SELECT MIN(created_at) FROM $TABLE", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** Removes the deep dive and keeps the entry as a counted stub (blueprint 6.6). The note is already gone once a deep dive exists. */
    fun deleteDeepDive(id: Long): Boolean {
        var n = 0
        inTransaction {
            n = it.update(TABLE, ContentValues().apply { putNull("deep_dive") }, "id = ? AND status = ?", arrayOf(id.toString(), UrgeStatus.DONE.name))
        }
        return n > 0
    }

    /** Every entry that can be read, counted stubs included. */
    fun count(): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Sets the lock window once the engine has decided (the entry is written first, the lock second). */
    fun setLockWindow(id: Long, startedAt: Long, endedAt: Long) {
        inTransaction {
            it.update(
                TABLE, ContentValues().apply { put("lock_started_at", startedAt); put("lock_ended_at", endedAt) },
                "id = ?", arrayOf(id.toString())
            )
        }
    }

    /** The writing screen is up. */
    fun markWriting(id: Long): Boolean = move(id, UrgeStatus.WRITING)

    /** The note is submitted. It is stored at once, before any question is asked, so a killed app loses nothing. */
    fun saveNote(id: Long, text: String): Boolean = move(id, UrgeStatus.QUESTIONS) { put("raw_text", text) }

    /** The questions the owner is shown, stored so a deep dive can be retried without asking again. Only while answering. */
    fun saveQuestions(id: Long, questions: List<Question>): Boolean {
        var done = false
        inTransaction {
            done = it.update(
                TABLE, ContentValues().apply { put("questions_json", UrgeJson.questionsToJson(questions)) },
                "id = ? AND status = ?", arrayOf(id.toString(), UrgeStatus.QUESTIONS.name)
            ) == 1
        }
        return done
    }

    /** "Not now": a counted stub with no text. */
    fun skip(id: Long): Boolean = move(id, UrgeStatus.SKIPPED) { putNull("raw_text") }

    /** The answers are in. From here the deep dive is owed, and the retry job owns it if the screen cannot finish. */
    fun saveAnswers(id: Long, answers: List<Answer>): Boolean = move(id, UrgeStatus.PENDING_DEEPDIVE) {
        put("answers_json", UrgeJson.answersToJson(answers))
    }

    /** Saves the deep dive and deletes the note, in one transaction. Refused if there is no deep dive to keep. */
    fun saveDeepDive(id: Long, markdown: String): Boolean {
        if (markdown.isBlank()) return false
        return move(id, UrgeStatus.DONE) {
            put("deep_dive", markdown)
            putNull("raw_text")
        }
    }

    /** Deletes one entry (blueprint 6.6). Returns whether a row went. */
    fun delete(id: Long): Boolean {
        var n = 0
        inTransaction { n = it.delete(TABLE, "id = ?", arrayOf(id.toString())) }
        return n > 0
    }

    private fun move(id: Long, to: UrgeStatus, extra: ContentValues.() -> Unit = {}): Boolean {
        var moved = false
        inTransaction { db ->
            // Only a row that can be read in full is ever written: an unreadable one is left exactly as it is.
            val from = db.query(TABLE, arrayOf("status", "kind", "source"), "id = ?", arrayOf(id.toString()), null, null, null)
                .use { c ->
                    if (!c.moveToFirst()) null
                    else if (UrgeKind.entries.none { it.name == c.getString(1) } || UrgeSource.entries.none { it.name == c.getString(2) }) null
                    else statusOf(c.getString(0))
                }
            if (from == null || !UrgeFlowRules.canMove(from, to)) return@inTransaction
            val values = ContentValues().apply { put("status", to.name); extra() }
            moved = db.update(TABLE, values, "id = ? AND status = ?", arrayOf(id.toString(), from.name)) == 1
        }
        return moved
    }

    private fun list(where: String, args: Array<String>): List<UrgeEntry> =
        database.readableDatabase.query(TABLE, COLUMNS, where, args, null, null, "created_at DESC").use { c ->
            buildList { while (c.moveToNext()) read(c)?.let { add(it) } }
        }

    private fun read(c: Cursor): UrgeEntry? {
        val status = statusOf(c.getString(6))
        val kind = UrgeKind.entries.firstOrNull { it.name == c.getString(2) }
        val source = UrgeSource.entries.firstOrNull { it.name == c.getString(3) }
        if (status == null || kind == null || source == null) {
            Timber.w("Urge entry %d not readable; left as it is", c.getLong(0))
            return null
        }
        return UrgeEntry(
            id = c.getLong(0),
            createdAt = c.getLong(1),
            kind = kind,
            source = source,
            lockStartedAt = if (c.isNull(4)) null else c.getLong(4),
            lockEndedAt = if (c.isNull(5)) null else c.getLong(5),
            status = status,
            rawText = if (c.isNull(7)) null else c.getString(7),
            questionsJson = if (c.isNull(8)) null else c.getString(8),
            answersJson = if (c.isNull(9)) null else c.getString(9),
            deepDive = if (c.isNull(10)) null else c.getString(10)
        )
    }

    private fun statusOf(name: String?): UrgeStatus? = UrgeStatus.entries.firstOrNull { it.name == name }

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
        val COLUMNS = arrayOf(
            "id", "created_at", "kind", "source", "lock_started_at", "lock_ended_at", "status",
            "raw_text", "questions_json", "answers_json", "deep_dive"
        )
    }
}
