package io.github.warleysr.dechainer.urge

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored shapes of `questions_json` and `answers_json`. Reading is forgiving: a stored value
 * that does not parse comes back as null or empty, and nothing is ever written back from a failed read.
 */
object UrgeJson {
    /** A string field, or "" for a missing or JSON null value (optString would give the text "null"). */
    private fun s(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)

    fun questionsToJson(questions: List<Question>): String = JSONArray().also { arr ->
        questions.forEach { q ->
            arr.put(
                JSONObject()
                    .put("id", q.id)
                    .put("type", q.type.name)
                    .put("prompt", q.prompt)
                    .put("options", JSONArray(q.options))
            )
        }
    }.toString()

    fun questionsFromJson(json: String?): List<Question>? {
        if (json.isNullOrBlank()) return null
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val out = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = QuestionType.entries.firstOrNull { it.name == s(o, "type") } ?: return@mapNotNull null
            val prompt = s(o, "prompt").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { (if (a.isNull(it)) "" else a.optString(it)) } }.orEmpty()
            Question(s(o, "id").ifBlank { "q${i + 1}" }, type, prompt, options)
        }
        return out.ifEmpty { null }
    }

    fun answersToJson(answers: List<Answer>): String = JSONArray().also { arr ->
        answers.forEach { a ->
            arr.put(JSONObject().put("id", a.questionId).put("prompt", a.prompt).put("answer", a.answer))
        }
    }.toString()

    fun answersFromJson(json: String?): List<Answer> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Answer(s(o, "id"), s(o, "prompt"), s(o, "answer"))
        }
    }
}
