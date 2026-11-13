package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.data.AccessibilityWatchdog.ServiceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceListTest {
    private val pkg = "io.github.warleysr.dechainer"
    private val cls = "io.github.warleysr.dechainer.DechainerAccessibilityService"

    @Test
    fun `null, empty and the word null mean no services`() {
        assertTrue(ServiceList.entries(null).isEmpty())
        assertTrue(ServiceList.entries("").isEmpty())
        assertTrue(ServiceList.entries("null").isEmpty())
    }

    @Test
    fun `both spellings of the component are recognised`() {
        assertTrue(ServiceList.isListed("$pkg/$cls", pkg, cls))
        assertTrue(ServiceList.isListed("other/x.Y:$pkg/.DechainerAccessibilityService", pkg, cls))
        assertFalse(ServiceList.isListed("other/x.Y", pkg, cls))
        assertFalse(ServiceList.isListed(null, pkg, cls))
    }

    @Test
    fun `removing only touches our entry`() {
        val list = "other/x.Y:$pkg/.DechainerAccessibilityService:third/z.W"
        assertEquals("other/x.Y:third/z.W", ServiceList.without(list, pkg, cls))
        assertEquals("", ServiceList.without("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `adding keeps others and never duplicates`() {
        assertEquals("$pkg/$cls", ServiceList.with(null, pkg, cls))
        assertEquals("other/x.Y:$pkg/$cls", ServiceList.with("other/x.Y", pkg, cls))
        assertEquals("other/x.Y:$pkg/$cls", ServiceList.with("other/x.Y:$pkg/.DechainerAccessibilityService", pkg, cls))
    }
}
