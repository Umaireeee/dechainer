package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.DeepDiveHistory
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the deep dive is told about the month before: counts, and each earlier deep dive's link and plan. Never a note. */
class DeepDiveHistoryTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L
    private val now = ZonedDateTime.of(2026, 10, 2, 22, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val dive = """## What happened
Scrolling in bed.
## The earliest link
Taking the phone to bed at 23:00.
## What helped and what didn't
Nothing.
## For next time
If I go to bed, then the phone charges in the hall."""

    private fun entry(id: Long, at: Long, kind: UrgeKind = UrgeKind.URGE, deep: String? = dive, raw: String? = null) =
        UrgeEntry(id, at, kind, UrgeSource.HOME, null, null, if (deep != null) UrgeStatus.DONE else UrgeStatus.PENDING_DEEPDIVE, raw, null, null, deep)

    @Test
    fun onlyTheMonthBeforeCountsAndTheEntryItselfNever() {
        val h = DeepDiveHistory.from(
            listOf(
                entry(1, now - 2 * day), entry(2, now - 3 * day, UrgeKind.SLIP), entry(3, now - 40 * day),
                entry(4, now), entry(5, now + day)
            ),
            excludeId = 4, at = now, zone = zone
        )
        assertEquals(1, h.urges)
        assertEquals(1, h.slips)
        assertEquals(listOf(1L, 2L).size, h.recent.size)
        assertEquals("newest first", UrgeKind.URGE, h.recent.first().kind)
    }

    @Test
    fun eachEarlierEntryBringsItsEarliestLinkAndItsPlan() {
        val p = DeepDiveHistory.from(listOf(entry(1, now - day)), excludeId = 9, at = now, zone = zone).recent.single()
        assertEquals("Taking the phone to bed at 23:00.", p.earliestLink)
        assertEquals("If I go to bed, then the phone charges in the hall.", p.plan)
        assertEquals(22, p.at.hour)
    }

    @Test
    fun aDeepDiveInAnotherLanguageIsReadByTheUsualShape() {
        val urdu = "## Kya hua\nA\n## Pehli kari\nB\n## Kya madad hui\nC\n## Agli baar\nD"
        val p = DeepDiveHistory.from(listOf(entry(1, now - day, deep = urdu)), excludeId = 9, at = now, zone = zone).recent.single()
        assertEquals("B", p.earliestLink)
        assertEquals("D", p.plan)
    }

    @Test
    fun anEntryStillWaitingIsCountedButItsNoteIsNeverUsed() {
        val h = DeepDiveHistory.from(listOf(entry(1, now - day, deep = null, raw = "SECRET NOTE")), excludeId = 9, at = now, zone = zone)
        assertEquals(1, h.urges)
        assertTrue(h.recent.isEmpty())
        assertFalse(h.toString().contains("SECRET"))
    }

    @Test
    fun aShortOrOddDeepDiveGivesNothingRatherThanAWrongLine() {
        val p = DeepDiveHistory.from(listOf(entry(1, now - day, deep = "Just a paragraph.")), excludeId = 9, at = now, zone = zone).recent.single()
        assertNull(p.earliestLink)
        assertNull(p.plan)
    }

    @Test
    fun theListIsCappedAndLinesAreClipped() {
        val long = dive.replace("Taking the phone to bed at 23:00.", "x".repeat(1_000))
        val many = (1..20).map { entry(it.toLong(), now - it * 3_600_000L, deep = long) }
        val h = DeepDiveHistory.from(many, excludeId = 0, at = now, zone = zone)
        assertEquals(DeepDiveHistory.MAX_RECENT, h.recent.size)
        assertEquals(DeepDiveHistory.MAX_LINE, h.recent.first().earliestLink!!.length)
        assertEquals(20, h.urges)
    }
}
