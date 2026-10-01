package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.QuestionType
import io.github.warleysr.dechainer.urge.Safety
import io.github.warleysr.dechainer.urge.UrgeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgeJsonTest {
    @Test
    fun questionsSurviveAStoreAndLoad() {
        val q = listOf(
            Question("q1", QuestionType.CHOICE, "Where were you?", listOf("Bed", "Desk")),
            Question("q2", QuestionType.TEXT, "What came before?"),
            Question("q3", QuestionType.SCALE, "How strong?")
        )
        assertEquals(q, UrgeJson.questionsFromJson(UrgeJson.questionsToJson(q)))
    }

    @Test
    fun aStoredValueThatDoesNotParseIsNullNotAnError() {
        assertNull(UrgeJson.questionsFromJson(null))
        assertNull(UrgeJson.questionsFromJson(""))
        assertNull(UrgeJson.questionsFromJson("not json"))
        assertNull(UrgeJson.questionsFromJson("{\"a\":1}"))
        assertNull(UrgeJson.questionsFromJson("[]"))
        assertEquals(emptyList<Answer>(), UrgeJson.answersFromJson("not json"))
        assertEquals(emptyList<Answer>(), UrgeJson.answersFromJson(null))
    }

    @Test
    fun aQuestionWithAnUnknownTypeOrNoPromptIsDroppedAndTheRestKept() {
        val json = """[{"id":"a","type":"WHAT","prompt":"x"},{"id":"b","type":"TEXT","prompt":"  "},{"id":"c","type":"TEXT","prompt":"ok"}]"""
        assertEquals(listOf("c"), UrgeJson.questionsFromJson(json)?.map { it.id })
    }

    @Test
    fun answersSurviveAStoreAndLoadIncludingQuotesAndNewlines() {
        val a = listOf(Answer("q1", "Where?", "In \"bed\"\nlate"), Answer("q2", "Why?", ""))
        assertEquals(a, UrgeJson.answersFromJson(UrgeJson.answersToJson(a)))
    }

    @Test
    fun theCrisisCheckCatchesTheObviousAndLeavesOrdinaryNotesAlone() {
        assertTrue(Safety.needsSupport("I want to die"))
        assertTrue(Safety.needsSupport("I think about suicide"))
        assertTrue(Safety.needsSupport("I'm going to hurt myself"))
        assertFalse(Safety.needsSupport("I scrolled for two hours and feel bad"))
        assertFalse(Safety.needsSupport(""))
    }

    @Test
    fun theCrisisCheckHandlesCurlyApostrophesAndCase() {
        assertTrue(Safety.needsSupport("I don’t want to live like this"))
        assertTrue(Safety.needsSupport("I CAN’T GO ON"))
        assertTrue(Safety.needsSupport("I wish I were dead"))
        assertTrue(Safety.needsSupport("self-harm"))
    }
}
