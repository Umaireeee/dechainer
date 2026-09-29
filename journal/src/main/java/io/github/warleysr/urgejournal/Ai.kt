package io.github.warleysr.urgejournal

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI providers the journal can talk to. They all speak the same "chat completions" dialect, so
 * one client covers them; only the address, the key's look and the default model differ. The
 * person's own key is used, so there is no server of ours in between.
 */
enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>,
    val keyHint: String
) {
    GOOGLE(
        "Google AI Studio (free)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-3.8-flash",
        listOf("gemini-3.8-flash", "gemini-flash-latest"),
        "aistudio.google.com/apikey"
    ),
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        "deepseek/deepseek-chat",
        listOf("deepseek/deepseek-chat", "google/gemini-2.5-flash", "openai/gpt-4o-mini"),
        "openrouter.ai/keys"
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner"),
        "platform.deepseek.com/api_keys"
    ),
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4.1-mini"),
        "platform.openai.com/api-keys"
    ),
    CUSTOM("Other (OpenAI-compatible)", "", "", emptyList(), "");

    companion object {
        /**
         * Guesses the provider from how a key looks, so pasting one is enough. A plain `sk-` key is
         * ambiguous (DeepSeek, OpenAI and others all use it), so it is left to the person's choice.
         */
        fun detect(key: String): Provider? {
            val k = key.trim()
            return when {
                k.startsWith("sk-or-") -> OPENROUTER
                k.startsWith("AIza") -> GOOGLE
                k.startsWith("sk-proj-") -> OPENAI
                else -> null
            }
        }
    }
}

class AiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /**
     * The API key, kept encrypted with a key that lives in the phone's Keystore and never leaves
     * it. A key saved in plain text by an earlier version is read once and moved over.
     */
    var key: String
        get() {
            prefs.getString("key_enc", null)?.let { return SecretBox.open(it) ?: "" }
            val old = prefs.getString("key", "") ?: ""
            if (old.isNotBlank()) key = old
            return old
        }
        set(v) {
            val clean = v.trim()
            val sealed = if (clean.isEmpty()) null else SecretBox.seal(clean)
            prefs.edit {
                when {
                    clean.isEmpty() -> { remove("key_enc"); remove("key") }
                    sealed != null -> { putString("key_enc", sealed); remove("key") }
                    // No Keystore on this phone: better a working key than none.
                    else -> { remove("key_enc"); putString("key", clean) }
                }
            }
        }

    /** The chosen provider, or the one the key looks like, or OpenRouter as a last resort. */
    var provider: Provider
        get() = Provider.entries.firstOrNull { it.name == prefs.getString("provider", null) }
            ?: Provider.detect(key) ?: Provider.OPENROUTER
        set(v) = prefs.edit { putString("provider", v.name) }

    /** Ask the AI for the long, thorough version (default) rather than a short one. */
    var deep: Boolean
        get() = prefs.getBoolean("deep", true)
        set(v) = prefs.edit { putBoolean("deep", v) }

    /** Show every section of a deep dive at once (default) instead of folding the later ones. */
    var expandAll: Boolean
        get() = prefs.getBoolean("expand_all", true)
        set(v) = prefs.edit { putBoolean("expand_all", v) }

    /** What the person wants coached: their goals and how blunt to be. Added to every request. */
    var about: String
        get() = prefs.getString("about", "") ?: ""
        set(v) = prefs.edit { putString("about", v.trim().take(ABOUT_LIMIT)) }

    /** The address for [Provider.CUSTOM], e.g. https://api.groq.com/openai/v1 */
    var customBase: String
        get() = prefs.getString("custom_base", "") ?: ""
        set(v) = prefs.edit { putString("custom_base", v.trim()) }

    var model: String
        get() = prefs.getString("model", "")?.ifBlank { null } ?: provider.defaultModel
        set(v) = prefs.edit { putString("model", v.trim()) }

    val baseUrl: String get() = if (provider == Provider.CUSTOM) customBase else provider.baseUrl

    /** Ready to call: a key, an address, and a model. */
    val configured: Boolean get() = key.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** Has the person agreed to send their answers to this provider? Changing provider asks again. */
    var consent: Boolean
        get() = prefs.getString("consent_for", null) == provider.name
        set(v) = prefs.edit { if (v) putString("consent_for", provider.name) else remove("consent_for") }
}

const val ABOUT_LIMIT = 600

/** AES-GCM with a key held in the Android Keystore, so the AI key is not readable from a backup or a copy of the files. */
object SecretBox {
    private const val ALIAS = "urge_journal_ai_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypted text safe to store, or null if this phone can't do it. */
    fun seal(plain: String): String? = runCatching {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORM)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** The text [seal] made, or null if it can't be read (a wiped Keystore, for example). */
    fun open(sealed: String): String? = runCatching {
        val bytes = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORM)
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE, key(),
            javax.crypto.spec.GCMParameterSpec(128, bytes, 0, IV_BYTES)
        )
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()
}

/** What went wrong, in terms the person can act on. */
enum class AiError { BAD_KEY, NO_CREDITS, RATE_LIMIT, NETWORK, SERVER, EMPTY, BAD_MODEL }

sealed interface AiResult {
    data class Ok(val text: String) : AiResult
    /** [detail] is the service's own short message, when it sent one, to help find the cause. */
    data class Failed(val error: AiError, val detail: String = "") : AiResult
}

object AiClient {
    /** The full address of the chat endpoint for a provider's base address. */
    fun endpoint(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** Blocking; call it off the main thread. */
    fun chat(provider: Provider, baseUrl: String, key: String, model: String, system: String, user: String): AiResult {
        return try {
            val body = JSONObject()
                .put("model", model.trim().removePrefix("models/"))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
            // OpenAI's newer models take a different name for the cap and refuse a custom temperature.
            if (provider == Provider.OPENAI) {
                body.put("max_completion_tokens", 4096)
            } else {
                body.put("max_tokens", 4096).put("temperature", 0.6)
            }
            val conn = (URL(endpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                if (provider == Provider.OPENROUTER) setRequestProperty("X-Title", "Urge Journal")
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
    fun interpret(code: Int, body: String): AiResult {
        val detail = errorMessage(body)
        return when {
            code == 401 || code == 403 -> AiResult.Failed(AiError.BAD_KEY, detail)
            code == 402 -> AiResult.Failed(AiError.NO_CREDITS, detail)
            code == 429 -> AiResult.Failed(AiError.RATE_LIMIT, detail)
            code == 404 -> AiResult.Failed(AiError.BAD_MODEL, detail)
            // Google answers a bad key with 400, and a bad model name with 400 too.
            code == 400 && detail.contains("api key", ignoreCase = true) -> AiResult.Failed(AiError.BAD_KEY, detail)
            code == 400 && detail.contains("model", ignoreCase = true) -> AiResult.Failed(AiError.BAD_MODEL, detail)
            code >= 500 -> AiResult.Failed(AiError.SERVER, detail)
            code in 200..299 -> content(body)?.let { AiResult.Ok(it) } ?: AiResult.Failed(AiError.EMPTY)
            else -> AiResult.Failed(AiError.SERVER, detail)
        }
    }

    /** Model names that are not for chat, so they are left out of the list. */
    private val notChat = listOf(
        "embed", "tts", "image", "imagen", "aqa", "veo", "whisper", "dall-e", "moderation",
        "transcribe", "audio", "realtime", "vision-preview"
    )

    /**
     * The model names this key can use, or null if the list couldn't be fetched. Model names come
     * and go, so asking the service is more reliable than a list written into the app.
     */
    fun listModels(baseUrl: String, key: String): List<String>? = try {
        val conn = (URL(baseUrl.trim().trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $key")
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code in 200..299) parseModels(text).ifEmpty { null } else null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Pulls chat model names out of a `/models` reply, without the `models/` prefix Google adds. */
    fun parseModels(json: String): List<String> = runCatching {
        val arr = JSONObject(json).getJSONArray("data")
        (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf { id -> id.isNotBlank() } }
            .filter { id -> notChat.none { bad -> id.contains(bad, ignoreCase = true) } }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    fun content(json: String): String? = runCatching {
        JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The service's own error text, whatever shape it came in, trimmed short. */
    fun errorMessage(body: String): String {
        if (body.isBlank()) return ""
        val text = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: runCatching { JSONArray(body).getJSONObject(0).getJSONObject("error").getString("message") }.getOrNull()
        }.getOrNull() ?: runCatching {
            JSONArray(body).getJSONObject(0).getJSONObject("error").getString("message")
        }.getOrNull()
        return (text ?: "").replace('\n', ' ').take(320)
    }
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
- If the person has told you their goals or how they want to be coached ("About them"), tie every suggestion to those goals and follow their wishes on tone.
- Where it fits, protect their real priorities (studies, work, sleep, people) by giving one small, concrete first step they can start within five minutes.
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

    private const val DEPTH_DEEP = """Depth: this person wants a thorough, substantial read, so these lengths replace the shorter ones above. Write at length. "why": 5 to 8 sentences on the psychology and on how THEIR specific answers interact (time, place, feeling, thought, phone location). "right_now": 4 to 6 steps, each with a brief reason. "today": 3 items. "this_week": 3 to 4 items. "long_term": 3 items. "understand": 3 concepts, each 3 to 5 sentences with a practical way to use it. Refer back to details they gave: times, places, their own words, their goals, their history."""

    private const val DEPTH_SHORT = """Depth: keep it tight, so these lengths replace the longer ones above. "why": 2 to 3 sentences. "right_now": 3 steps. "today": 1 to 2. "this_week": 2. "long_term": 2. "understand": 1 concept in 2 sentences."""

    /** The instructions for a deep dive, at the depth the person chose. */
    fun systemFor(deep: Boolean): String = SYSTEM + "\n\n" + if (deep) DEPTH_DEEP else DEPTH_SHORT

    fun user(entry: Entry, digest: String, about: String = ""): String = buildString {
        if (about.isNotBlank()) appendLine("About them (their own words): ${about.trim().take(ABOUT_LIMIT)}")
        appendLine(if (entry.slipped) "This person just SLIPPED (acted on the urge)." else "This person is having an urge right now.")
        appendLine("Local hour: ${java.time.Instant.ofEpochMilli(entry.time).atZone(java.time.ZoneId.systemDefault()).hour}:00")
        Q.entries.forEach { q ->
            entry.answers[q]?.let { appendLine("${Plain.question(q)}: ${Plain.answer(it)}") }
        }
        entry.after?.let { appendLine("After riding it out for ten minutes: ${Plain.after(it)}") }
        if (entry.tried.isNotEmpty()) appendLine("What they tried: ${entry.tried.joinToString(", ") { Plain.step(it) }}")
        if (entry.note.isNotBlank()) {
            appendLine()
            appendLine("In their own words: ${entry.note.trim().take(1200)}")
        }
        if (digest.isNotBlank()) {
            appendLine()
            appendLine("History digest: $digest")
        }
    }

    /** The weekly review's input: counts and patterns only. Notes and reports are never included. */
    fun weeklyUser(entries: List<Entry>, now: Long, about: String = "", zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val week = Insights.week(entries, now, zone)
        return buildString {
            if (about.isNotBlank()) appendLine("About them (their own words): ${about.trim().take(ABOUT_LIMIT)}")
            appendLine("Last 7 days: ${week.total} entries, ${week.resisted} ridden out, ${week.gaveIn} given in to or slipped.")
            week.topFeeling?.let { appendLine("Most common feeling: ${Plain.answer(it)}.") }
            week.peakHour?.let { appendLine("Busiest hour: %02d:00.".format(it)) }
            week.daysSinceGaveIn?.let { appendLine("Days since the last slip: $it.") }
            appendLine("Day by day (oldest first):")
            Insights.days(entries, now, zone).forEach { d ->
                appendLine("- ${d.date.dayOfWeek}: ${d.resisted} ridden out, ${d.gaveIn} given in")
            }
            val month = Insights.summary(entries, now, zone)
            if (month.isNotBlank()) appendLine("Longer view: $month")
        }
    }
}

/** The weekly look back. It reuses the deep dive's shape so the same screen can show it. */
const val WEEKLY_SYSTEM = """You are a calm, direct coach doing a weekly review with one person who is building self-control over compulsive phone use, and over sexual urges when those are part of it. You get counts and patterns from their urge journal, never their private notes. They like depth and want to understand themselves, not be lectured.

Rules:
- Be specific to the numbers you are given. Never write advice that would fit anyone.
- No shame, no moralising, no diagnosis, no promises. A slip is information.
- Name what worked as well as what didn't. If there is too little data, say so plainly and give a small plan to gather more.
- If they gave their goals ("About them"), tie the plan to those goals.

Reply with ONLY one JSON object, no other text, in exactly this shape (the names are fixed; use them as described):
{
 "headline": "one sentence on how the week went",
 "why": "3 to 5 sentences on what the data shows: when, what feelings, what situations, what seemed to help",
 "right_now": ["the one or two things to do first this coming week"],
 "today": [],
 "this_week": ["2 to 3 experiments for the coming week, including one rule written as 'If ..., then ...'"],
 "long_term": ["2 points on the underlying need and how to grow past this"],
 "understand": [{"title": "a concept worth learning", "body": "2 to 3 plain sentences"}],
 "pattern": "the single strongest pattern in their data",
 "encouragement": "one honest sentence, no fluff"
}"""

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
