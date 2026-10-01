package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.DeepDiveSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding the "earliest link" of a deep dive by position, so it works in any language. */
class DeepDiveSectionsTest {
    private val english = """
        ## What happened
        You sat down to study.

        ## The earliest link
        Sitting down without saying what you would do first.
        It was already heavy.

        ## What helped and what didn't
        Nothing yet.
    """.trimIndent()

    @Test
    fun splitsAtHeadingsInOrder() {
        val s = DeepDiveSections.split(english)
        assertEquals(listOf("What happened", "The earliest link", "What helped and what didn't"), s.map { it.heading })
        assertEquals("You sat down to study.", s[0].body)
    }

    @Test
    fun theEarliestLinkIsTheSecondSectionWhateverTheLanguage() {
        val urdu = "## کیا ہوا\nتم بیٹھے تھے۔\n\n## پہلی کڑی\nبغیر منصوبے کے بیٹھنا۔"
        assertEquals("بغیر منصوبے کے بیٹھنا۔", DeepDiveSections.earliestLink(urdu))
        assertEquals("Sitting down without saying what you would do first. It was already heavy.", DeepDiveSections.earliestLink(english))
    }

    @Test
    fun aLongLinkIsCutAndAMissingOneIsNull() {
        val long = "## A\nx\n\n## B\n" + "word ".repeat(200)
        val cut = DeepDiveSections.earliestLink(long, max = 50)!!
        assertTrue(cut.length <= 53 && cut.endsWith("..."))
        assertNull(DeepDiveSections.earliestLink("just a paragraph with no headings"))
        assertNull(DeepDiveSections.earliestLink("## Only one\ntext"))
    }
}
