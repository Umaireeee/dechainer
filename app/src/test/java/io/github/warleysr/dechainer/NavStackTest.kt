package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.viewmodels.NavStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    private val roots = setOf("focus", "apps", "schedules", "config")
    private fun stack(list: MutableList<String> = ArrayList()) = NavStack(list, "home") { it in roots }

    @Test
    fun theAppOpensOnHome() {
        val s = stack()
        assertEquals("home", s.current)
        assertTrue(s.atHome)
        assertFalse(s.goBack())
    }

    @Test
    fun aRootScreenOpensOnTopOfHomeSoBackGoesHome() {
        val s = stack()
        s.navigateTo("focus")
        assertEquals("focus", s.current)
        assertTrue(s.goBack())
        assertEquals("home", s.current)
    }

    @Test
    fun choosingAnotherRootReplacesTheFirstInsteadOfStackingIt() {
        val list = ArrayList<String>()
        val s = stack(list)
        s.navigateTo("focus")
        s.navigateTo("apps")
        assertEquals(listOf("home", "apps"), list)
    }

    @Test
    fun aScreenOpenedFromARootGoesBackToThatRoot() {
        val s = stack()
        s.navigateTo("schedules")
        s.navigateTo("schedule_editor")
        assertEquals("schedule_editor", s.current)
        assertTrue(s.goBack())
        assertEquals("schedules", s.current)
        assertTrue(s.goBack())
        assertTrue(s.atHome)
    }

    @Test
    fun choosingHomeClearsEverythingAbove() {
        val list = ArrayList<String>()
        val s = stack(list)
        s.navigateTo("config")
        s.navigateTo("restrictions")
        s.navigateTo("home")
        assertEquals(listOf("home"), list)
    }

    @Test
    fun openingTheScreenYouAreOnDoesNotStackItTwice() {
        val list = ArrayList<String>()
        val s = stack(list)
        s.navigateTo("config")
        s.navigateTo("restrictions")
        s.navigateTo("restrictions")
        assertEquals(listOf("home", "config", "restrictions"), list)
    }

    @Test
    fun backNeverPopsHome() {
        val list = ArrayList<String>()
        val s = stack(list)
        repeat(3) { assertFalse(s.goBack()) }
        assertEquals(listOf("home"), list)
    }
}
