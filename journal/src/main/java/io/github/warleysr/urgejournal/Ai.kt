package io.github.warleysr.urgejournal

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI deep dive. It goes through OpenRouter with the person's own key, so there is no server of
 * ours in between. The key and the model name are kept in this app's private storage.
 */
class AiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit { putString("key", v.trim()) }

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = prefs.edit { putString("model", v.trim()) }

    /** Has the person agreed that their answers are sent to OpenRouter to write the report? */
    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(v) = prefs.edit { putBoolean("consent", v) }

    companion object {
        const val DEFAULT_MODEL = "deepseek/deepseek-chat"
    }
}

/** What went wrong, in terms the person can act on. */
enum class AiError { BAD_KEY, NO_CREDITS, RATE_LIMIT, NETWORK, SERVER, EMPTY }

sealed interface AiResult {
    data class Ok(val text: String) : AiResult
    data class Failed(val error: AiError) : AiResult
}

object AiClient {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /** Blocking; call it off the main thread. */
    fun chat(key: String, model: String, system: String, user: String): AiResult {
        return try {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.6)
                .put("max_tokens", 2200)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Title", "Urge Journal")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            interpret(code, text)
        } catch (_: IOException) {
            AiResult.Failed(AiError.NETWORK)
        } catch (_: RuntimeException) {
            AiResult.Failed(AiError.SERVER)
        }
    }

    /** Maps an HTTP status and body to a result. Split out so it can be tested without a network. */
    fun interpret(code: Int, body: String): AiResult = when {
        code == 401 || code == 403 -> AiResult.Failed(AiError.BAD_KEY)
        code == 402 -> AiResult.Failed(AiError.NO_CREDITS)
        code == 429 -> AiResult.Failed(AiError.RATE_LIMIT)
        code >= 500 -> AiResult.Failed(AiError.SERVER)
        code in 200..299 -> content(body)?.let { AiResult.Ok(it) } ?: AiResult.Failed(AiError.EMPTY)
        else -> AiResult.Failed(AiError.SERVER)
    }

    fun content(json: String): String? = runCatching {
        JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Builds what is sent to the model. Only the answers, the optional note and an anonymous digest. */
object Prompt {
    const val SYSTEM = """You are a calm, direct coach helping one person build self-control over compulsive phone use, and over sexual urges when they say those are part of it. They log an urge (or a slip) with structured answers and an optional note. They like depth and want to understand themselves, not be lectured.

Rules:
- Be specific to THEIR answers. Never give generic advice that would fit anyone.
- No shame, no moralising, no religion, no medical or psychological diagnosis, no promises of a cure.
- Explain the mechanism briefly (why this feeling plus this situation produces this urge), then give concrete actions.
- Use well-established, practical methods: urge surfing, changing the environment, implementation intentions ("If X, then Y"), sleep and stress management, and reaching out to people.
- The person's phone can be blocked by a companion app; you may refer to putting distance between them and the phone, but do not invent app features.
- If they already slipped, do not dwell on it. Focus on the next hour, on what the gap in their setup was, and on one specific rule to close it.
- If the note suggests they may hurt themselves or are in crisis, respond only with a short, warm message encouraging them to contact local emergency services or a crisis line, and leave the other fields empty.

Reply with ONLY one JSON object, no other text, in exactly this shape:
{
 "headline": "one sentence naming what is really going on",
 "why": "2 to 4 sentences on the mechanism, specific to their answers",
 "right_now": ["3 to 5 concrete steps for the next 10 to 20 minutes"],
 "today": ["2 to 3 things for the rest of today"],
 "this_week": ["2 to 3 changes, including one rule written as 'If ..., then ...'"],
 "long_term": ["2 to 3 points about the underlying need and how to meet it in a healthier way"],
 "understand": [{"title": "a concept worth learning", "body": "2 to 3 plain sentences"}, {"title": "...", "body": "..."}],
 "pattern": "one observation about their history if the digest shows one, else an empty string",
 "encouragement": "one honest sentence, no fluff"
}"""

    fun user(entry: Entry, digest: String): String = buildString {
        appendLine(if (entry.slipped) "This person just SLIPPED (acted on the urge)." else "This person is having an urge right now.")
        appendLine("Local hour: ${java.time.Instant.ofEpochMilli(entry.time).atZone(java.time.ZoneId.systemDefault()).hour}:00")
        Q.entries.forEach { q ->
            entry.answers[q]?.let { appendLine("${Plain.question(q)}: ${Plain.answer(it)}") }
        }
        if (entry.note.isNotBlank()) {
            appendLine()
            appendLine("In their own words: ${entry.note.trim().take(1200)}")
        }
        if (digest.isNotBlank()) {
            appendLine()
            appendLine("History digest: $digest")
        }
    }
}

/** A parsed deep dive. Every field can be empty; the screen shows what there is. */
data class Report(
    val headline: String,
    val why: String,
    val rightNow: List<String>,
    val today: List<String>,
    val thisWeek: List<String>,
    val longTerm: List<String>,
    val understand: List<Pair<String, String>>,
    val pattern: String,
    val encouragement: String
) {
    val isEmpty: Boolean
        get() = headline.isBlank() && why.isBlank() && rightNow.isEmpty() && today.isEmpty() &&
            thisWeek.isEmpty() && longTerm.isEmpty() && understand.isEmpty()
}

object ReportParser {
    /** Pulls the JSON object out of the reply, tolerating code fences and stray text around it. */
    fun parse(text: String): Report? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        fun list(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        }
        val understand = o.optJSONArray("understand")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val item = a.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                val body = item.optString("body").trim()
                if (title.isEmpty() && body.isEmpty()) null else title to body
            }
        }.orEmpty()
        val report = Report(
            headline = o.optString("headline").trim(),
            why = o.optString("why").trim(),
            rightNow = list("right_now"),
            today = list("today"),
            thisWeek = list("this_week"),
            longTerm = list("long_term"),
            understand = understand,
            pattern = o.optString("pattern").trim(),
            encouragement = o.optString("encouragement").trim()
        )
        return report.takeUnless { it.isEmpty && it.encouragement.isBlank() }
    }
}

/** A blunt check on the free-text note, so a person in crisis gets care instead of a report. */
object Safety {
    private val markers = listOf(
        "kill myself", "end my life", "want to die", "suicide", "suicidal", "hurt myself",
        "harm myself", "self harm", "self-harm", "don't want to live", "dont want to live", "no reason to live"
    )

    fun needsSupport(note: String): Boolean {
        val t = note.lowercase()
        return markers.any { it in t }
    }
}
