package io.github.warleysr.dechainer.ai

/**
 * The AI providers the app can talk to. They all speak the same "chat completions" dialect, so one
 * client covers them; only the address, the key's look and the default model differ. The owner's own
 * key is used, so there is no server of ours in between. Ported from the journal (blueprint 8).
 */
enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String
) {
    GOOGLE("Google AI Studio (free)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", "aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat", "openrouter.ai/keys"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat", "platform.deepseek.com/api_keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com/api-keys"),
    CUSTOM("Other (OpenAI-compatible)", "", "", "");

    companion object {
        /**
         * Guesses the provider from how a key looks, so pasting one is enough. A plain `sk-` key is
         * ambiguous (DeepSeek, OpenAI and others all use it), so it is left to the owner's choice.
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

/** Everything a call needs. Built from [AiSettings] on the phone and by hand in tests. */
data class AiConfig(val provider: Provider, val baseUrl: String, val key: String, val model: String) {
    val configured: Boolean get() = key.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

/** What went wrong, in terms the owner can act on. */
enum class AiError { BAD_KEY, NO_CREDITS, RATE_LIMIT, NETWORK, SERVER, EMPTY, BAD_MODEL, BAD_URL, INSECURE_URL }

/** Errors that only the owner can fix (a refused key, no credit, a wrong model or address): trying again changes nothing. */
val AiError.needsOwner: Boolean
    get() = this == AiError.BAD_KEY || this == AiError.NO_CREDITS || this == AiError.BAD_MODEL ||
        this == AiError.BAD_URL || this == AiError.INSECURE_URL

sealed interface AiResult {
    data class Ok(val text: String) : AiResult

    /** [detail] is the service's own short message, when it sent one, to help find the cause. */
    data class Failed(val error: AiError, val detail: String = "") : AiResult
}

/** How long a call may take and how much it may write. */
data class AiLimits(val maxTokens: Int, val connectTimeoutMs: Int, val readTimeoutMs: Int) {
    companion object {
        /** Section 6.2: if the questions take longer than 20 seconds the fixed ones are shown, so the call is cut off there too. */
        val QUESTIONS = AiLimits(maxTokens = 4_000, connectTimeoutMs = 10_000, readTimeoutMs = 45_000)
        val DEEP_DIVE = AiLimits(maxTokens = 2_500, connectTimeoutMs = 15_000, readTimeoutMs = 60_000)
        val WEEKLY = AiLimits(maxTokens = 6_000, connectTimeoutMs = 15_000, readTimeoutMs = 120_000)
    }
}

/** Why a call is, or is not, allowed to go out. */
enum class AiGateResult { OPEN, NO_KEY, NO_CONSENT, OFFLINE }

/**
 * Whether the AI may be called right now (blueprint 8). Nothing leaves the phone without a key, the
 * owner's consent for this provider, and a network; otherwise the fixed questions and the retry job
 * take over. The urge itself never waits on any of it.
 */
object AiGate {
    fun check(configured: Boolean, consent: Boolean, online: Boolean): AiGateResult = when {
        !configured -> AiGateResult.NO_KEY
        !consent -> AiGateResult.NO_CONSENT
        !online -> AiGateResult.OFFLINE
        else -> AiGateResult.OPEN
    }
}
