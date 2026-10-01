package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.SyncCoalescer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoalescerTest {
    @Test
    fun nothingRequestedMeansNothingToRun() {
        val c = SyncCoalescer()
        assertNull(c.beginPass())
        assertFalse(c.hasWaiting())
    }

    @Test
    fun aBurstOfRequestsBeforeAPassStartsCostsOnePass() {
        val c = SyncCoalescer()
        val tickets = List(5) { c.request() }
        val claim = c.beginPass()
        assertEquals(tickets.last(), claim)
        assertNull("no second pass is owed", c.beginPass())
        c.endPass(claim!!)
        assertTrue("every caller in the burst is served by that one pass", tickets.all { c.isServed(it) })
    }

    @Test
    fun aRequestDuringARunningPassGetsExactlyOneMore() {
        val c = SyncCoalescer()
        val first = c.request()
        val claim1 = c.beginPass()!!
        val during1 = c.request()
        val during2 = c.request()
        c.endPass(claim1)
        assertTrue(c.isServed(first))
        assertFalse("a request made while a pass runs is not served by it", c.isServed(during1))
        assertTrue(c.hasWaiting())
        val claim2 = c.beginPass()!!
        c.endPass(claim2)
        assertTrue(c.isServed(during1))
        assertTrue(c.isServed(during2))
        assertNull(c.beginPass())
    }

    @Test
    fun ticketsOnlyIncrease() {
        val c = SyncCoalescer()
        assertEquals(1L, c.request())
        assertEquals(2L, c.request())
        assertEquals(3L, c.request())
    }

    @Test
    fun anEarlierPassFinishingLateNeverUnservesALaterTicket() {
        val c = SyncCoalescer()
        c.request()
        val claim1 = c.beginPass()!!
        val t2 = c.request()
        val claim2 = c.beginPass()!!
        c.endPass(claim2)
        c.endPass(claim1)
        assertTrue(c.isServed(t2))
    }
}
