package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.DnsProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins how the adult-site filter moves between providers when a network blocks one. */
class DnsProvidersTest {

    @Test
    fun theFastProviderIsTriedFirst() {
        assertEquals("family.cloudflare-dns.com", DnsProviders.next(emptySet()))
    }

    @Test
    fun aProviderThatFailedIsSkipped() {
        assertEquals("family.adguard-dns.com", DnsProviders.next(setOf("family.cloudflare-dns.com")))
    }

    @Test
    fun theThrottledProviderComesLast() {
        val allButLast = DnsProviders.ORDER.dropLast(1).toSet()
        assertEquals("family-filter-dns.cleanbrowsing.org", DnsProviders.next(allButLast))
    }

    @Test
    fun whenEveryProviderFailedThereIsNothingLeft() {
        assertNull(DnsProviders.next(DnsProviders.ORDER.toSet()))
    }

    @Test
    fun noProviderIsListedTwice() {
        assertEquals(DnsProviders.ORDER.size, DnsProviders.ORDER.toSet().size)
    }

    @Test
    fun aNetworkThatCannotBeValidatedLooksBroken() {
        assertTrue(DnsProviders.looksBroken(validated = false, hasInternet = true))
    }

    @Test
    fun aWorkingNetworkIsLeftAlone() {
        assertFalse(DnsProviders.looksBroken(validated = true, hasInternet = true))
    }

    @Test
    fun aNetworkWithNoInternetIsNotTheDnsFault() {
        assertFalse(DnsProviders.looksBroken(validated = false, hasInternet = false))
    }
}
