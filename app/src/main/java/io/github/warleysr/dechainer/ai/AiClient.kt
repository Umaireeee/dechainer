package io.github.warleysr.dechainer.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one client the app's network access goes through (blueprint 8). Ported from the journal, with
 * three changes: plain `http://` is refused up front, each call carries its own limits, and a reply
 * cut off by the network is never kept (a deep dive must be whole before the note is deleted).
 */
object AiClient {
    /** The full address of the chat endpoint for a provider's base address. */
    fun endpoint(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** The model to try instead when Google says the chosen one does not exist. */
    const val GOOGLE_FALLBACK_MODEL = "gemini-flash-latest"

    /** A different model worth trying after [error], or null. Only Google, only for a bad model name, and never the same one twice. */
    fun fallbackModel(provider: Provider, model: String, error: AiError): String? =
        if (provider == Provider.GOOGLE && error == AiError.BAD_MODEL &&
            model.trim().removePrefix("models/") != GOOGLE_FALLBACK_MODEL
        ) GOOGLE_FALLBACK_MODEL else null

    /**
     * Why an address cannot be used, or null if it can: only https is accepted, so a key is never
     * sent in the clear. A plain http address is reported as such; anything else that is not a
     * proper address is a bad address, not a network problem.
     */
    fun addressProblem(baseUrl: String): AiError? {
        val url = try {
            URL(endpoint(baseUrl))
        } catch (_: java.net.MalformedURLException) {
            return AiError.BAD_URL
        }
        return when {
            url.protocol == "http" -> AiError.INSECURE_URL
            url.protocol != "https" || url.host.isBlank() -> AiError.BAD_URL
            else -> null
        }
    }

    /** Blocking; call it off the main thread. Retries once with a fallback model when Google rejects the chosen one. */
    fun chat(config: AiConfig, system: String, user: String, limits: AiLimits): AiResult {
        val first = chatOnce(config, system, user, limits)
        if (first is AiResult.Failed) {
            fallbackModel(config.provider, config.model, first.error)
                ?.let { return chatOnce(config.copy(model = it), system, user, limits) }
        }
        return first
    }

    fun buildBody(provider: Provider, model: String, system: String, user: String, maxTokens: Int, stream: Boolean): JSONObject {
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
            body.put("max_completion_tokens", maxTokens)
        } else {
            body.put("max_tokens", maxTokens).put("temperature", 0.6)
        }
        if (stream) body.put("stream", true)
        return body
    }

    private fun open(config: AiConfig, body: JSONObject, limits: AiLimits, stream: Boolean): HttpURLConnection {
        val conn = (URL(endpoint(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = limits.connectTimeoutMs
            // Between pieces of the reply when streaming, so a long answer is not cut off while it is still arriving.
            readTimeout = limits.readTimeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.key}")
            setRequestProperty("Content-Type", "application/json")
            if (stream) setRequestProperty("Accept", "text/event-stream")
            if (config.provider == Provider.OPENROUTER) setRequestProperty("X-Title", "Dechainer")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return conn
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    private fun chatOnce(config: AiConfig, system: String, user: String, limits: AiLimits): AiResult {
        addressProblem(config.baseUrl)?.let { return AiResult.Failed(it) }
        var conn: HttpURLConnection? = null
        return try {
            conn = open(config, buildBody(config.provider, config.model, system, user, limits.maxTokens, stream = false), limits, stream = false)
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { readBounded(it) }.orEmpty()
            conn.disconnect()
            interpret(code, text)
        } catch (_: IOException) {
            AiResult.Failed(AiError.NETWORK)
        } catch (_: RuntimeException) {
            AiResult.Failed(AiError.SERVER)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Like [chat], but the reply is read as it is written: [onText] is called with everything
     * received so far each time more arrives, so the screen can fill in while the model is still
     * writing. Blocking; call it off the main thread. A service that answers with a whole reply
     * instead of a stream is handled too. A stream cut off part-way fails: half a deep dive is never saved.
     */
    fun chatStream(config: AiConfig, system: String, user: String, limits: AiLimits, onText: (String) -> Unit): AiResult {
        val first = streamOnce(config, system, user, limits, onText)
        if (first is AiResult.Failed) {
            fallbackModel(config.provider, config.model, first.error)
                ?.let { return streamOnce(config.copy(model = it), system, user, limits, onText) }
        }
        return first
    }

    private fun streamOnce(config: AiConfig, system: String, user: String, limits: AiLimits, onText: (String) -> Unit): AiResult {
        addressProblem(config.baseUrl)?.let { return AiResult.Failed(it) }
        var conn: HttpURLConnection? = null
        return try {
            conn = open(config, buildBody(config.provider, config.model, system, user, limits.maxTokens, stream = true), limits, stream = true)
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { readBounded(it) }.orEmpty()
                conn.disconnect()
                return interpret(code, text)
            }
            if (!(conn.contentType ?: "").contains("event-stream", ignoreCase = true)) {
                // Not a stream after all: the whole reply is in the body.
                val text = conn.inputStream.bufferedReader().use { readBounded(it) }
                conn.disconnect()
                return interpret(code, text)
            }
            val full = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> Sse.read(reader, onText) }
            conn.disconnect()
            if (full.isBlank()) AiResult.Failed(AiError.EMPTY) else AiResult.Ok(full)
        } catch (e: Sse.StreamFailure) {
            AiResult.Failed(AiError.SERVER, e.message.orEmpty().take(320))
        } catch (_: IOException) {
            AiResult.Failed(AiError.NETWORK)
        } catch (_: RuntimeException) {
            AiResult.Failed(AiError.SERVER)
        } finally {
            conn?.disconnect()
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

    fun content(json: String): String? = runCatching {
        val choice = JSONObject(json).getJSONArray("choices").getJSONObject(0)
        val reason = choice.optString("finish_reason", "")
        if (reason.isNotBlank() && reason != "null" && reason != "stop") null
        else choice.getJSONObject("message").getString("content")
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_REPLY_CHARS }

    const val MAX_REPLY_CHARS = 1_000_000

    private fun readBounded(reader: java.io.Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            if (out.length + n > MAX_REPLY_CHARS) throw IOException("Reply exceeds limit")
            out.append(buffer, 0, n)
        }
        return out.toString()
    }

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

/** Reads a streamed (server-sent events) reply: lines of `data: {json}` ending with `data: [DONE]`. */
object Sse {
    class StreamFailure(message: String) : RuntimeException(message)

    private fun payload(line: String): String? {
        val l = line.trim()
        return if (l.startsWith("data:")) l.removePrefix("data:").trim() else null
    }

    fun isDone(line: String): Boolean = payload(line) == "[DONE]"

    /**
     * The piece of text one line carries, or null for lines that carry none: comments and
     * keep-alives, the first chunk that only names the role, empty pieces, and the end marker.
     */
    fun delta(line: String): String? {
        val data = payload(line)?.takeIf { it.isNotEmpty() && it != "[DONE]" } ?: return null
        val choice = runCatching { JSONObject(data).optJSONArray("choices")?.optJSONObject(0) }.getOrNull() ?: return null
        val piece = choice.optJSONObject("delta")
        val text = when {
            piece != null && !piece.isNull("content") -> piece.optString("content")
            // Some services send the last piece as a whole message.
            choice.optJSONObject("message")?.isNull("content") == false -> choice.optJSONObject("message")?.optString("content")
            else -> null
        }
        return text?.takeIf { it.isNotEmpty() }
    }

    /** The service's own message when a line reports an error in the middle of a stream, else null. */
    fun error(line: String): String? {
        val data = payload(line)?.takeIf { it.startsWith("{") } ?: return null
        val err = runCatching { JSONObject(data).optJSONObject("error") }.getOrNull() ?: return null
        return err.optString("message").ifBlank { "The service reported an error." }
    }

    /** Reads the whole stream. [onText] gets everything written so far after each piece; the full text is returned. */
    fun read(reader: java.io.BufferedReader, onText: (String) -> Unit): String {
        val all = StringBuilder()
        var complete = false
        while (true) {
            val line = reader.readLine() ?: break
            if (isDone(line)) { complete = true; break }
            error(line)?.let { throw StreamFailure(it) }
            val data = payload(line)
            if (!data.isNullOrBlank()) {
                val event = try { JSONObject(data) } catch (_: Exception) { throw StreamFailure("Malformed stream event") }
                val reason = event.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason", "").orEmpty()
                if (reason.isNotBlank() && reason != "null") {
                    if (reason != "stop") throw StreamFailure("Incomplete reply: $reason")
                    complete = true
                }
            }
            val piece = delta(line) ?: continue
            if (all.length + piece.length > AiClient.MAX_REPLY_CHARS) throw StreamFailure("Reply exceeds limit")
            all.append(piece)
            onText(all.toString())
        }
        if (!complete) throw StreamFailure("Stream ended before completion")
        return all.toString()
    }
}
