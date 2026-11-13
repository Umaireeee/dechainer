package io.github.warleysr.dechainer.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DowntimeTest {
    private val utc = ZoneId.of("UTC")
    private fun millis(dt: LocalDateTime) = dt.atZone(utc).toInstant().toEpochMilli()
    private fun rule(vararg windows: TimeWindow, pkgs: Set<String> = setOf("a"), groups: Set<String> = emptySet()) =
        DowntimeRule(name = "r", packageNames = pkgs, groupIds = groups, windows = windows.toList())

    // 2026-11-13 is a Friday.
    private val friday = LocalDateTime.of(2026, 11, 13, 12, 0)

    @Test
    fun `suspends only inside the window and on its days`() {
        val rules = listOf(rule(TimeWindow(9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5))))
        assertEquals(setOf("a"), Downtime.suspendedAt(rules, emptyList(), 1, 10 * 60))
        assertEquals(emptySet<String>(), Downtime.suspendedAt(rules, emptyList(), 1, 18 * 60))
        assertEquals(emptySet<String>(), Downtime.suspendedAt(rules, emptyList(), 7, 10 * 60))
    }

    @Test
    fun `a night window covers late evening and the next morning`() {
        val rules = listOf(rule(TimeWindow(22 * 60, 7 * 60)))
        assertEquals(setOf("a"), Downtime.suspendedAt(rules, emptyList(), 3, 23 * 60))
        assertEquals(setOf("a"), Downtime.suspendedAt(rules, emptyList(), 4, 6 * 60))
        assertEquals(emptySet<String>(), Downtime.suspendedAt(rules, emptyList(), 4, 8 * 60))
    }

    @Test
    fun `group members are included and a disabled slot suspends nothing`() {
        val groups = listOf(AppGroup(id = "g", name = "Social", packageNames = setOf("x", "y")))
        val on = listOf(rule(TimeWindow(0, 1439), pkgs = emptySet(), groups = setOf("g")))
        assertEquals(setOf("x", "y"), Downtime.suspendedAt(on, groups, 2, 100))
        val off = listOf(rule(TimeWindow(0, 1439, enabled = false), pkgs = setOf("a")))
        assertEquals(emptySet<String>(), Downtime.suspendedAt(off, groups, 2, 100))
    }

    @Test
    fun `next boundary is the closest start or end after now`() {
        val rules = listOf(rule(TimeWindow(18 * 60, 20 * 60)))
        assertEquals(millis(friday.withHour(18).withMinute(0)), Downtime.nextBoundaryMillis(rules, friday, utc))
        val inside = friday.withHour(19)
        assertEquals(millis(friday.withHour(20).withMinute(0)), Downtime.nextBoundaryMillis(rules, inside, utc))
    }

    @Test
    fun `next boundary rolls over to the following day and week`() {
        val rules = listOf(rule(TimeWindow(9 * 60, 10 * 60, setOf(1)))) // Mondays only
        val monday = LocalDateTime.of(2026, 11, 16, 9, 0)
        assertEquals(millis(monday), Downtime.nextBoundaryMillis(rules, friday, utc))
        assertEquals(millis(monday.withHour(10)), Downtime.nextBoundaryMillis(rules, monday, utc))
        assertEquals(millis(monday.plusDays(7)), Downtime.nextBoundaryMillis(rules, monday.withHour(10), utc))
    }

    @Test
    fun `no windows means no boundary`() {
        assertNull(Downtime.nextBoundaryMillis(listOf(rule()), friday, utc))
        assertNull(Downtime.nextBoundaryMillis(listOf(rule(TimeWindow(60, 120, enabled = false))), friday, utc))
    }

    @Test
    fun `plan suspends what is not suspended yet and releases only what downtime held`() {
        val plan = Downtime.plan(should = setOf("a", "b"), held = setOf("c"), suspendedNow = setOf("b", "c", "z"))
        assertEquals(setOf("a"), plan.suspend)        // b was already suspended by someone else
        assertEquals(setOf("c"), plan.release)        // z is not ours to release
        assertEquals(setOf("a"), plan.held)           // b stays out of what we hold
    }

    @Test
    fun `plan keeps holding apps still in downtime`() {
        val plan = Downtime.plan(should = setOf("a"), held = setOf("a"), suspendedNow = setOf("a"))
        assertTrue(plan.suspend.isEmpty() && plan.release.isEmpty())
        assertEquals(setOf("a"), plan.held)
    }

    @Test
    fun `shortening or removing downtime is loosening, extending it is not`() {
        val old = listOf(rule(TimeWindow(22 * 60, 7 * 60)))
        assertTrue(Downtime.isLooser(old, listOf(rule(TimeWindow(23 * 60, 7 * 60))), emptyList()))
        assertTrue(Downtime.isLooser(old, listOf(rule(TimeWindow(22 * 60, 7 * 60, setOf(1, 2)))), emptyList()))
        assertTrue(Downtime.isLooser(old, emptyList(), emptyList()))
        assertTrue(Downtime.isLooser(old, listOf(rule(TimeWindow(22 * 60, 7 * 60), pkgs = setOf("b"))), emptyList()))
        assertFalse(Downtime.isLooser(old, listOf(rule(TimeWindow(21 * 60, 8 * 60))), emptyList()))
        assertFalse(Downtime.isLooser(old, old + rule(TimeWindow(9 * 60, 10 * 60), pkgs = setOf("b")), emptyList()))
    }
}
