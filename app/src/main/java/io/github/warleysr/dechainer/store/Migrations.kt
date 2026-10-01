package io.github.warleysr.dechainer.store

/** One step of the schema: [statements] take a database from version [from] to version [to]. */
class Migration(val from: Int, val to: Int, val statements: List<String>)

/**
 * The schema history (blueprint section 7). Every change to the database is a new entry at the end;
 * an entry that has shipped is never edited. Pure, so the chain can be tested without a database.
 *
 * Version 1 is the skeleton: only `app_state`. Version 2 adds `urge_entry` (Phase 3). Version 3 adds
 * `focus_session` and `focus_checkin` (Phase 4). The other tables of section 7 arrive with the phase that
 * first needs them, each as its own step here.
 */
object Migrations {
    val ALL: List<Migration> = listOf(
        Migration(
            from = 0, to = 1,
            statements = listOf(
                "CREATE TABLE app_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"
            )
        ),
        // Phase 3: the urge journal (blueprint section 7). Times are trusted-clock epoch millis.
        Migration(
            from = 1, to = 2,
            statements = listOf(
                """CREATE TABLE urge_entry (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    source TEXT NOT NULL,
                    lock_started_at INTEGER,
                    lock_ended_at INTEGER,
                    status TEXT NOT NULL,
                    raw_text TEXT,
                    questions_json TEXT,
                    answers_json TEXT,
                    deep_dive TEXT
                )""",
                "CREATE INDEX urge_entry_status ON urge_entry (status)"
            )
        ),
        // Phase 4: focus sessions and their check-ins (blueprint section 7).
        Migration(
            from = 2, to = 3,
            statements = listOf(
                // `id` is the start time in epoch millis (bumped by one on a clash), so a session keeps the
                // identity the old log gave it: notifications, the log screen and the import all name it.
                """CREATE TABLE focus_session (
                    id INTEGER PRIMARY KEY NOT NULL,
                    source TEXT NOT NULL,
                    flavor TEXT NOT NULL,
                    purpose TEXT NOT NULL DEFAULT '',
                    started_at INTEGER NOT NULL,
                    planned_end_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    focused_minutes INTEGER NOT NULL DEFAULT 0,
                    outcome TEXT
                )""",
                """CREATE TABLE focus_checkin (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    session_id INTEGER NOT NULL,
                    at INTEGER NOT NULL,
                    answer TEXT NOT NULL,
                    reset_result TEXT NOT NULL DEFAULT 'NONE'
                )""",
                "CREATE INDEX focus_checkin_session ON focus_checkin (session_id)",
                "CREATE INDEX focus_session_started ON focus_session (started_at)"
            )
        )
    )

    val LATEST: Int get() = ALL.last().to

    /**
     * The statements that take a database from [from] to [to], in order. Fails loudly when the
     * history has a gap, rather than leaving a half-built schema.
     */
    fun statementsBetween(from: Int, to: Int, all: List<Migration> = ALL): List<String> {
        require(from in 0..to) { "Cannot migrate from $from to $to" }
        val out = mutableListOf<String>()
        var at = from
        while (at < to) {
            val step = all.singleOrNull { it.from == at }
                ?: error("No single migration starts at schema version $at")
            check(step.to > at && step.to <= to) { "Migration $at -> ${step.to} does not fit the target $to" }
            out += step.statements
            at = step.to
        }
        return out
    }

    /** Whether [all] is one unbroken chain 0 -> 1 -> 2 ... with no duplicates. */
    fun isContiguous(all: List<Migration> = ALL): Boolean =
        all.isNotEmpty() && all.withIndex().all { (i, m) -> m.from == i && m.to == i + 1 }
}
