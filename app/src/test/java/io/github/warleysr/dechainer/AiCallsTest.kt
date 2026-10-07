package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiLimits
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.DeepDiveInput
import io.github.warleysr.dechainer.ai.MarkdownOutcome
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.ai.QuestionsOutcome
import io.github.warleysr.dechainer.ai.QuestionsReply
import io.github.warleysr.dechainer.urge.CrisisContact
import io.github.warleysr.dechainer.urge.UrgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The glue between a request, one client call and the reply parser, with the client replaced by a fake. */
class AiCallsTest {
    private val cfg = AiConfig(Provider.OPENAI, "https://api.openai.com/v1", "key", "m")
    private val input = DeepDiveInput(UrgeKind.URGE, ZonedDateTime.of(2026, 9, 29, 23, 5, 0, 0, ZoneId.of("UTC")), "note", emptyList(), false)
    private val threeQuestions = """{"support":false,"questions":[
        {"id":"a","type":"text","prompt":"A?"},{"id":"b","type":"text","prompt":"B?"},{"id":"c","type":"text","prompt":"C?"}]}"""

    private class Seen { var system = ""; var user = ""; var limits: AiLimits? = null }

    private fun calls(seen: Seen = Seen(), reply: AiResult, streamPieces: List<String> = emptyList()) = AiCalls(
        chat = { _, s, u, l -> seen.system = s; seen.user = u; seen.limits = l; reply },
        stream = { _, s, u, l, on -> seen.system = s; seen.user = u; seen.limits = l; streamPieces.forEach(on); reply }
    )

    @Test
    fun questionsAreRequestedWithTheTwentySecondLimitAndParsed() {
        val seen = Seen()
        val out = calls(seen, AiResult.Ok(threeQuestions)).generateQuestions(cfg, UrgeKind.URGE, "my note")
        assertEquals(AiLimits.QUESTIONS, seen.limits)
        assertTrue("my note" in seen.user)
        assertEquals(3, ((out as QuestionsOutcome.Reply).reply as QuestionsReply.Questions).list.size)
    }

    @Test
    fun theSupportReplyComesThrough() {
        val out = calls(reply = AiResult.Ok("""{"support":true,"questions":[]}""")).generateQuestions(cfg, UrgeKind.URGE, "x")
        assertEquals(QuestionsOutcome.Reply(QuestionsReply.Support), out)
    }

    @Test
    fun malformedQuestionsAreAReplyThatIsUnusableNotAFailure() {
        val out = calls(reply = AiResult.Ok("sorry, no")).generateQuestions(cfg, UrgeKind.URGE, "x")
        assertEquals(QuestionsOutcome.Reply(QuestionsReply.Unusable), out)
    }

    @Test
    fun aFailedQuestionsCallKeepsItsError() {
        val out = calls(reply = AiResult.Failed(AiError.NETWORK)).generateQuestions(cfg, UrgeKind.URGE, "x")
        assertEquals(QuestionsOutcome.Failed(AiError.NETWORK), out)
    }

    @Test
    fun theDeepDiveStreamsAndComesBackAsCleanMarkdown() {
        val seen = Seen()
        val shown = mutableListOf<String>()
        val out = calls(seen, AiResult.Ok("```markdown\n## What happened\nYou sat.\n```"), listOf("## What", "## What happened"))
            .deepDive(cfg, input) { shown += it }
        assertEquals(MarkdownOutcome.Ok("## What happened\nYou sat."), out)
        assertEquals(listOf("## What", "## What happened"), shown)
        assertEquals(AiLimits.DEEP_DIVE, seen.limits)
    }

    @Test
    fun aDeepDiveThatIsJsonOrEmptyIsAFailureSoTheNoteStays() {
        assertEquals(MarkdownOutcome.Failed(AiError.EMPTY), calls(reply = AiResult.Ok("{\"a\":1}")).deepDive(cfg, input))
        assertEquals(MarkdownOutcome.Failed(AiError.EMPTY), calls(reply = AiResult.Ok("  ")).deepDive(cfg, input))
        assertEquals(MarkdownOutcome.Failed(AiError.SERVER), calls(reply = AiResult.Failed(AiError.SERVER)).deepDive(cfg, input))
    }

    // ---- the crisis contact ----

    @Test
    fun aNumberKeepsItsDigitsAndALeadingPlus() {
        assertEquals("+15550102030", CrisisContact.cleanNumber(" +1 (555) 010-2030 "))
        assertEquals("5550102030", CrisisContact.cleanNumber("555-010 2030"))
        assertEquals("1555", CrisisContact.cleanNumber("1+555"))
        assertEquals("", CrisisContact.cleanNumber("call me"))
    }

    @Test
    fun aContactNeedsANumberAndANameIsCapped() {
        assertFalse(CrisisContact.of("Sam", "").usable)
        assertTrue(CrisisContact.of("", "123").usable)
        assertEquals(CrisisContact.MAX_NAME, CrisisContact.of("n".repeat(500), "1").name.length)
    }
}
