package io.github.warleysr.dechainer.ai

import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One earlier urge or slip, as the next deep dive may see it: when it was, and the two lines of its
 * own deep dive that matter for a pattern (the earliest link, and the plan made for next time). Never
 * the note: by the time a deep dive exists its note is gone, and a note still waiting is not used.
 */
data class PastEntryFact(
    val kind: UrgeKind,
    val at: LocalDateTime,
    val earliestLink: String?,
    val plan: String?
)

/** The recent history a deep dive is written against, so it can name a pattern and follow up on an earlier plan. */
data class DeepDiveHistory(
    /** Urges and slips in the [DeepDiveHistory.WINDOW_DAYS] days before this one. */
    val urges: Int,
    val slips: Int,
    /** The newest few with a deep dive, newest first. */
    val recent: List<PastEntryFact>
) {
    val isEmpty: Boolean get() = urges == 0 && slips == 0

    companion object {
        const val WINDOW_DAYS = 30L
        const val MAX_RECENT = 8
        const val MAX_LINE = 240

        val NONE = DeepDiveHistory(0, 0, emptyList())

        /**
         * Builds the history for an entry made at [at] from [entries] (any order). Only entries before it
         * and inside the window count; [excludeId] (the entry itself) never does. Pure.
         */
        fun from(entries: List<UrgeEntry>, excludeId: Long, at: Long, zone: ZoneId): DeepDiveHistory {
            val from = at - WINDOW_DAYS * 86_400_000L
            val window = entries.filter { it.id != excludeId && it.createdAt in from until at }
            val recent = window
                .filter { !it.deepDive.isNullOrBlank() }
                .sortedByDescending { it.createdAt }
                .take(MAX_RECENT)
                .map { e ->
                    val sections = sections(e.deepDive!!)
                    PastEntryFact(
                        kind = e.kind,
                        at = Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDateTime(),
                        earliestLink = pick(sections, listOf("earliest link"), 1)?.let { clip(it) },
                        plan = pick(sections, listOf("next time"), sections.lastIndex)?.let { clip(it) }
                    )
                }
            return DeepDiveHistory(
                urges = window.count { it.kind == UrgeKind.URGE },
                slips = window.count { it.kind == UrgeKind.SLIP },
                recent = recent
            )
        }

        /** The `## ` sections of a deep dive, as heading to body, in order. */
        fun sections(markdown: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            var heading: String? = null
            val body = StringBuilder()
            fun flush() {
                heading?.let { h -> out += h to body.toString().trim() }
                body.setLength(0)
            }
            for (line in markdown.lines()) {
                val t = line.trim()
                if (t.startsWith("## ")) {
                    flush()
                    heading = t.removePrefix("## ").trim()
                } else if (heading != null) {
                    body.appendLine(t)
                }
            }
            flush()
            return out
        }

        /**
         * The body of the section whose heading names one of [names]; the reply may be in another
         * language, so when none does, the section at [fallbackIndex] of the usual shape is taken.
         */
        private fun pick(sections: List<Pair<String, String>>, names: List<String>, fallbackIndex: Int): String? {
            sections.firstOrNull { (h, _) -> names.any { h.contains(it, ignoreCase = true) } }?.let { return it.second.ifBlank { null } }
            if (sections.size < 4) return null
            return sections.getOrNull(fallbackIndex)?.second?.ifBlank { null }
        }

        private fun clip(text: String) = text.replace(Regex("\\s+"), " ").trim().take(MAX_LINE)
    }
}
