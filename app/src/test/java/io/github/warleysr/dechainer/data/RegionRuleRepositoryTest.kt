package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.models.RegionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegionRuleRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        RegionRuleRepository.prefs(context).edit { clear() }
    }

    @Test
    fun `rules survive a save and load round trip`() {
        val rules = listOf(
            RegionRule("a", "com.whatsapp", "com.whatsapp:id/header", null, "header", true, "com.whatsapp.HomeActivity"),
            RegionRule("b", "com.example", null, "Reels", "Reels", false, null, invisible = true)
        )
        RegionRuleRepository.save(context, rules)
        assertEquals(rules, RegionRuleRepository.load(context))
    }

    @Test
    fun `rules saved before extendDown and activity existed still load`() {
        RegionRuleRepository.prefs(context).edit {
            putString("rules", """[{"id":"x","pkg":"com.a","viewId":"com.a:id/v","text":"","label":"v"}]""")
        }
        val rule = RegionRuleRepository.load(context).single()
        assertEquals(false, rule.extendDown)
        assertEquals(false, rule.invisible)
        assertNull(rule.activityClass)
    }

    @Test
    fun `rules matching nothing and corrupt data are dropped`() {
        RegionRuleRepository.prefs(context).edit {
            putString("rules", """[{"id":"x","pkg":"com.a","viewId":"","text":"","label":"empty"}]""")
        }
        assertTrue(RegionRuleRepository.load(context).isEmpty())

        RegionRuleRepository.prefs(context).edit { putString("rules", "not json") }
        assertTrue(RegionRuleRepository.load(context).isEmpty())
    }
}
