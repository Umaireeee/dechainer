package io.github.warleysr.dechainer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The debug-only controls (blueprint 14A) must not be in a release build. They live in the debug
 * source set, which a release build does not compile or merge. This pins that structure, from the
 * sources; CI also greps the real merged release manifest.
 */
class DebugControlsTest {
    private fun module(): File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }

    @Test
    fun theMainManifestHasNoDebugActionsOrDebugReceiver() {
        val main = File(module(), "src/main/AndroidManifest.xml").readText()
        assertFalse("DEBUG_" in main)
        assertFalse("DebugControlReceiver" in main)
    }

    @Test
    fun theDebugReceiverAndItsManifestEntryAreInTheDebugSourceSet() {
        val debugManifest = File(module(), "src/debug/AndroidManifest.xml")
        assertTrue(debugManifest.exists())
        assertTrue("DebugControlReceiver" in debugManifest.readText())
        assertTrue(File(module(), "src/debug/java/io/github/warleysr/dechainer/debug/DebugControlReceiver.kt").exists())
    }

    @Test
    fun noMainSourceReferencesTheDebugReceiverOrItsActions() {
        val offenders = File(module(), "src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { "DebugControlReceiver" in it || "DEBUG_ABORT_BRICK" in it || "DEBUG_FOCUS_BLOCK" in it ||
                "DEBUG_URGE_LOCK" in it || "DEBUG_PUNISHMENT_DAY" in it } }
            .map { it.path }.toList()
        assertTrue("main must not know about the debug receiver: $offenders", offenders.isEmpty())
    }

    @Test
    fun theDebugManifestListsTheUrgeLockAndPunishmentDayControls() {
        val manifest = File(module(), "src/debug/AndroidManifest.xml").readText()
        assertTrue("DEBUG_URGE_LOCK" in manifest)
        assertTrue("DEBUG_PUNISHMENT_DAY" in manifest)
    }

    @Test
    fun theDebugEntryPointsInMainRefuseUnlessThisIsADebugBuild() {
        // The few hooks the debug receiver calls live in main code, guarded by BuildConfig.DEBUG.
        val pomodoro = File(module(), "src/main/java/io/github/warleysr/dechainer/focus/Pomodoro.kt").readText()
        assertTrue(Regex("fun debugStartBlock[^{]*\\{\\s*if \\(!BuildConfig\\.DEBUG\\) return false").containsMatchIn(pomodoro))
        assertTrue(Regex("fun debugCancelAlarm[^{]*\\{\\s*if \\(BuildConfig\\.DEBUG\\)").containsMatchIn(pomodoro))
        val engine = File(module(), "src/main/java/io/github/warleysr/dechainer/lock/LockEngine.kt").readText()
        assertTrue(Regex("fun debugDropAlarms[^{]*\\{\\s*if \\(!BuildConfig\\.DEBUG\\) return").containsMatchIn(engine))
        assertTrue(Regex("fun debugStartUrgeLock[^{]*\\{\\s*if \\(!BuildConfig\\.DEBUG\\) return null").containsMatchIn(engine))
        assertTrue(Regex("fun debugStartPunishment[^{]*\\{\\s*if \\(!BuildConfig\\.DEBUG\\) return").containsMatchIn(engine))
    }
}
