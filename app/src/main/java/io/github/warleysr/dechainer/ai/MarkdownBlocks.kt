package io.github.warleysr.dechainer.ai

/** A run of text with its emphasis, for the screen to turn into styled text. */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>) : MdBlock
}

/**
 * Just enough Markdown for the AI's replies: headings, paragraphs, bullet lists, and bold and
 * italic text. Anything else is shown as the plain text it is. No library, no HTML.
 */
object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(spans(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.trim().matches(Regex("<!-- advice:next-(week|month|year) -->")) -> flush()
                line.isBlank() -> flush()
                heading.matches(line) -> {
                    flush()
                    val m = heading.find(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, spans(m.groupValues[2].trim()))
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val text = (bullet.find(line) ?: numbered.find(line))!!.groupValues[1]
                    blocks += MdBlock.Bullet(spans(text.trim()))
                }
                else -> paragraph += line.trim()
            }
        }
        flush()
        return blocks
    }

    /** Splits a line into spans at `**bold**` and `*italic*`. An unmatched marker stays as text. */
    fun spans(line: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        fun emit(bold: Boolean, italic: Boolean) {
            if (buf.isNotEmpty()) out += MdSpan(buf.toString(), bold, italic)
            buf.setLength(0)
        }
        var i = 0
        while (i < line.length) {
            if (line.startsWith("**", i)) {
                val close = line.indexOf("**", i + 2)
                if (close > i + 2) {
                    emit(false, false)
                    out += MdSpan(line.substring(i + 2, close), bold = true)
                    i = close + 2
                    continue
                }
            }
            val c = line[i]
            if (c == '*' && i + 1 < line.length && line[i + 1] != ' ') {
                val close = line.indexOf(c, i + 1)
                if (close > i + 1 && line[close - 1] != ' ') {
                    emit(false, false)
                    out += MdSpan(line.substring(i + 1, close), italic = true)
                    i = close + 1
                    continue
                }
            }
            buf.append(c)
            i++
        }
        emit(false, false)
        return out
    }
}
