package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** A `weekly_report` row. A deleted report has [status] [WeeklyReportRepository.DELETED] and no text. */
data class StoredReport(
    val id: Long,
    val periodIndex: Int,
    val periodStart: Long,
    val periodEnd: Long,
    val createdAt: Long,
    val status: String,
    val bodyMd: String,
    val summaryJson: String
) {
    val deleted: Boolean get() = status == WeeklyReportRepository.DELETED
}

/**
 * `weekly_report` (blueprint section 7). One row per period, enforced by a unique index, so two jobs
 * can never both save a report for the same week. A report is saved whole in one transaction; a
 * deleted report keeps a bare row so the week is not built again. Reads never write.
 */
class WeeklyReportRepository(private val database: DechainerDatabase) {

    /** Saves a report. False (and nothing written) if the period already has a row. */
    fun insert(periodIndex: Int, periodStart: Long, periodEnd: Long, createdAt: Long, bodyMd: String, summaryJson: String): Boolean {
        if (bodyMd.isBlank()) return false
        var ok = false
        inTransaction { db ->
            val id = db.insertWithOnConflict("weekly_report", null, ContentValues().apply {
                put("period_index", periodIndex); put("period_start", periodStart); put("period_end", periodEnd)
                put("created_at", createdAt); put("status", DONE); put("body_md", bodyMd); put("summary_json", summaryJson)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            ok = id != -1L
        }
        return ok
    }

    fun get(id: Long): StoredReport? =
        database.readableDatabase.query("weekly_report", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    /** Every row, newest period first, deleted ones included. */
    fun all(): List<StoredReport> =
        database.readableDatabase.query("weekly_report", null, null, null, null, null, "period_index DESC")
            .use { c -> buildList { while (c.moveToNext()) read(c)?.let(::add) } }

    /** The periods that have a row, deleted or not. */
    fun reportedPeriods(): Set<Int> =
        database.readableDatabase.rawQuery("SELECT period_index FROM weekly_report", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getInt(0)) } }

    /** The end of the newest period that has a row, deleted or not, or null. Deleting a report never lowers it. */
    fun latestEnd(): Long? =
        database.readableDatabase.rawQuery("SELECT MAX(period_end) FROM weekly_report", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** The newest [limit] reports before [periodIndex] that still exist, oldest first. */
    fun before(periodIndex: Int, limit: Int): List<StoredReport> =
        database.readableDatabase.query(
            "weekly_report", null, "period_index < ? AND status = ?", arrayOf(periodIndex.toString(), DONE),
            null, null, "period_index DESC", limit.toString()
        ).use { c -> buildList { while (c.moveToNext()) read(c)?.let(::add) } }.reversed()

    /** Deletes a report's text. The row stays, so the week is not built again. */
    fun delete(id: Long): Boolean {
        var n = 0
        inTransaction {
            n = it.update("weekly_report", ContentValues().apply {
                put("status", DELETED); put("body_md", ""); put("summary_json", "")
            }, "id = ?", arrayOf(id.toString()))
        }
        return n > 0
    }

    /** Wipe: every report's text goes, every row stays. */
    fun deleteAll() {
        inTransaction {
            it.update("weekly_report", ContentValues().apply { put("status", DELETED); put("body_md", ""); put("summary_json", "") }, null, null)
        }
    }

    private fun read(c: Cursor): StoredReport? = try {
        StoredReport(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            periodIndex = c.getInt(c.getColumnIndexOrThrow("period_index")),
            periodStart = c.getLong(c.getColumnIndexOrThrow("period_start")),
            periodEnd = c.getLong(c.getColumnIndexOrThrow("period_end")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            bodyMd = c.getString(c.getColumnIndexOrThrow("body_md")),
            summaryJson = c.getString(c.getColumnIndexOrThrow("summary_json"))
        )
    } catch (_: Exception) { null }

    private fun inTransaction(work: (SQLiteDatabase) -> Unit) {
        val db = database.writableDatabase
        db.beginTransaction()
        try { work(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    companion object {
        const val DONE = "DONE"
        const val DELETED = "DELETED"
    }
}
