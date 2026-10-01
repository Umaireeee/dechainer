package io.github.warleysr.dechainer.store

/** One step of the schema: [statements] take a database from version [from] to version [to]. */
class Migration(val from: Int, val to: Int, val statements: List<String>)

/**
 * The schema history (blueprint section 7). Every change to the database is a new entry at the end;
 * an entry that has shipped is never edited. Pure, so the chain can be tested without a database.
 *
 * Version 1 is the skeleton: only `app_state`. The other tables of section 7 arrive with the phase
 * that first needs them, each as its own step here.
 */
object Migrations {
    val ALL: List<Migration> = listOf(
        Migration(
            from = 0, to = 1,
            statements = listOf(
                "CREATE TABLE app_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"
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
