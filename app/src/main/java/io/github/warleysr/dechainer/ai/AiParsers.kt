package io.github.warleysr.dechainer.ai

import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.QuestionType
import org.json.JSONObject

/** What the questions call answered with. */
sealed interface QuestionsReply {
    /** The note shows risk: show the support card instead of questions. */
    data object Support : QuestionsReply

    /** 3 to 5 usable questions. */
    data class Questions(val list: List<Question>) : QuestionsReply

    /** Malformed, too few questions, or nothing usable: the fixed questions are shown instead. */
    data object Unusable : QuestionsReply
}

/** Reads the reply to [AiPrompts.questions]. Lenient about fences and stray text, strict about what it accepts. */
object QuestionsParser {
    private const val MAX_PROMPT_CHARS = 300
    private const val MAX_OPTION_CHARS = 60
    private const val MAX_OPTIONS = 6

    fun parse(text: String): QuestionsReply {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return QuestionsReply.Unusable
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return QuestionsReply.Unusable
        if (o.optBoolean("support", false)) return QuestionsReply.Support
        val arr = o.optJSONArray("questions") ?: return QuestionsReply.Unusable
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Question>()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val prompt = q.optString("prompt").trim().take(MAX_PROMPT_CHARS)
            if (prompt.isEmpty()) continue
            val options = q.optJSONArray("options")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).trim().take(MAX_OPTION_CHARS).takeIf { s -> s.isNotEmpty() } }
            }.orEmpty().distinct().take(MAX_OPTIONS)
            val type = when (q.optString("type").trim().lowercase()) {
                "choice" -> if (options.size >= 2) QuestionType.CHOICE else QuestionType.TEXT
                "scale" -> QuestionType.SCALE
                else -> QuestionType.TEXT
            }
            var id = q.optString("id").trim().take(40).ifEmpty { "q${out.size + 1}" }
            if (id in seen) id = "q${out.size + 1}"
            while (id in seen) id += "x"
            seen += id
            out += Question(id, type, prompt, if (type == QuestionType.CHOICE) options else emptyList())
            if (out.size == Rules.MAX_AI_QUESTIONS) break
        }
        return if (out.size >= Rules.MIN_AI_QUESTIONS) QuestionsReply.Questions(out) else QuestionsReply.Unusable
    }
}

/** Reads the Markdown reply of the deep dive. */
object MarkdownReply {
    /**
     * The reply as clean Markdown, or null when it cannot be used: empty, or a JSON object where
     * Markdown was asked for. A code fence around the whole reply is taken off.
     */
    fun clean(text: String): String? {
        var t = text.trim()
        val fence = Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```$").find(t)
        if (fence != null) t = fence.groupValues[1].trim()
        if (t.isEmpty() || t.startsWith("{")) return null
        return t
    }
}
