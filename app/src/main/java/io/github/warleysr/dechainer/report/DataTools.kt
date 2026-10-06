package io.github.warleysr.dechainer.report

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class DeleteResult {
    DELETED,

    /** The rules of 6.4 say no: the open week's goals, a slip or session whose day is not settled, one still running. */
    NOT_ALLOWED,
    MISSING
}

/** What an import did. Anything it did not take is counted, never silently dropped. */
data class ImportResult(val ok: Boolean, val added: Int, val skipped: Int)

/** What a wipe removed and what it had to keep. */
data class WipeResult(val removed: Int, val kept: Int)

/**
 * Delete, wipe, export and import (blueprint 6.6). Nothing here touches `app_state`, `day` rows,
 * rest days, `activatedOn` or `weekAnchor`, so the daily results the progress graphs read stay. The
 * wipe's recovery code is asked by the screen.
 */
class DataTools(private val ctx: Context, private val zone: ZoneId, private val now: () -> Long) {
    private val urges = Store.urgeEntries(ctx)
    private val focus = Store.focus(ctx)
    private val days = Store.days(ctx)
    private val reports = Store.reports(ctx)
    private val periodReports = Store.periodReports(ctx)

    private fun measuredOk(at: Long): Boolean =
        ReportRules.canDeleteMeasured(at, now(), zone, days.goals(DayWindow.dateOf(at, zone)))

    private fun urgeDeletable(e: io.github.warleysr.dechainer.urge.UrgeEntry): Boolean =
        (e.status in setOf(UrgeStatus.DONE, UrgeStatus.SKIPPED, UrgeStatus.PENDING_DEEPDIVE) ||
            io.github.warleysr.dechainer.urge.UrgeFlowRules.abandoned(e, now())) &&
            (e.kind != UrgeKind.SLIP || measuredOk(e.createdAt))

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
        if (s.endedAt == null || !measuredOk(s.startedAt)) return DeleteResult.NOT_ALLOWED
        focus.delete(id)
        return DeleteResult.DELETED
    }

    fun deleteReport(id: Long): DeleteResult {
        return if (reports.delete(id)) DeleteResult.DELETED else DeleteResult.MISSING
    }

    /** Deletes a monthly or yearly report's text; the row stays so the period is not built again. */
    fun deletePeriodReport(id: Long): DeleteResult =
        if (periodReports.delete(id)) DeleteResult.DELETED else DeleteResult.MISSING

    /** Whether the goal text of [date] may go: a report must cover the day (blueprint 6.4). */
    fun canDeleteGoals(date: LocalDate): Boolean = ReportRules.canDeleteGoals(date, reports.latestEnd(), zone)

    fun deleteGoals(date: LocalDate): DeleteResult {
        if (!canDeleteGoals(date)) return DeleteResult.NOT_ALLOWED
        return if (days.deleteGoals(date) > 0) DeleteResult.DELETED else DeleteResult.MISSING
    }

    /** Wipe all data: everything the rules allow to go. The caller has asked for the recovery code. */
    fun wipeAll(): WipeResult {
        var removed = 0
        var kept = 0
        urges.all().forEach { e ->
            if (e.status.isFinal || e.status == UrgeStatus.PENDING_DEEPDIVE ||
                io.github.warleysr.dechainer.urge.UrgeFlowRules.abandoned(e, now())) {
                if (urgeDeletable(e) && urges.delete(e.id)) removed++ else {
                    urges.erasePrivateText(e.id)
                    kept++ // Keep measured slips, while honoring the owner's text erasure.
                }
            } else kept++ // still in the flow
        }
        focus.sessions().forEach { s ->
            if (s.endedAt != null && measuredOk(s.startedAt)) { focus.delete(s.id); removed++ } else kept++
        }
        days.datesWithGoals().forEach { d -> if (canDeleteGoals(d)) { days.deleteGoals(d); removed++ } else kept++ }
        reports.deleteAll()
        periodReports.deleteAll()
        return WipeResult(removed = removed, kept = kept)
    }

    // ---- export and import ----

    /** The whole store, except `app_state` (activation and rest-day state are never exported). */
    fun export(): String {
        val db = Store.raw(ctx).readableDatabase
        db.beginTransaction()
        try {
            return JSONObject()
                .put("format", FORMAT).put("version", VERSION).put("exportedAt", now())
                .put("weekAnchor", Store.appState(ctx).get(AppStateKeys.WEEK_ANCHOR) ?: JSONObject.NULL)
                .also { o -> TABLES.forEach { t -> o.put(t, dump(db, t)) } }
                .toString().also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }

    /**
     * Reads a file made by [export] back in. It only adds: a row already here is left exactly as it is,
     * so an import can never rewrite a day's recorded result. Days and
     * their goals come back only from before `activatedOn`, reports only if they belong to this store's
     * weeks, and an entry that was mid-flow is kept as a counted stub.
     */
    fun import(json: String): ImportResult {
        if (json.length > MAX_IMPORT_BYTES) return ImportResult(false, 0, 0)
        val root = runCatching { JSONObject(json) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT || root.optInt("version", -1) !in 1..VERSION) return ImportResult(false, 0, 0)

        val state = Store.appState(ctx)
        val fileAnchor = root.optString("weekAnchor", "").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val activated = state.get(AppStateKeys.ACTIVATED_ON)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val today = DayWindow.dateOf(now(), zone)
        val dayLimit = activated ?: today

        var added = 0
        var skipped = 0
        val db = Store.raw(ctx).writableDatabase
        db.beginTransaction()
        try {
            // Import metadata is rolled back together with malformed rows.
            if (fileAnchor != null) db.insertWithOnConflict("app_state", null, ContentValues().apply {
                put("key", AppStateKeys.WEEK_ANCHOR); put("value", fileAnchor.toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val sameWeeks = fileAnchor != null && state.get(AppStateKeys.WEEK_ANCHOR) == fileAnchor.toString()
            fun rows(t: String): List<JSONObject> = root.optJSONArray(t)?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it).also { row -> if (row == null) skipped++ } }
            }.orEmpty()

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
                }); added++
            }

            val newSessions = mutableSetOf<Long>()
            for (r in rows("focus_session")) {
                val id = r.optLong("id", -1)
                if (id < 0 || exists(db, "focus_session", "id = ?", id.toString())) { skipped++; continue }
                val started = r.getLong("started_at")
                val planned = r.getLong("planned_end_at")
                val source = io.github.warleysr.dechainer.focus.FocusSource.entries.firstOrNull { it.name == r.optString("source") }
                val flavor = io.github.warleysr.dechainer.focus.Flavor.entries.firstOrNull { it.name == r.optString("flavor") }
                if (source == null || flavor == null || planned <= started || started > now()) { skipped++; continue }
                val wasRunning = r.isNull("ended_at")
                val ended = (if (wasRunning) minOf(now(), planned) else r.getLong("ended_at")).coerceIn(started, planned)
                val minutes = r.optInt("focused_minutes").coerceIn(0, ((ended - started) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                db.insertOrThrow("focus_session", null, ContentValues().apply {
                    put("id", id); put("source", r.optString("source")); put("flavor", r.optString("flavor"))
                    put("purpose", r.optString("purpose", "")); put("started_at", r.getLong("started_at"))
                    put("planned_end_at", planned); put("ended_at", ended)
                    put("focused_minutes", minutes)
                    put("outcome", if (wasRunning) "ENDED_EARLY_BY_SYSTEM" else r.optNullString("outcome"))
                }); newSessions += id; added++
            }
            for (r in rows("focus_checkin")) {
                if (r.optLong("session_id", -1) !in newSessions) { skipped++; continue }
                db.insertOrThrow("focus_checkin", null, ContentValues().apply {
                    put("session_id", r.getLong("session_id")); put("at", r.getLong("at"))
                    put("answer", r.optString("answer")); put("reset_result", r.optString("reset_result", "NONE"))
                }); added++
            }

            val newDays = mutableSetOf<String>()
            for (r in rows("day")) {
                val date = runCatching { LocalDate.parse(r.optString("date")) }.getOrNull()
                if (date == null || !date.isBefore(dayLimit) || exists(db, "day", "date = ?", date.toString())) { skipped++; continue }
                db.insertOrThrow("day", null, ContentValues().apply {
                    put("date", date.toString()); put("kind", r.optString("kind", "NORMAL")); putLong("plan_written_at", r)
                    putLong("resolved_at", r); put("done_count", r.optInt("done_count")); put("total_count", r.optInt("total_count"))
                    put("evaluated", r.optInt("evaluated")); put("violation", r.optNullString("violation"))
                }); newDays += date.toString(); added++
            }
            for (r in rows("goal")) {
                if (r.optString("day_date") !in newDays) { skipped++; continue }
                db.insertOrThrow("goal", null, ContentValues().apply {
                    put("day_date", r.getString("day_date")); put("position", r.optInt("position")); put("text", r.optString("text"))
                    put("type", r.optString("type", "MANUAL")); putInt("target_minutes", r); put("state", r.optString("state", "OPEN"))
                    put("resolved_by", r.optNullString("resolved_by"))
                }); added++
            }

            for (r in rows("weekly_report")) {
                val index = r.optInt("period_index", Int.MIN_VALUE)
                if (!sameWeeks || index == Int.MIN_VALUE || r.optString("body_md").isBlank() || exists(db, "weekly_report", "period_index = ?", index.toString())) { skipped++; continue }
                db.insertOrThrow("weekly_report", null, ContentValues().apply {
                    put("period_index", index); put("period_start", r.getLong("period_start")); put("period_end", r.getLong("period_end"))
                    put("created_at", r.getLong("created_at")); put("status", "DONE"); put("body_md", r.getString("body_md"))
                    put("summary_json", r.optString("summary_json", ""))
                }); added++
            }
            // Monthly and yearly reports are keyed by the calendar, so they fit any store.
            for (r in rows("period_report")) {
                val kind = PeriodKind.entries.firstOrNull { it.name == r.optString("kind") }
                val key = r.optString("period_key")
                if (kind == null || !PeriodMath.isValid(kind, key) || r.optString("body_md").isBlank() ||
                    exists(db, "period_report", "kind = ? AND period_key = ?", kind.name, key)
                ) { skipped++; continue }
                db.insertOrThrow("period_report", null, ContentValues().apply {
                    put("kind", kind.name); put("period_key", key); put("period_start", r.getLong("period_start")); put("period_end", r.getLong("period_end"))
                    put("created_at", r.getLong("created_at")); put("status", "DONE"); put("body_md", r.getString("body_md"))
                    put("summary_json", r.optString("summary_json", ""))
                }); added++
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // One bad row stops the import and nothing of it is kept.
            return ImportResult(false, 0, 0)
        } finally {
            db.endTransaction()
        }
        Store.changed()
        return ImportResult(true, added, skipped)
    }

    private fun exists(db: SQLiteDatabase, table: String, where: String, vararg args: String): Boolean =
        db.query(table, arrayOf("1"), where, arrayOf(*args), null, null, null, "1").use { it.moveToFirst() }

    private fun dump(db: SQLiteDatabase, table: String): JSONArray = JSONArray().also { out ->
        db.query(table, null, if (table == "weekly_report" || table == "period_report") "status = 'DONE'" else null, null, null, null, null).use { c ->
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
    private fun ContentValues.putInt(key: String, from: JSONObject) { if (from.isNull(key)) putNull(key) else put(key, from.getInt(key)) }

    companion object {
        const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
        const val FORMAT = "dechainer-export"
        const val VERSION = 1
        private val TABLES = listOf("urge_entry", "focus_session", "focus_checkin", "day", "goal", "weekly_report", "period_report")
    }
}
