package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.urge.Breathing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cheap guards on the resources, so a missing string is a red unit test rather than a build that
 * fails late: every `R.string`, `R.array` and `R.drawable` the code names exists, every `@string` and
 * `@drawable` the manifest and XML name exists, and the breathing screen has exactly its ten prompts.
 */
class ResourceReferencesTest {
    private fun module(): File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val main get() = File(module(), "src/main")
    private val strings get() = File(main, "res/values/strings.xml").readText()

    private fun names(tag: String) = Regex("<$tag name=\"([^\"]+)\"").findAll(strings).map { it.groupValues[1] }.toList()

    private fun kotlinSources() = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun drawables() = File(main, "res").listFiles { f -> f.name.startsWith("drawable") }.orEmpty()
        .flatMap { it.listFiles().orEmpty().toList() }.map { it.nameWithoutExtension }.toSet()

    @Test
    fun noStringNameIsDefinedTwice() {
        val all = names("string")
        assertEquals(all.groupBy { it }.filter { it.value.size > 1 }.keys.toString(), all.size, all.toSet().size)
    }

    @Test
    fun everyResourceTheCodeNamesExists() {
        val strings = names("string").toSet()
        val arrays = names("string-array").toSet()
        val drawables = drawables()
        val missing = mutableListOf<String>()
        for (file in kotlinSources()) {
            val text = file.readText()
            Regex("R\\.string\\.(\\w+)").findAll(text).forEach { if (it.groupValues[1] !in strings) missing += "${file.name}: string ${it.groupValues[1]}" }
            Regex("R\\.array\\.(\\w+)").findAll(text).forEach { if (it.groupValues[1] !in arrays) missing += "${file.name}: array ${it.groupValues[1]}" }
            Regex("R\\.drawable\\.(\\w+)").findAll(text).forEach { if (it.groupValues[1] !in drawables) missing += "${file.name}: drawable ${it.groupValues[1]}" }
        }
        assertTrue("Missing resources: $missing", missing.isEmpty())
    }

    @Test
    fun everyResourceTheManifestAndXmlNameExists() {
        val strings = names("string").toSet()
        val drawables = drawables()
        val files = listOf(File(main, "AndroidManifest.xml")) + File(main, "res/xml").listFiles().orEmpty().toList()
        val missing = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            Regex("@string/(\\w+)").findAll(text).forEach { if (it.groupValues[1] !in strings) missing += "${file.name}: string ${it.groupValues[1]}" }
            Regex("@drawable/(\\w+)").findAll(text).forEach { if (it.groupValues[1] !in drawables) missing += "${file.name}: drawable ${it.groupValues[1]}" }
        }
        assertTrue("Missing resources: $missing", missing.isEmpty())
    }

    @Test
    fun theBreathingScreenHasExactlyItsTenPrompts() {
        val block = Regex("<string-array name=\"urge_breath_prompts\">([\\s\\S]*?)</string-array>").find(strings)
        assertTrue("urge_breath_prompts is missing", block != null)
        assertEquals(Breathing.PROMPT_COUNT, Regex("<item>").findAll(block!!.groupValues[1]).count())
    }

    @Test
    fun theFixedQuestionsAndTheLockedScreenTextMatchTheBlueprint() {
        assertTrue("What was happening just before the urge started?" in strings)
        assertTrue("How were you feeling, in a word or two?" in strings)
        assertTrue("Where were you, and roughly what time was it?" in strings)
    }
}
