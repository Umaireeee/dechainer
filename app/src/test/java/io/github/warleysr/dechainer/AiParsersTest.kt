package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.MarkdownBlocks
import io.github.warleysr.dechainer.ai.MarkdownReply
import io.github.warleysr.dechainer.ai.MdBlock
import io.github.warleysr.dechainer.ai.MdSpan
import io.github.warleysr.dechainer.ai.QuestionsParser
import io.github.warleysr.dechainer.ai.QuestionsReply
import io.github.warleysr.dechainer.urge.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers for the model's replies, including the malformed ones. */
class AiParsersTest {
    private fun questions(reply: QuestionsReply) = (reply as QuestionsReply.Questions).list

    private val good = """{"support": false, "questions": [
        {"id": "q1", "type": "choice", "prompt": "Where was the phone?", "options": ["In bed", "On the desk"]},
        {"id": "q2", "type": "text", "prompt": "What did the argument start with?"},
        {"id": "q3", "type": "scale", "prompt": "How strong was it at 23:05?"}]}"""

    @Test
    fun aWellFormedReplyGivesItsQuestionsInOrder() {
        val q = questions(QuestionsParser.parse(good))
        assertEquals(listOf("q1", "q2", "q3"), q.map { it.id })
        assertEquals(listOf(QuestionType.CHOICE, QuestionType.TEXT, QuestionType.SCALE), q.map { it.type })
        assertEquals(listOf("In bed", "On the desk"), q[0].options)
        assertEquals(emptyList<String>(), q[1].options)
    }

    @Test
    fun aReplyInCodeFencesOrWithTalkAroundItIsStillRead() {
        assertEquals(3, questions(QuestionsParser.parse("```json\n$good\n```")).size)
        assertEquals(3, questions(QuestionsParser.parse("Sure! Here you go:\n$good\nHope that helps.")).size)
    }

    @Test
    fun theSupportReplyShowsTheSupportCardWhateverElseItSays() {
        assertEquals(QuestionsReply.Support, QuestionsParser.parse("""{"support": true, "questions": []}"""))
        assertEquals(QuestionsReply.Support, QuestionsParser.parse("""{"support": true}"""))
        assertEquals(QuestionsReply.Support, QuestionsParser.parse("```json\n{\"support\":true}\n```"))
        // Even if it also sent questions, a flagged note gets the card.
        assertEquals(QuestionsReply.Support, QuestionsParser.parse(good.replace("\"support\": false", "\"support\": true")))
    }

    @Test
    fun garbageEmptyAndWrongShapesAreUnusable() {
        for (bad in listOf("", "no json here", "{", "}{", "[1,2,3]", "{\"questions\": 5}", "{\"questions\": []}", "{\"support\": false}", "{not json}")) {
            assertEquals("[$bad]", QuestionsReply.Unusable, QuestionsParser.parse(bad))
        }
    }

    @Test
    fun fewerThanThreeUsableQuestionsAreUnusable() {
        val two = """{"support":false,"questions":[{"id":"a","type":"text","prompt":"One?"},{"id":"b","type":"text","prompt":"Two?"}]}"""
        assertEquals(QuestionsReply.Unusable, QuestionsParser.parse(two))
        // A blank prompt does not count.
        val blank = """{"questions":[{"type":"text","prompt":"One?"},{"type":"text","prompt":"  "},{"type":"text","prompt":"Three?"}]}"""
        assertEquals(QuestionsReply.Unusable, QuestionsParser.parse(blank))
    }

    @Test
    fun moreThanFiveAreCutToFive() {
        val seven = (1..7).joinToString(",", "{\"support\":false,\"questions\":[", "]}") { """{"id":"q$it","type":"text","prompt":"Question $it?"}""" }
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), questions(QuestionsParser.parse(seven)).map { it.id })
    }

    @Test
    fun aChoiceWithoutRealOptionsBecomesAText() {
        val r = """{"questions":[
            {"id":"a","type":"choice","prompt":"A?"},
            {"id":"b","type":"choice","prompt":"B?","options":["only one"]},
            {"id":"c","type":"choice","prompt":"C?","options":["x","x"]}]}"""
        val q = questions(QuestionsParser.parse(r))
        assertEquals(listOf(QuestionType.TEXT, QuestionType.TEXT, QuestionType.TEXT), q.map { it.type })
        assertTrue(q.all { it.options.isEmpty() })
    }

    @Test
    fun anUnknownTypeIsAText() {
        val r = """{"questions":[{"id":"a","type":"essay","prompt":"A?"},{"id":"b","prompt":"B?"},{"id":"c","type":"SCALE","prompt":"C?"}]}"""
        assertEquals(listOf(QuestionType.TEXT, QuestionType.TEXT, QuestionType.SCALE), questions(QuestionsParser.parse(r)).map { it.type })
    }

    @Test
    fun missingAndDuplicateIdsAreMadeUnique() {
        val r = """{"questions":[{"id":"a","type":"text","prompt":"A?"},{"id":"a","type":"text","prompt":"B?"},{"type":"text","prompt":"C?"}]}"""
        val ids = questions(QuestionsParser.parse(r)).map { it.id }
        assertEquals(3, ids.distinct().size)
        assertEquals("a", ids[0])
    }

    @Test
    fun aHugePromptAndOptionsAreCapped() {
        val r = """{"questions":[{"id":"a","type":"choice","prompt":"${"p".repeat(1000)}","options":["${"o".repeat(500)}","b","c","d","e","f","g","h"]},
            {"type":"text","prompt":"B?"},{"type":"text","prompt":"C?"}]}"""
        val q = questions(QuestionsParser.parse(r))[0]
        assertEquals(300, q.prompt.length)
        assertEquals(6, q.options.size)
        assertEquals(60, q.options[0].length)
    }

    @Test
    fun theTypeOfAnOptionOrPromptThatIsNotAStringDoesNotCrashTheParser() {
        val r = """{"questions":[{"id":5,"type":7,"prompt":"A?","options":[1,2]},{"prompt":"B?"},{"prompt":"C?"}]}"""
        assertEquals(3, questions(QuestionsParser.parse(r)).size)
    }

    // ---- Markdown replies ----

    @Test
    fun aMarkdownReplyIsKeptAndTrimmed() {
        assertEquals("## A\nbody", MarkdownReply.clean("\n\n## A\nbody\n  "))
    }

    @Test
    fun aFenceAroundTheWholeReplyIsTakenOff() {
        assertEquals("## A\nbody", MarkdownReply.clean("```markdown\n## A\nbody\n```"))
        assertEquals("## A\nbody", MarkdownReply.clean("```\n## A\nbody\n```"))
    }

    @Test
    fun anEmptyReplyOrJsonWhereMarkdownWasAskedForIsUnusable() {
        assertNull(MarkdownReply.clean(""))
        assertNull(MarkdownReply.clean("   \n "))
        assertNull(MarkdownReply.clean("{\"headline\":\"x\"}"))
        assertNull(MarkdownReply.clean("```json\n{\"a\":1}\n```"))
    }

    @Test
    fun wordsAreCounted() {
        assertEquals(0, MarkdownReply.wordCount(""))
        assertEquals(4, MarkdownReply.wordCount("## One two\nthree  four"))
    }

    // ---- the Markdown the screen shows ----

    @Test
    fun headingsParagraphsAndBulletsBecomeBlocks() {
        val blocks = MarkdownBlocks.parse("## What happened\nYou sat\ndown late.\n\n- First\n* Second\n1. Third\n\nEnd")
        assertEquals(
            listOf(
                MdBlock.Heading(2, listOf(MdSpan("What happened"))),
                MdBlock.Paragraph(listOf(MdSpan("You sat down late."))),
                MdBlock.Bullet(listOf(MdSpan("First"))),
                MdBlock.Bullet(listOf(MdSpan("Second"))),
                MdBlock.Bullet(listOf(MdSpan("Third"))),
                MdBlock.Paragraph(listOf(MdSpan("End")))
            ),
            blocks
        )
    }

    @Test
    fun boldAndItalicBecomeSpansAndUnmatchedMarkersStayAsText() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("b", bold = true), MdSpan(" c "), MdSpan("d", italic = true)),
            MarkdownBlocks.spans("a **b** c *d*")
        )
        assertEquals(listOf(MdSpan("2 * 3 = 6 and **open")), MarkdownBlocks.spans("2 * 3 = 6 and **open"))
        assertEquals(listOf(MdSpan("right_now stays")), MarkdownBlocks.spans("right_now stays"))
    }

    @Test
    fun emptyMarkdownHasNoBlocks() {
        assertEquals(emptyList<MdBlock>(), MarkdownBlocks.parse(""))
        assertEquals(emptyList<MdBlock>(), MarkdownBlocks.parse("\n\n   \n"))
    }
}
