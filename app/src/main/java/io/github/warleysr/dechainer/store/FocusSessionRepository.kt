package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FocusSession
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome

/** A `focus_session` row (blueprint section 7). [id] is the start time in epoch millis, bumped by one on a clash. */
data class StoredSession(
    val id: Long,
    val source: FocusSource,
    val flavor: Flavor,
    val purpose: String,
    val startedAt: Long,
    val plannedEndAt: Long,
    val endedAt: Long?,
    val focusedMinutes: Int,
    val outcome: SessionOutcome?
)

/** A `focus_checkin` row. */
data class StoredCheckin(val id: Long, val sessionId: Long, val at: Long, val answer: CheckinAnswer, val reset: ResetResult)

/**
 * `focus_session` and `focus_checkin`. Reads never write, and a row whose text cannot be read as
 * the types asked for is skipped by the read and left exactly as it is. Every write is a transaction.
 */
class FocusSessionRepository(private val database: DechainerDatabase) {

    /** Stores a new session and returns its id: [StoredSession.id], or the next free millisecond if that one is taken. */
    fun insert(session: StoredSession): Long {
        var id = session.id
        inTransaction { db ->
            while (exists(db, id)) id += 1
            val values = ContentValues().apply {
                put("id", id)
                put("source", session.source.name)
                put("flavor", session.flavor.name)
                put("purpose", session.purpose)
                put("started_at", session.startedAt)
                put("planned_end_at", session.plannedEndAt)
                if (session.endedAt == null) putNull("ended_at") else put("ended_at", session.endedAt)
                put("focused_minutes", session.focusedMinutes)
                if (session.outcome == null) putNull("outcome") else put("outcome", session.outcome.name)
            }
            check(db.insert(SESSIONS, null, values) != -1L) { "Could not store the focus session" }
        }
        return id
    }

    /** The flavor and purpose became known (or fell back to their default). */
    fun updateChoice(id: Long, flavor: Flavor, purpose: String) {
        inTransaction { db ->
            val values = ContentValues().apply { put("flavor", flavor.name); put("purpose", purpose) }
            db.update(SESSIONS, values, "id = ?", arrayOf(id.toString()))
        }
    }

    /** Closes the session. Closing one twice keeps the first closing. */
    fun end(id: Long, endedAt: Long, outcome: SessionOutcome, focusedMinutes: Int) {
        inTransaction { db ->
            val values = ContentValues().apply {
                put("ended_at", endedAt); put("outcome", outcome.name); put("focused_minutes", focusedMinutes)
            }
            db.update(SESSIONS, values, "id = ? AND ended_at IS NULL", arrayOf(id.toString()))
        }
    }

    fun addCheckin(sessionId: Long, at: Long, answer: CheckinAnswer, reset: ResetResult) {
        inTransaction { db ->
            val values = ContentValues().apply {
                put("session_id", sessionId); put("at", at); put("answer", answer.name); put("reset_result", reset.name)
            }
            check(db.insert(CHECKINS, null, values) != -1L) { "Could not store the check-in" }
        }
    }

    /** Removes one session and its check-ins. */
    fun delete(id: Long) {
        inTransaction { db ->
            db.delete(CHECKINS, "session_id = ?", arrayOf(id.toString()))
            db.delete(SESSIONS, "id = ?", arrayOf(id.toString()))
        }
    }

    fun session(id: Long): StoredSession? =
        database.readableDatabase.query(SESSIONS, SESSION_COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) readSession(it) else null }

    /** Every session that can be read, oldest first. */
    fun sessions(): List<StoredSession> =
        database.readableDatabase.query(SESSIONS, SESSION_COLUMNS, null, null, null, null, "started_at ASC, id ASC")
            .use { c -> buildList { while (c.moveToNext()) readSession(c)?.let(::add) } }

    fun checkins(sessionId: Long): List<StoredCheckin> =
        database.readableDatabase.query(CHECKINS, CHECKIN_COLUMNS, "session_id = ?", arrayOf(sessionId.toString()), null, null, "at ASC, id ASC")
            .use { c -> buildList { while (c.moveToNext()) readCheckin(c)?.let(::add) } }

    fun allCheckins(): List<StoredCheckin> =
        database.readableDatabase.query(CHECKINS, CHECKIN_COLUMNS, null, null, null, null, "at ASC, id ASC")
            .use { c -> buildList { while (c.moveToNext()) readCheckin(c)?.let(::add) } }

    fun count(): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $SESSIONS", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * The sessions as the log screen knows them: one [FocusSession] per row, [FocusSession.done] from
     * its check-ins (any "No" makes it false, otherwise any "Yes" makes it true, otherwise unanswered).
     * Tags and lecture counts are not stored (blueprint section 7), so they come back empty.
     */
    fun log(): List<FocusSession> {
        val byId = allCheckins().groupBy { it.sessionId }
        return sessions().map { s ->
            val answers = byId[s.id].orEmpty().map { it.answer }
            val done = when {
                CheckinAnswer.NO in answers -> false
                CheckinAnswer.YES in answers -> true
                else -> null
            }
            FocusSession(
                id = s.id,
                minutes = s.focusedMinutes,
                done = done,
                intention = s.purpose.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Adds the sessions of the old log (blueprint 13): once, in one transaction. A session whose id
     * is already stored is left as it is, so running it twice adds nothing. Returns how many were added.
     */
    fun importLegacy(old: List<FocusSession>): Int {
        var added = 0
        inTransaction { db ->
            old.forEach { s ->
                val end = s.id + s.minutes * 60_000L
                val values = ContentValues().apply {
                    put("id", s.id)
                    put("source", FocusSource.MANUAL.name)
                    put("flavor", Flavor.USUAL.name)
                    put("purpose", s.intention.orEmpty())
                    put("started_at", s.id)
                    put("planned_end_at", end)
                    put("ended_at", end)
                    put("focused_minutes", s.minutes)
                    put("outcome", SessionOutcome.COMPLETED.name)
                }
                if (db.insertWithOnConflict(SESSIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    added++
                    s.done?.let { yes ->
                        val check = ContentValues().apply {
                            put("session_id", s.id); put("at", end)
                            put("answer", (if (yes) CheckinAnswer.YES else CheckinAnswer.NO).name)
                            put("reset_result", ResetResult.NONE.name)
                        }
                        db.insert(CHECKINS, null, check)
                    }
                }
            }
        }
        return added
    }

    private fun exists(db: SQLiteDatabase, id: Long): Boolean =
        db.rawQuery("SELECT 1 FROM $SESSIONS WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }

    private fun readSession(c: android.database.Cursor): StoredSession? = try {
        StoredSession(
            id = c.getLong(0),
            source = FocusSource.valueOf(c.getString(1)),
            flavor = Flavor.valueOf(c.getString(2)),
            purpose = c.getString(3) ?: "",
            startedAt = c.getLong(4),
            plannedEndAt = c.getLong(5),
            endedAt = if (c.isNull(6)) null else c.getLong(6),
            focusedMinutes = c.getInt(7),
            outcome = if (c.isNull(8)) null else SessionOutcome.valueOf(c.getString(8))
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readCheckin(c: android.database.Cursor): StoredCheckin? = try {
        StoredCheckin(
            c.getLong(0), c.getLong(1), c.getLong(2),
            CheckinAnswer.valueOf(c.getString(3)), ResetResult.valueOf(c.getString(4))
        )
    } catch (_: IllegalArgumentException) {
        null
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
        Store.changed()
    }

    private companion object {
        const val SESSIONS = "focus_session"
        const val CHECKINS = "focus_checkin"
        val SESSION_COLUMNS = arrayOf("id", "source", "flavor", "purpose", "started_at", "planned_end_at", "ended_at", "focused_minutes", "outcome")
        val CHECKIN_COLUMNS = arrayOf("id", "session_id", "at", "answer", "reset_result")
    }
}
