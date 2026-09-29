package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.models.UrgeEntry
import io.github.warleysr.dechainer.models.UrgeStats
import io.github.warleysr.dechainer.models.UrgeTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class UrgeLogTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun ms(day: Int, hour: Int) =
        LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = ms(20, 12)

    @Test
    fun emptyLogHasNothingToReport() {
        val w = UrgeStats.week(emptyList(), now, zone)
        assertEquals(0, w.total)
        assertNull(w.resistedPercent)
        assertNull(w.topTrigger)
        assertNull(w.peakHour)
        assertNull(w.daysSinceGaveIn)
    }

    @Test
    fun weekCountsOnlyTheLastSevenDays() {
        val entries = listOf(
            UrgeEntry(ms(10, 23), UrgeTrigger.BORED, false),   // too old
            UrgeEntry(ms(15, 23), UrgeTrigger.BORED, true),
            UrgeEntry(ms(16, 23), UrgeTrigger.BORED, true),
            UrgeEntry(ms(18, 2), UrgeTrigger.STRESSED, false)
        )
        val w = UrgeStats.week(entries, now, zone)
        assertEquals(3, w.total)
        assertEquals(2, w.resisted)
        assertEquals(1, w.gaveIn)
        assertEquals(66, w.resistedPercent)
        assertEquals(UrgeTrigger.BORED, w.topTrigger)
        assertEquals(23, w.peakHour)
    }

    @Test
    fun daysSinceGaveInCountsCalendarDays() {
        val entries = listOf(
            UrgeEntry(ms(15, 3), UrgeTrigger.LONELY, false),
            UrgeEntry(ms(19, 3), UrgeTrigger.LONELY, true)     // resisting doesn't reset it
        )
        assertEquals(5, UrgeStats.daysSinceGaveIn(entries, now, zone))
        assertEquals(0, UrgeStats.daysSinceGaveIn(listOf(UrgeEntry(ms(20, 1), UrgeTrigger.HABIT, false)), now, zone))
    }

    @Test
    fun jsonRoundTripSkipsBadRows() {
        val list = listOf(UrgeEntry(1L, UrgeTrigger.TIRED, true), UrgeEntry(2L, UrgeTrigger.OTHER, false))
        assertEquals(list, UrgeEntry.listFromJson(UrgeEntry.listToJson(list)))
        assertEquals(emptyList<UrgeEntry>(), UrgeEntry.listFromJson("not json"))
        assertEquals(emptyList<UrgeEntry>(), UrgeEntry.listFromJson(null))
        assertEquals(1, UrgeEntry.listFromJson("""[{"t":1,"k":"NOPE","r":true},{"t":2,"k":"BORED","r":true}]""").size)
    }
}
