package io.github.warleysr.dechainer.models

import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupSuggestionsTest {
    private fun app(pkg: String, name: String = pkg, category: Int = ApplicationInfo.CATEGORY_UNDEFINED, launchable: Boolean = true) =
        AppItem(name = name, packageName = pkg, icon = ColorDrawable(0), isSystem = false, isLaunchable = launchable, category = category)

    private fun ids(list: List<GroupSuggestion>) = list.map { it.id }

    @Test
    fun `groups apps by declared category and by well known package`() {
        val apps = listOf(
            app("com.instagram.android"), app("com.reddit.frontpage"),
            app("a.game", category = ApplicationInfo.CATEGORY_GAME), app("b.game", category = ApplicationInfo.CATEGORY_GAME),
            app("c.social", category = ApplicationInfo.CATEGORY_SOCIAL)
        )
        val result = GroupSuggestions.suggest(apps, emptyList(), emptySet())
        assertEquals(listOf("social", "games"), ids(result))
        assertEquals(3, result.first { it.id == "social" }.apps.size)
    }

    @Test
    fun `needs at least two apps`() {
        val result = GroupSuggestions.suggest(listOf(app("com.instagram.android")), emptyList(), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `apps already in a group or without a launcher icon are left out`() {
        val apps = listOf(app("com.instagram.android"), app("com.reddit.frontpage"), app("com.snapchat.android", launchable = false))
        val groups = listOf(AppGroup(id = "g", name = "Mine", packageNames = setOf("com.reddit.frontpage")))
        assertTrue(GroupSuggestions.suggest(apps, groups, emptySet()).isEmpty())
    }

    @Test
    fun `a dismissed suggestion is not offered again`() {
        val apps = listOf(app("com.instagram.android"), app("com.reddit.frontpage"))
        assertTrue(GroupSuggestions.suggest(apps, emptyList(), setOf("social")).isEmpty())
    }

    @Test
    fun `a known package wins over a different declared category`() {
        val apps = listOf(
            app("com.google.android.youtube", category = ApplicationInfo.CATEGORY_SOCIAL),
            app("com.netflix.mediaclient")
        )
        assertEquals(listOf("video"), ids(GroupSuggestions.suggest(apps, emptyList(), emptySet())))
    }
}
