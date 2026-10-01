package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.guard.CrashGuard
import io.github.warleysr.dechainer.guard.CrashLog
import io.github.warleysr.dechainer.guard.CrashRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {
    private val minute = 60_000L

    private class MemoryLog(var records: List<CrashRecord> = emptyList()) : CrashLog {
        override fun load() = records
        override fun save(records: List<CrashRecord>) { this.records = records }
    }

    private class Harness(brick: Boolean = true, val log: MemoryLog = MemoryLog()) {
        var brickActive = brick
        var aborts = 0
        val guard = CrashGuard(log, brickActive = { brickActive }, abort = { aborts++ })
    }

    @Test
    fun oneCrashDuringABrickDoesNotAbortIt() {
        val h = Harness()
        assertFalse(h.guard.onCrash(10 * minute, 5))
        assertEquals(0, h.aborts)
    }

    @Test
    fun twoCrashesWithinFiveMinutesDuringABrickAbortIt() {
        val h = Harness()
        h.guard.onCrash(10 * minute, 5)
        assertTrue(h.guard.onCrash(12 * minute, 5))
        assertEquals(1, h.aborts)
    }

    @Test
    fun theCrashLogIsClearedAfterAnAbortSoTheNextCrashStartsFresh() {
        val h = Harness()
        h.guard.onCrash(10 * minute, 5)
        h.guard.onCrash(11 * minute, 5)
        assertTrue(h.log.records.isEmpty())
        assertFalse("one new crash is not a loop", h.guard.onCrash(12 * minute, 5))
        assertEquals(1, h.aborts)
    }

    @Test
    fun twoCrashesFarApartDoNotAbort() {
        val h = Harness()
        h.guard.onCrash(10 * minute, 5)
        assertFalse(h.guard.onCrash(16 * minute, 5))
        assertEquals(0, h.aborts)
    }

    @Test
    fun theWindowIsFiveMinutesInclusive() {
        val a = Harness()
        a.guard.onCrash(10 * minute, 5)
        assertTrue("exactly 5:00 later still counts", a.guard.onCrash(15 * minute, 5))
        val b = Harness()
        b.guard.onCrash(10 * minute, 5)
        assertFalse("5:00.001 later does not", b.guard.onCrash(15 * minute + 1, 5))
    }

    @Test
    fun crashesWithNoBrickRunningNeverAbortAnything() {
        val h = Harness(brick = false)
        h.guard.onCrash(10 * minute, 5)
        assertFalse(h.guard.onCrash(11 * minute, 5))
        assertFalse(h.guard.onCrash(12 * minute, 5))
        assertEquals(0, h.aborts)
    }

    @Test
    fun crashesBeforeTheBrickStartedStillCountTowardsTheLoop() {
        // Two crashes while idle, then the block starts and the app crashes a third time at once.
        val h = Harness(brick = false)
        h.guard.onCrash(10 * minute, 5)
        h.guard.onCrash(11 * minute, 5)
        h.brickActive = true
        assertTrue(h.guard.onCrash(12 * minute, 5))
    }

    @Test
    fun aCrashFromBeforeARebootIsNotCountedWithOneAfterIt() {
        val h = Harness()
        h.guard.onCrash(10 * minute, 5)
        // Rebooted: boot count 6, and elapsed-realtime restarted from zero.
        assertFalse(h.guard.onCrash(1 * minute, 6))
        assertEquals(0, h.aborts)
        assertTrue("two in the new boot do count", h.guard.onCrash(2 * minute, 6))
    }

    @Test
    fun oldCrashesArePrunedFromTheLog() {
        val h = Harness(brick = false)
        h.guard.onCrash(1 * minute, 5)
        h.guard.onCrash(30 * minute, 5)
        assertEquals(listOf(CrashRecord(30 * minute, 5)), h.log.records)
    }

    @Test
    fun anAbortThatFailsIsSwallowedAndTheNextCrashTriesAgain() {
        var attempts = 0
        var released = false
        val guard = CrashGuard(MemoryLog(), brickActive = { true }, abort = {
            attempts++
            if (attempts == 1) error("release failed")
            released = true
        })
        guard.onCrash(10 * minute, 5)
        assertFalse("never throws out of an uncaught-exception handler", guard.onCrash(11 * minute, 5))
        assertFalse(released)
        assertTrue("the loop was remembered, so the next crash aborts", guard.onCrash(12 * minute, 5))
        assertTrue(released)
        assertEquals(2, attempts)
    }

    @Test
    fun aBrokenLogOrProbeNeverThrows() {
        val brokenLog = object : CrashLog {
            override fun load(): List<CrashRecord> = error("disk")
            override fun save(records: List<CrashRecord>) = error("disk")
        }
        assertFalse(CrashGuard(brokenLog, { true }, { }).onCrash(1, 1))
        val brokenProbe = CrashGuard(MemoryLog(), brickActive = { error("prefs") }, abort = { })
        brokenProbe.onCrash(1, 1)
        assertFalse(brokenProbe.onCrash(2, 1))
    }

    @Test
    fun recordsRoundTripAndGarbageIsDropped() {
        val records = listOf(CrashRecord(10, 1), CrashRecord(20, 2))
        assertEquals(records, CrashRecord.decode(CrashRecord.encode(records)))
        assertEquals(listOf(CrashRecord(5, 3)), CrashRecord.decode("x;5:3;1:2:3;;4:y"))
        assertTrue(CrashRecord.decode(null).isEmpty())
        assertTrue(CrashRecord.decode("").isEmpty())
    }
}
