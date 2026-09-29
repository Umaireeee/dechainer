package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.models.SchedulePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class SchedulePresetsTest {

    @Test
    fun everyPresetIsAnUnlockedAllowOnlyDraft() {
        for (p in SchedulePreset.entries) {
            val d = p.toDraft("id-${p.name}", p.name)
            assertTrue(d.allowOnly)
            assertTrue(d.enabled)
            assertFalse("presets must never start locked", d.lockWhileActive)
            assertTrue(d.packages.isEmpty())
            assertTrue(d.days.isNotEmpty())
            assertTrue(d.windowMinutes in 1..1440)
        }
    }

    @Test
    fun bedtimeCrossesMidnight() {
        val d = SchedulePreset.BEDTIME.toDraft("b", "Bedtime")
        assertTrue(d.crossesMidnight)
        // 2026-09-14 is a Monday.
        assertTrue(d.isActiveAt(LocalDateTime.of(2026, 9, 14, 23, 0)))
        assertTrue(d.isActiveAt(LocalDateTime.of(2026, 9, 15, 6, 0)))
        assertFalse(d.isActiveAt(LocalDateTime.of(2026, 9, 15, 6, 30)))
        assertFalse(d.isActiveAt(LocalDateTime.of(2026, 9, 15, 12, 0)))
    }

    @Test
    fun studyHoursAreWeekdaysOnly() {
        val d = SchedulePreset.STUDY_HOURS.toDraft("s", "Study")
        assertEquals(5, d.days.size)
        assertFalse(DayOfWeek.SATURDAY in d.days)
        assertTrue(d.isActiveAt(LocalDateTime.of(2026, 9, 14, 10, 0)))   // Monday
        assertFalse(d.isActiveAt(LocalDateTime.of(2026, 9, 19, 10, 0)))  // Saturday
    }
}
