package io.github.warleysr.dechainer.ai

import io.github.warleysr.dechainer.urge.UrgeKind
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * One earlier urge or slip, as the AI sees it: when it happened and the "earliest link" its own deep
 * dive named. Derived data only (a line the AI wrote earlier), never a raw note. It lets the next
 * questions and deep dive say "this is the third time this week it began like this".
 */
data class PastEntry(val kind: UrgeKind, val at: ZonedDateTime, val earliestLink: String)

/** The sections of a deep dive, in order, without needing to know what language the headings are in. */
object DeepDiveSections {
    private val heading = Regex("^#{1,6}\\s+(.*)$")

    data class Section(val heading: String, val body: String)

    /** Splits Markdown at its `#` headings. Text before the first heading is dropped. */
    fun split(markdown: String): List<Section> {
        val out = mutableListOf<Section>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            title?.let { out += Section(it, body.toString().trim()) }
            body.setLength(0)
        }
        for (line in markdown.lines()) {
            val m = heading.find(line.trim())
            if (m != null) { flush(); title = m.groupValues[1].trim() } else if (title != null) body.appendLine(line)
        }
        flush()
        return out
    }

    /**
     * The "earliest link" of a deep dive. Its position is fixed by the prompt (the second section),
     * so it is found by place, not by heading text, and works in any language. Cut to [max] characters.
     */
    fun earliestLink(markdown: String, max: Int = 220): String? {
        val body = split(markdown).getOrNull(1)?.body?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (body.isEmpty()) return null
        return if (body.length <= max) body else body.take(max).trimEnd() + "..."
    }
}

/** How [PastEntry] items are written into a request. */
object PastEntries {
    /** At most this many earlier entries go into a request. */
    const val MAX = 4

    fun line(p: PastEntry): String {
        val day = p.at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val what = if (p.kind == UrgeKind.SLIP) "slip" else "urge"
        return "- $day ${p.at.toLocalDate()} %02d:%02d, %s. Earliest link then: %s".format(p.at.hour, p.at.minute, what, p.earliestLink)
    }

    /** The block for a request, or null when there is nothing to say. The newest [MAX] entries only. */
    fun block(history: List<PastEntry>): String? {
        val items = history.sortedByDescending { it.at.toInstant() }.take(MAX)
        if (items.isEmpty()) return null
        return AiPrompts.delimit(AiPrompts.HISTORY_TAG, items.joinToString("\n") { line(it) })
    }
}
