package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.json.JSONArray
import org.json.JSONObject

enum class DeleteResult {
    DELETED,

    /** The entry is still in the flow, or the session is still running. */
    NOT_ALLOWED,
    MISSING
}

/** What an import did. Anything it did not take is counted, never silently dropped. */
data class ImportResult(val ok: Boolean, val added: Int, val skipped: Int)

/** What a wipe removed and what it had to keep. */
data class WipeResult(val removed: Int, val kept: Int)

/**
 * Delete, wipe, export and import (blueprint 6.6). Nothing here touches `app_state`, so the
 * surviving state stays. The wipe's recovery code is asked by the screen.
 */
class DataTools(private val ctx: Context, private val now: () -> Long) {
    private val urges = Store.urgeEntries(ctx)
    private val focus = Store.focus(ctx)

    private fun urgeDeletable(e: io.github.warleysr.dechainer.urge.UrgeEntry): Boolean =
        e.status in setOf(UrgeStatus.DONE, UrgeStatus.SKIPPED, UrgeStatus.PENDING_DEEPDIVE)

    fun deleteUrge(id: Long): DeleteResult {
        val e = urges.get(id) ?: return DeleteResult.MISSING
        if (!urgeDeletable(e)) return DeleteResult.NOT_ALLOWED
        return if (urges.delete(id)) DeleteResult.DELETED else DeleteResult.MISSING
    }

    fun deleteDeepDive(id: Long): DeleteResult {
        return if (urges.deleteDeepDive(id)) DeleteResult.DELETED else DeleteResult.MISSING
    }

    fun deleteSession(id: Long): DeleteResult {
        val s = focus.session(id) ?: return DeleteResult.MISSING
        if (s.endedAt == null) return DeleteResult.NOT_ALLOWED
        focus.delete(id)
        return DeleteResult.DELETED
    }

    /** Wipe all data: everything the rules allow to go. The caller has asked for the recovery code. */
    fun wipeAll(): WipeResult {
        var removed = 0
        var kept = 0
        // One transaction, so a kill mid-wipe cannot leave a half-emptied store (blueprint 7).
        val db = Store.raw(ctx).writableDatabase
        db.beginTransaction()
        try {
            urges.all().forEach { e ->
                if (e.status.isFinal || e.status == UrgeStatus.PENDING_DEEPDIVE) {
                    if (urgeDeletable(e) && urges.delete(e.id)) removed++ else kept++
                } else kept++ // still in the flow
            }
            focus.sessions().forEach { s ->
                if (s.endedAt != null) { focus.delete(s.id); removed++ } else kept++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return WipeResult(removed = removed, kept = kept)
    }

    // ---- export and import ----

    /** The whole store, except `app_state` (the trusted clock checkpoints are never exported). */
    fun export(): String {
        val db = Store.raw(ctx).readableDatabase
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("exportedAt", now())
            .also { o -> TABLES.forEach { t -> o.put(t, dump(db, t)) } }
            .toString()
    }

    /**
     * Reads a file made by [export] back in. It only adds: a row already here is left exactly as it is,
     * and an entry that was mid-flow is kept as a counted stub.
     */
    fun import(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT || root.optInt("version", -1) !in 1..VERSION) return ImportResult(false, 0, 0)

        var added = 0
        var skipped = 0
        val db = Store.raw(ctx).writableDatabase
        db.beginTransaction()
        try {
            fun rows(t: String): List<JSONObject> = root.optJSONArray(t)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

            for (r in rows("urge_entry")) {
                val kind = UrgeKind.entries.firstOrNull { it.name == r.optString("kind") }
                val source = UrgeSource.entries.firstOrNull { it.name == r.optString("source") }
                val status = UrgeStatus.entries.firstOrNull { it.name == r.optString("status") }
                if (kind == null || source == null || status == null || !r.has("created_at") || exists(db, "urge_entry", "created_at = ? AND kind = ?", r.getLong("created_at").toString(), kind.name)) { skipped++; continue }
                val kept = when (status) { UrgeStatus.LOCKED, UrgeStatus.WRITING -> UrgeStatus.SKIPPED; UrgeStatus.QUESTIONS -> UrgeStatus.PENDING_DEEPDIVE; else -> status }
                val note = if (kept == UrgeStatus.SKIPPED) null else r.optNullString("raw_text")
                db.insertOrThrow("urge_entry", null, ContentValues().apply {
                    put("created_at", r.getLong("created_at")); put("kind", kind.name); put("source", source.name)
                    putLong("lock_started_at", r); putLong("lock_ended_at", r); put("status", kept.name)
                    put("raw_text", note); put("questions_json", r.optNullString("questions_json"))
                    put("answers_json", r.optNullString("answers_json")); put("deep_dive", r.optNullString("deep_dive"))
                    put("support", r.optInt("support"))
                }); added++
            }

            val newSessions = mutableSetOf<Long>()
            for (r in rows("focus_session")) {
                val id = r.optLong("id", -1)
                // Only rows this build can read back: an unknown enum would be silently dropped on every later read.
                val source = runCatching { io.github.warleysr.dechainer.focus.FocusSource.valueOf(r.optString("source")) }.getOrNull()
                val flavor = runCatching { io.github.warleysr.dechainer.focus.Flavor.valueOf(r.optString("flavor")) }.getOrNull()
                val outcomeRaw = r.optNullString("outcome")
                val outcome = outcomeRaw?.let { runCatching { io.github.warleysr.dechainer.focus.SessionOutcome.valueOf(it) }.getOrNull() }
                if (id < 0 || source == null || flavor == null || (outcomeRaw != null && outcome == null) ||
                    exists(db, "focus_session", "id = ?", id.toString())) { skipped++; continue }
                db.insertOrThrow("focus_session", null, ContentValues().apply {
                    put("id", id); put("source", source.name); put("flavor", flavor.name)
                    put("purpose", r.optString("purpose", "")); put("started_at", r.getLong("started_at"))
                    put("planned_end_at", r.getLong("planned_end_at")); putLong("ended_at", r)
                    put("focused_minutes", r.optInt("focused_minutes")); put("outcome", outcome?.name)
                }); newSessions += id; added++
            }
            for (r in rows("focus_checkin")) {
                if (r.optLong("session_id", -1) !in newSessions) { skipped++; continue }
                db.insertOrThrow("focus_checkin", null, ContentValues().apply {
                    put("session_id", r.getLong("session_id")); put("at", r.getLong("at"))
                    put("answer", r.optString("answer")); put("reset_result", r.optString("reset_result", "NONE"))
                }); added++
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // One bad row stops the import and nothing of it is kept.
            return ImportResult(false, 0, 0)
        } finally {
            db.endTransaction()
        }
        return ImportResult(true, added, skipped)
    }

    private fun exists(db: SQLiteDatabase, table: String, where: String, vararg args: String): Boolean =
        db.query(table, arrayOf("1"), where, arrayOf(*args), null, null, null, "1").use { it.moveToFirst() }

    private fun dump(db: SQLiteDatabase, table: String): JSONArray = JSONArray().also { out ->
        db.query(table, null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                        else -> o.put(name, c.getString(i))
                    }
                }
                out.put(o)
            }
        }
    }

    private fun JSONObject.optNullString(key: String): String? = if (isNull(key)) null else optString(key)
    private fun ContentValues.putLong(key: String, from: JSONObject) { if (from.isNull(key)) putNull(key) else put(key, from.getLong(key)) }

    companion object {
        const val FORMAT = "dechainer-export"
        const val VERSION = 1
        private val TABLES = listOf("urge_entry", "focus_session", "focus_checkin")
    }
}
