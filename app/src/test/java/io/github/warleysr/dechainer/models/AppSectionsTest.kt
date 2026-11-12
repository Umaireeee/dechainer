package io.github.warleysr.dechainer.models

import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AppSectionsTest {
    private fun app(
        name: String,
        system: Boolean = false,
        launchable: Boolean = true,
        suspended: Boolean = false,
        limit: Int = 0,
        windows: List<TimeWindow> = emptyList()
    ) = AppItem(
        name = name, packageName = "pkg.${name.lowercase()}", icon = ColorDrawable(0),
        isSystem = system, isSuspended = suspended, isLaunchable = launchable,
        timeLimit = TimeLimit.daily(limit), timeWindows = windows
    )

    private fun minutes(m: Long) = TimeUnit.MINUTES.toMillis(m)
    private fun names(list: List<AppItem>?) = list.orEmpty().map { it.name }

    @Test
    fun `system apps that are really used are ranked with user apps`() {
        val apps = listOf(app("YouTube", system = true), app("Reddit"), app("Notes"), app("Calculator", system = true))
        val usage = mapOf("pkg.youtube" to minutes(300), "pkg.reddit" to minutes(120), "pkg.notes" to minutes(3))

        val sections = AppSections.build(apps, usage, emptySet())

        assertEquals(listOf("YouTube", "Reddit"), names(sections[AppSection.MOST_USED]))
        assertEquals(listOf("Notes"), names(sections[AppSection.RARELY_USED]))
        assertEquals(listOf("Calculator"), names(sections[AppSection.SYSTEM_TOOLS]))
    }

    @Test
    fun `controlled apps come first whatever their usage and even without a launcher icon`() {
        val apps = listOf(app("Hidden", launchable = false, suspended = true), app("Limited", limit = 30), app("Plain"))
        val usage = mapOf("pkg.plain" to minutes(500))

        val sections = AppSections.build(apps, usage, emptySet())

        assertEquals(listOf("Hidden", "Limited"), names(sections[AppSection.CONTROLLED]).sorted())
        assertEquals(listOf("Plain"), names(sections[AppSection.MOST_USED]))
    }

    @Test
    fun `apps with no launcher icon are left out unless controlled`() {
        val apps = listOf(app("WebView", system = true, launchable = false), app("Maps"))
        val sections = AppSections.build(apps, mapOf("pkg.maps" to minutes(60)), emptySet())
        assertFalse(sections.values.flatten().any { it.name == "WebView" })
    }

    @Test
    fun `members of a controlled group are controlled`() {
        val sections = AppSections.build(listOf(app("Insta")), emptyMap(), setOf("pkg.insta"))
        assertEquals(listOf("Insta"), names(sections[AppSection.CONTROLLED]))
    }

    @Test
    fun `disabled schedules do not count as control`() {
        val off = listOf(TimeWindow(60, 120, enabled = false))
        val sections = AppSections.build(listOf(app("Quiet", windows = off)), emptyMap(), emptySet())
        assertTrue(AppSection.CONTROLLED !in sections)
    }

    @Test
    fun `without usage access everything is listed alphabetically in one section`() {
        val apps = listOf(app("Zed"), app("Alpha", system = true), app("Mid"))
        val sections = AppSections.build(apps, null, emptySet())
        assertEquals(setOf(AppSection.ALL_APPS), sections.keys)
        assertEquals(listOf("Alpha", "Mid", "Zed"), names(sections[AppSection.ALL_APPS]))
    }

    @Test
    fun `unused user apps go to never used and empty sections are dropped`() {
        val sections = AppSections.build(listOf(app("Idle")), emptyMap(), emptySet())
        assertEquals(setOf(AppSection.NEVER_USED), sections.keys)
    }
}
