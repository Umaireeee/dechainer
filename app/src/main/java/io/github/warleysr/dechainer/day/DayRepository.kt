package io.github.warleysr.dechainer.day

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.store.DechainerDatabase
import java.time.LocalDate

/**
 * `day` and `goal` (blueprint section 7). Writes are transactions; a row that does not parse is
 * skipped on read and never rewritten. The rules about who may edit what, and when, are in
 * [DayWindow]; callers check them first.
 */
class DayRepository(private val database: DechainerDatabase) {

    /** Today's measured numbers for the auto-resolved goal types. */
    fun stats(zone: java.time.ZoneId): DayStats = object : DayStats {
        override fun focusMinutes(date: LocalDate) = focusMinutesBetween(DayWindow.startOf(date, zone), DayWindow.endOf(date, zone))
        override fun slips(date: LocalDate) = slipsBetween(DayWindow.startOf(date, zone), DayWindow.endOf(date, zone))
    }


    fun day(date: LocalDate): DayRow? =
        database.readableDatabase.query("day", null, "date = ?", arrayOf(date.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) dayRow(c) else null }

    fun restDates(): List<LocalDate> =
        database.readableDatabase.query("day", arrayOf("date"), "kind = 'REST'", null, null, null, null)
            .use { c -> buildList { while (c.moveToNext()) runCatching { LocalDate.parse(c.getString(0)) }.getOrNull()?.let(::add) } }

    fun goals(date: LocalDate): List<Goal> =
        database.readableDatabase.query("goal", null, "day_date = ?", arrayOf(date.toString()), null, null, "position")
            .use { c -> buildList { while (c.moveToNext()) goalRow(c)?.let(::add) } }

    /** Replaces the plan for [date]. Returns false (and writes nothing) unless it has MIN_GOALS..MAX_GOALS non-blank goals. */
    fun savePlan(date: LocalDate, goals: List<NewGoal>, now: Long): Boolean {
        val clean = goals.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        if (clean.size !in DayRules.MIN_GOALS..DayRules.MAX_GOALS) return false
        inTransaction { db ->
            ensureDay(db, date)
            db.delete("goal", "day_date = ?", arrayOf(date.toString()))
            clean.forEachIndexed { i, g ->
                db.insertOrThrow("goal", null, ContentValues().apply {
                    put("day_date", date.toString()); put("position", i); put("text", g.text)
                    put("type", g.type.name); put("target_minutes", g.targetMinutes)
                })
            }
            db.update("day", ContentValues().apply { put("plan_written_at", now); put("total_count", clean.size); put("done_count", 0) },
                "date = ?", arrayOf(date.toString()))
        }
        return true
    }

    fun setGoal(id: Long, state: GoalState, by: ResolvedBy) {
        inTransaction { db ->
            db.update("goal", ContentValues().apply { put("state", state.name); put("resolved_by", by.name.takeIf { state != GoalState.OPEN }) },
                "id = ?", arrayOf(id.toString()))
        }
    }

    /** Declares or withdraws a REST day. Never touches an evaluated row. */
    fun setRest(date: LocalDate, rest: Boolean) {
        inTransaction { db ->
            ensureDay(db, date)
            db.update("day", ContentValues().apply { put("kind", if (rest) "REST" else "NORMAL") },
                "date = ? AND evaluated = 0", arrayOf(date.toString()))
        }
    }

    /** Stores a day's evaluation once. A day already evaluated is left exactly as it is, so re-running changes nothing. */
    fun saveEvaluation(date: LocalDate, verdict: DayVerdict): Boolean {
        var wrote = false
        inTransaction { db ->
            ensureDay(db, date)
            wrote = db.update("day", ContentValues().apply {
                put("kind", verdict.kind.name); put("violation", verdict.violation?.name); put("evaluated", 1)
            }, "date = ? AND evaluated = 0", arrayOf(date.toString())) > 0
        }
        return wrote
    }

    /** Stores the settled goals of a closed day and its counts. */
    fun saveResult(date: LocalDate, goals: List<Goal>, now: Long) {
        inTransaction { db ->
            ensureDay(db, date)
            goals.forEach { g ->
                db.update("goal", ContentValues().apply { put("state", g.state.name); put("resolved_by", g.resolvedBy?.name) },
                    "id = ?", arrayOf(g.id.toString()))
            }
            db.update("day", ContentValues().apply {
                put("done_count", goals.count { it.state == GoalState.DONE }); put("total_count", goals.size)
                put("resolved_at", now)
            }, "date = ? AND resolved_at IS NULL", arrayOf(date.toString()))
        }
    }

    /** Focused minutes of sessions that started in [from, to) (trusted-clock millis). */
    fun focusMinutesBetween(from: Long, to: Long): Int =
        database.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(focused_minutes), 0) FROM focus_session WHERE started_at >= ? AND started_at < ?",
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** SLIP entries created in [from, to). */
    fun slipsBetween(from: Long, to: Long): Int =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM urge_entry WHERE kind = 'SLIP' AND created_at >= ? AND created_at < ?",
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun ensureDay(db: SQLiteDatabase, date: LocalDate) {
        db.insertWithOnConflict("day", null, ContentValues().apply { put("date", date.toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun dayRow(c: Cursor): DayRow? = try {
        DayRow(
            date = LocalDate.parse(c.getString(c.getColumnIndexOrThrow("date"))),
            kind = DayKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
            planWrittenAt = c.getColumnIndexOrThrow("plan_written_at").let { if (c.isNull(it)) null else c.getLong(it) },
            resolvedAt = c.getColumnIndexOrThrow("resolved_at").let { if (c.isNull(it)) null else c.getLong(it) },
            doneCount = c.getInt(c.getColumnIndexOrThrow("done_count")),
            totalCount = c.getInt(c.getColumnIndexOrThrow("total_count")),
            evaluated = c.getInt(c.getColumnIndexOrThrow("evaluated")) == 1,
            violation = c.getString(c.getColumnIndexOrThrow("violation"))?.let { runCatching { Violation.valueOf(it) }.getOrNull() }
        )
    } catch (_: Exception) { null }

    private fun goalRow(c: Cursor): Goal? = try {
        Goal(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            position = c.getInt(c.getColumnIndexOrThrow("position")),
            text = c.getString(c.getColumnIndexOrThrow("text")),
            type = GoalType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
            targetMinutes = c.getColumnIndexOrThrow("target_minutes").let { if (c.isNull(it)) null else c.getInt(it) },
            state = GoalState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
            resolvedBy = c.getString(c.getColumnIndexOrThrow("resolved_by"))?.let { ResolvedBy.valueOf(it) }
        )
    } catch (_: Exception) { null }

    private fun inTransaction(work: (SQLiteDatabase) -> Unit) {
        val db = database.writableDatabase
        db.beginTransaction()
        try { work(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
