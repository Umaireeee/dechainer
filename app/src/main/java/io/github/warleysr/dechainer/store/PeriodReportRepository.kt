package io.github.warleysr.dechainer.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.warleysr.dechainer.report.PeriodKind

/** A `period_report` row: one monthly or yearly report. A deleted one has status DELETED and no text. */
data class StoredPeriodReport(
    val id: Long,
    val kind: PeriodKind,
    val key: String,
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
 * `period_report`: the monthly and yearly reports. One row per kind and calendar period, enforced by a
 * unique index, so two jobs can never both save one. Saved whole in one transaction; a deleted report
 * keeps a bare row so the period is not built again. Reads never write.
 */
class PeriodReportRepository(private val database: DechainerDatabase) {

    /** Saves a report. False (and nothing written) if the period already has a row or the text is empty. */
    fun insert(kind: PeriodKind, key: String, periodStart: Long, periodEnd: Long, createdAt: Long, bodyMd: String, summaryJson: String): Boolean {
        if (bodyMd.isBlank()) return false
        var ok = false
        inTransaction { db ->
            val id = db.insertWithOnConflict("period_report", null, ContentValues().apply {
                put("kind", kind.name); put("period_key", key); put("period_start", periodStart); put("period_end", periodEnd)
                put("created_at", createdAt); put("status", WeeklyReportRepository.DONE); put("body_md", bodyMd); put("summary_json", summaryJson)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            ok = id != -1L
        }
        return ok
    }

    fun get(id: Long): StoredPeriodReport? =
        database.readableDatabase.query("period_report", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    /** Every row of [kind], newest period first, deleted ones included. */
    fun all(kind: PeriodKind): List<StoredPeriodReport> =
        database.readableDatabase.query("period_report", null, "kind = ?", arrayOf(kind.name), null, null, "period_key DESC")
            .use { c -> buildList { while (c.moveToNext()) read(c)?.let(::add) } }

    /** The keys of [kind] that have a row, deleted or not. */
    fun reportedKeys(kind: PeriodKind): Set<String> = all(kind).mapTo(mutableSetOf()) { it.key }

    /** The newest [limit] reports of [kind] before [key] that still exist, oldest first. Keys sort by date. */
    fun before(kind: PeriodKind, key: String, limit: Int): List<StoredPeriodReport> =
        all(kind).filter { !it.deleted && it.key < key }.take(limit).reversed()

    /** Deletes a report's text. The row stays, so the period is not built again. */
    fun delete(id: Long): Boolean {
        var n = 0
        inTransaction {
            n = it.update("period_report", ContentValues().apply {
                put("status", WeeklyReportRepository.DELETED); put("body_md", ""); put("summary_json", "")
            }, "id = ?", arrayOf(id.toString()))
        }
        return n > 0
    }

    /** Wipe: every report's text goes, every row stays. */
    fun deleteAll() {
        inTransaction {
            it.update("period_report", ContentValues().apply {
                put("status", WeeklyReportRepository.DELETED); put("body_md", ""); put("summary_json", "")
            }, null, null)
        }
    }

    private fun read(c: Cursor): StoredPeriodReport? = try {
        StoredPeriodReport(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            kind = PeriodKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
            key = c.getString(c.getColumnIndexOrThrow("period_key")),
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
}
