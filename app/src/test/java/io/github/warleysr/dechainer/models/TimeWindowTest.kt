package io.github.warleysr.dechainer.models

import io.github.warleysr.dechainer.data.AppTimeWindows
import io.github.warleysr.dechainer.screens.common.isLooserWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeWindowTest {
    private val monday = 1
    private val tuesday = 2
    private val sunday = 7
    private val weekdays = setOf(1, 2, 3, 4, 5)

    @Test
    fun `no windows means no restriction`() {
        assertNull(TimeWindow.allowedSpans(listOf(emptyList(), emptyList()), monday))
        assertTrue(TimeWindow.isAllowed(emptyList(), monday, 3 * 60))
    }

    @Test
    fun `window only applies on its days and is closed the rest of the week`() {
        val windows = listOf(TimeWindow(18 * 60, 22 * 60, weekdays))
        assertTrue(TimeWindow.isAllowed(windows, monday, 19 * 60))
        assertFalse(TimeWindow.isAllowed(windows, monday, 17 * 60))
        assertFalse(TimeWindow.isAllowed(windows, sunday, 19 * 60))
        assertEquals(emptyList<Span>(), TimeWindow.allowedSpans(listOf(windows), sunday))
    }

    @Test
    fun `night window spills into the next day`() {
        val windows = listOf(TimeWindow(22 * 60, 2 * 60, setOf(5))) // Friday 22:00 -> Saturday 02:00
        assertTrue(TimeWindow.isAllowed(windows, 5, 23 * 60))
        assertTrue(TimeWindow.isAllowed(windows, 6, 60))
        assertFalse(TimeWindow.isAllowed(windows, 6, 3 * 60))
        assertFalse(TimeWindow.isAllowed(windows, 5, 60))
    }

    @Test
    fun `disabled windows are ignored and do not restrict`() {
        val windows = listOf(TimeWindow(18 * 60, 22 * 60, enabled = false))
        assertNull(TimeWindow.allowedSpans(listOf(windows), monday))
    }

    @Test
    fun `app and group windows must both allow the minute`() {
        val group = listOf(TimeWindow(9 * 60, 17 * 60))
        val app = listOf(TimeWindow(12 * 60, 20 * 60))
        val spans = TimeWindow.allowedSpans(listOf(group, app), monday)
        assertEquals(listOf(Span(12 * 60, 17 * 60)), spans)
    }

    @Test
    fun `a restricting source with nothing today closes the app even if the other allows`() {
        val group = listOf(TimeWindow(0, 1440 - 1, setOf(sunday)))
        val app = listOf(TimeWindow(9 * 60, 17 * 60))
        assertEquals(emptyList<Span>(), TimeWindow.allowedSpans(listOf(group, app), monday))
    }

    @Test
    fun `touching spans are merged`() {
        val windows = listOf(TimeWindow(8 * 60, 12 * 60), TimeWindow(12 * 60, 15 * 60))
        assertEquals(listOf(Span(8 * 60, 15 * 60)), TimeWindow.allowedSpans(listOf(windows), tuesday))
    }

    @Test
    fun `old json without days and flag loads as every day enabled and round trips`() {
        val old = """[{"s":480,"e":600}]"""
        val windows = AppTimeWindows.decode(old)
        assertEquals(listOf(TimeWindow(480, 600)), windows)
        assertEquals(old, AppTimeWindows.encode(windows))

        val custom = listOf(TimeWindow(60, 120, setOf(6, 7), enabled = false))
        assertEquals(custom, AppTimeWindows.decode(AppTimeWindows.encode(custom)))
    }

    @Test
    fun `allowing a new day is loosening but removing a day is not`() {
        val old = listOf(TimeWindow(9 * 60, 17 * 60, weekdays))
        assertTrue(isLooserWindows(old, listOf(TimeWindow(9 * 60, 17 * 60, weekdays + sunday))))
        assertFalse(isLooserWindows(old, listOf(TimeWindow(9 * 60, 17 * 60, setOf(1, 2)))))
        assertTrue(isLooserWindows(old, emptyList()))
    }
}
