package io.github.warleysr.dechainer.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionRuleTest {
    private fun rule(activity: String?) =
        RegionRule("1", "com.whatsapp", "com.whatsapp:id/header", null, "header", activityClass = activity)

    @Test
    fun `rule without an activity applies to every screen of the app`() {
        assertTrue(rule(null).appliesTo("com.whatsapp.HomeActivity"))
        assertTrue(rule(null).appliesTo(null))
    }

    @Test
    fun `rule with an activity only applies on that activity`() {
        val r = rule("com.whatsapp.HomeActivity")
        assertTrue(r.appliesTo("com.whatsapp.HomeActivity"))
        assertFalse(r.appliesTo("com.whatsapp.Conversation"))
        assertFalse(r.appliesTo(null))
    }
}
