package io.github.warleysr.dechainer.utils

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegionGeometryTest {
    private val list = Rect(0, 200, 1000, 1700)

    @Test
    fun `covers from the node down to the end of the list`() {
        assertEquals(Rect(0, 900, 1000, 1700), RegionGeometry.below(Rect(40, 900, 900, 960), list))
    }

    @Test
    fun `node scrolled above the list covers the whole list`() {
        assertEquals(list, RegionGeometry.below(Rect(40, 100, 900, 190), list))
    }

    @Test
    fun `node partly above the top is clamped to the list`() {
        assertEquals(Rect(0, 200, 1000, 1700), RegionGeometry.below(Rect(40, 150, 900, 260), list))
    }

    @Test
    fun `node below the list covers nothing`() {
        assertNull(RegionGeometry.below(Rect(40, 1700, 900, 1760), list))
    }

    @Test
    fun `returned rect is a copy`() {
        val area = Rect(list)
        val result = RegionGeometry.below(Rect(40, 100, 900, 190), area)!!
        result.top = 0
        assertEquals(list, area)
    }
}
