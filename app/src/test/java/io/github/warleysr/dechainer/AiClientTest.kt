package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.AiClient
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGate
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiLimits
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.ai.Sse
import io.github.warleysr.dechainer.ai.needsOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client ported from the journal: providers, addresses, reply handling and streaming, none of it touching a network. */
class AiClientTest {
    @Test
    fun aPastedKeyPicksTheProvider() {
        assertEquals(Provider.OPENROUTER, Provider.detect("sk-or-v1-abcdef"))
        assertEquals(Provider.GOOGLE, Provider.detect("AIzaSyExample"))
        assertEquals(Provider.OPENAI, Provider.detect("sk-proj-abc"))
        assertNull(Provider.detect("sk-plain123")) // DeepSeek and others also use this shape
        assertNull(Provider.detect("something-else"))
        assertNull(Provider.detect(""))
    }

    @Test
    fun endpointsAreBuiltFromTheBaseAddress() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiClient.endpoint(Provider.OPENROUTER.baseUrl))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiClient.endpoint(Provider.GOOGLE.baseUrl)
        )
        assertEquals("https://api.groq.com/openai/v1/chat/completions", AiClient.endpoint(" https://api.groq.com/openai/v1/ "))
        assertEquals("https://api.deepseek.com/chat/completions", AiClient.endpoint(Provider.DEEPSEEK.baseUrl))
    }

    @Test
    fun everyRealProviderIsHttpsAndHasADefaultModel() {
        for (p in Provider.entries.filter { it != Provider.CUSTOM }) {
            assertTrue(p.baseUrl.startsWith("https://"))
            assertTrue(p.defaultModel.isNotBlank())
            assertNull("${p.name} must pass the address check", AiClient.addressProblem(p.baseUrl))
        }
    }

    // ---- plain http is refused up front ----

    @Test
    fun aPlainHttpAddressIsRefusedAsInsecureBeforeAnyKeyLeavesThePhone() {
        assertEquals(AiError.INSECURE_URL, AiClient.addressProblem("http://example.com/v1"))
        assertEquals(AiError.INSECURE_URL, AiClient.addressProblem(" HTTP://example.com "))
        // And the call itself answers at once, without opening a connection.
        val cfg = AiConfig(Provider.CUSTOM, "http://192.168.1.5:8080/v1", "key", "model")
        assertEquals(AiResult.Failed(AiError.INSECURE_URL), AiClient.chat(cfg, "s", "u", AiLimits.QUESTIONS))
        assertEquals(AiResult.Failed(AiError.INSECURE_URL), AiClient.chatStream(cfg, "s", "u", AiLimits.DEEP_DIVE) { })
    }

    @Test
    fun anAddressThatIsNotAnAddressIsABadAddress() {
        assertEquals(AiError.BAD_URL, AiClient.addressProblem("not a url"))
        assertEquals(AiError.BAD_URL, AiClient.addressProblem(""))
        assertEquals(AiError.BAD_URL, AiClient.addressProblem("ftp://example.com"))
        assertEquals(AiError.BAD_URL, AiClient.addressProblem("https://"))
        val cfg = AiConfig(Provider.CUSTOM, "not a url", "key", "model")
        assertEquals(AiResult.Failed(AiError.BAD_URL), AiClient.chat(cfg, "s", "u", AiLimits.QUESTIONS))
    }

    @Test
    fun anHttpsAddressPasses() {
        assertNull(AiClient.addressProblem("https://api.groq.com/openai/v1"))
        assertNull(AiClient.addressProblem("https://localhost:8443/v1"))
    }

    // ---- replies ----

    @Test
    fun serviceErrorsKeepTheirOwnMessage() {
        val google = """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}]"""
        val r = AiClient.interpret(400, google) as AiResult.Failed
        assertEquals(AiError.BAD_KEY, r.error)
        assertTrue("API key not valid" in r.detail)

        val openrouter = """{"error":{"message":"No endpoints found for nope/model","code":404}}"""
        val m = AiClient.interpret(404, openrouter) as AiResult.Failed
        assertEquals(AiError.BAD_MODEL, m.error)
        assertTrue("nope/model" in m.detail)

        assertEquals(AiError.BAD_MODEL, (AiClient.interpret(400, """{"error":{"message":"Unknown model: Gemini"}}""") as AiResult.Failed).error)
    }

    @Test
    fun statusCodesMapToWhatTheOwnerCanDo() {
        assertEquals(AiError.BAD_KEY, (AiClient.interpret(401, "") as AiResult.Failed).error)
        assertEquals(AiError.BAD_KEY, (AiClient.interpret(403, "") as AiResult.Failed).error)
        assertEquals(AiError.NO_CREDITS, (AiClient.interpret(402, "") as AiResult.Failed).error)
        assertEquals(AiError.RATE_LIMIT, (AiClient.interpret(429, "") as AiResult.Failed).error)
        assertEquals(AiError.SERVER, (AiClient.interpret(503, "") as AiResult.Failed).error)
        assertEquals(AiError.SERVER, (AiClient.interpret(418, "") as AiResult.Failed).error)
    }

    @Test
    fun aGoodReplyIsItsMessageContentAndAnEmptyOneIsAFailure() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""
        assertEquals(AiResult.Ok("hello"), AiClient.interpret(200, ok))
        assertEquals(AiResult.Failed(AiError.EMPTY), AiClient.interpret(200, """{"choices":[{"message":{"content":"  "}}]}"""))
        assertEquals(AiResult.Failed(AiError.EMPTY), AiClient.interpret(200, "garbage"))
        assertEquals(AiResult.Failed(AiError.EMPTY), AiClient.interpret(200, """{"choices":[]}"""))
    }

    @Test
    fun theErrorTextIsFoundWhateverShapeItCameIn() {
        assertEquals("a", AiClient.errorMessage("""{"error":{"message":"a"}}"""))
        assertEquals("b", AiClient.errorMessage("""{"message":"b"}"""))
        assertEquals("c", AiClient.errorMessage("""[{"error":{"message":"c"}}]"""))
        assertEquals("", AiClient.errorMessage(""))
        assertEquals("", AiClient.errorMessage("<html>"))
        assertEquals(320, AiClient.errorMessage("""{"message":"${"x".repeat(900)}"}""").length)
    }

    @Test
    fun onlyGoogleFallsBackAndOnlyForABadModelNameAndNeverToTheSameModel() {
        assertEquals("gemini-flash-latest", AiClient.fallbackModel(Provider.GOOGLE, "gemini-9", AiError.BAD_MODEL))
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "gemini-flash-latest", AiError.BAD_MODEL))
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "models/gemini-flash-latest", AiError.BAD_MODEL))
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "gemini-9", AiError.NETWORK))
        assertNull(AiClient.fallbackModel(Provider.OPENAI, "x", AiError.BAD_MODEL))
    }

    @Test
    fun theRequestBodyCarriesTheModelTheMessagesAndTheCap() {
        val b = AiClient.buildBody(Provider.DEEPSEEK, " models/m ", "sys", "usr", 777, stream = false)
        assertEquals("m", b.getString("model"))
        assertEquals(777, b.getInt("max_tokens"))
        assertFalse(b.has("stream"))
        assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("usr", b.getJSONArray("messages").getJSONObject(1).getString("content"))
        val o = AiClient.buildBody(Provider.OPENAI, "gpt", "s", "u", 500, stream = true)
        assertEquals(500, o.getInt("max_completion_tokens"))
        assertFalse(o.has("temperature"))
        assertTrue(o.getBoolean("stream"))
    }

    @Test
    fun theQuestionsCallIsCutOffAtTwentySeconds() {
        assertEquals(45_000, AiLimits.QUESTIONS.readTimeoutMs)
        assertTrue(AiLimits.QUESTIONS.connectTimeoutMs < AiLimits.QUESTIONS.readTimeoutMs)
    }

    // ---- which errors time can fix ----

    @Test
    fun onlyTheOwnerCanFixAKeyACreditAModelOrAnAddress() {
        val owner = setOf(AiError.BAD_KEY, AiError.NO_CREDITS, AiError.BAD_MODEL, AiError.BAD_URL, AiError.INSECURE_URL)
        for (e in AiError.entries) assertEquals(e.name, e in owner, e.needsOwner)
    }

    // ---- the gate ----

    @Test
    fun nothingLeavesThePhoneWithoutAKeyConsentAndANetwork() {
        assertEquals(AiGateResult.NO_KEY, AiGate.check(configured = false, consent = true, online = true))
        assertEquals(AiGateResult.NO_CONSENT, AiGate.check(configured = true, consent = false, online = true))
        assertEquals(AiGateResult.OFFLINE, AiGate.check(configured = true, consent = true, online = false))
        assertEquals(AiGateResult.OPEN, AiGate.check(configured = true, consent = true, online = true))
        // No key wins over everything, so the offline phone with no key is told about the key.
        assertEquals(AiGateResult.NO_KEY, AiGate.check(configured = false, consent = false, online = false))
    }

    @Test
    fun aConfigIsReadyOnlyWithAKeyAnAddressAndAModel() {
        assertTrue(AiConfig(Provider.OPENAI, "https://a", "k", "m").configured)
        assertFalse(AiConfig(Provider.OPENAI, "https://a", " ", "m").configured)
        assertFalse(AiConfig(Provider.CUSTOM, "", "k", "m").configured)
        assertFalse(AiConfig(Provider.OPENAI, "https://a", "k", "").configured)
    }

    // ---- streaming ----

    private fun sse(vararg lines: String) = java.io.BufferedReader(java.io.StringReader(lines.joinToString("\n")))

    @Test
    fun aStreamedReplyIsReadPieceByPieceAndIgnoresNoise() {
        val shown = mutableListOf<String>()
        val text = Sse.read(
            sse(
                """data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                """data: {"choices":[{"delta":{"content":"## What"}}]}""",
                ": OPENROUTER PROCESSING",
                "",
                """data: {"choices":[{"delta":{"content":" happened"}}]}""",
                """data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}""",
                "data: [DONE]",
                """data: {"choices":[{"delta":{"content":"never read"}}]}"""
            )
        ) { shown += it }
        assertEquals("## What happened", text)
        // The screen is told everything written so far, each time more arrives.
        assertEquals(listOf("## What", "## What happened"), shown)
    }

    @Test
    fun aServiceThatSendsTheLastPieceAsAWholeMessageStillWorks() {
        assertEquals("all of it", Sse.delta("""data: {"choices":[{"message":{"role":"assistant","content":"all of it"}}]}"""))
        assertNull(Sse.delta("data: not json"))
        assertNull(Sse.delta("event: ping"))
        assertTrue(Sse.isDone("data: [DONE]"))
        assertFalse(Sse.isDone("data: {}"))
    }

    @Test
    fun anErrorInTheMiddleOfAStreamIsReportedNotSwallowed() {
        val failure = try {
            Sse.read(sse("""data: {"choices":[{"delta":{"content":"ab"}}]}""", """data: {"error":{"message":"rate limited"}}""")) { }
            null
        } catch (e: Sse.StreamFailure) {
            e.message
        }
        assertEquals("rate limited", failure)
    }

    @Test
    fun aReplyThatDidNotEndOnPurposeFailsInsteadOfBeingKeptHalfWritten() {
        fun failure(vararg lines: String) = try { Sse.read(sse(*lines)) { }; null } catch (e: Sse.StreamFailure) { e.message }
        val piece = """data: {"choices":[{"delta":{"content":"## What happened\nYou sat"}}]}"""
        assertEquals("The reply was cut off (length).", failure(piece, """data: {"choices":[{"delta":{},"finish_reason":"length"}]}""", "data: [DONE]"))
        assertEquals("The reply was cut off (content_filter).", failure(piece, """data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""))
        assertEquals("a connection that just closes is not a finished reply", "The reply ended before it was finished.", failure(piece))
        // Ended on purpose, with [DONE] or with stop: kept.
        assertEquals("## What happened\nYou sat", Sse.read(sse(piece, "data: [DONE]")) { })
        assertEquals("## What happened\nYou sat", Sse.read(sse(piece, """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")) { })
        assertEquals("length", Sse.finishReason("""data: {"choices":[{"finish_reason":"length"}]}"""))
        assertNull(Sse.finishReason("""data: {"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""))
        assertEquals(AiResult.Failed(AiError.SERVER, "The reply was cut off or filtered."),
            AiClient.interpret(200, """{"choices":[{"message":{"content":"half"},"finish_reason":"length"}]}"""))
        assertEquals(AiResult.Failed(AiError.SERVER, "The reply was cut off or filtered."),
            AiClient.interpret(200, """{"choices":[{"message":{"content":"x"},"finish_reason":"content_filter"}]}"""))
        assertEquals(AiResult.Ok("whole"), AiClient.interpret(200, """{"choices":[{"message":{"content":"whole"},"finish_reason":"stop"}]}"""))
    }
}
