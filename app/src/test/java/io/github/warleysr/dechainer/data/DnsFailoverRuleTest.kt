package io.github.warleysr.dechainer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsFailoverRuleTest {

    @Test
    fun returnsNullWhenDisabledAndNothingPinned() {
        assertNull(
            DnsFailoverRule.chooseServer(
                pinnedHost = null,
                autoFailover = false,
                reachability = mapOf("family.cloudflare-dns.com" to true)
            )
        )
    }

    @Test
    fun keepsPinnedHostWhenAutoFailoverIsOff() {
        assertEquals(
            "family.cloudflare-dns.com",
            DnsFailoverRule.chooseServer(
                pinnedHost = "family.cloudflare-dns.com",
                autoFailover = false,
                reachability = mapOf(
                    "family.cloudflare-dns.com" to false,
                    "family.adguard-dns.com" to true
                )
            )
        )
    }

    @Test
    fun keepsActiveServerWhenItStillWorks() {
        assertEquals(
            "family.adguard-dns.com",
            DnsFailoverRule.chooseServer(
                pinnedHost = "family.adguard-dns.com",
                autoFailover = true,
                reachability = mapOf(
                    "family.cloudflare-dns.com" to true,
                    "family.adguard-dns.com" to true
                )
            )
        )
    }

    @Test
    fun switchesImmediatelyWhenPinnedServerBecomesUnreachable() {
        assertEquals(
            "adult-filter-dns.cleanbrowsing.org",
            DnsFailoverRule.chooseServer(
                pinnedHost = "family.cloudflare-dns.com",
                autoFailover = true,
                reachability = mapOf(
                    "family.cloudflare-dns.com" to false,
                    "family.adguard-dns.com" to false,
                    "adult-filter-dns.cleanbrowsing.org" to true,
                    "family-filter-dns.cleanbrowsing.org" to true
                )
            )
        )
    }

    @Test
    fun neverDropsFilteringWhenAllCleanServersAreUnreachable() {
        assertEquals(
            "family.adguard-dns.com",
            DnsFailoverRule.chooseServer(
                pinnedHost = "family.adguard-dns.com",
                autoFailover = true,
                reachability = DnsFailoverRule.CLEAN_HOSTS.associateWith { false }
            )
        )
        assertEquals(
            DnsFailoverRule.CLEAN_HOSTS.first(),
            DnsFailoverRule.chooseServer(
                pinnedHost = null,
                autoFailover = true,
                reachability = DnsFailoverRule.CLEAN_HOSTS.associateWith { false }
            )
        )
    }

    @Test
    fun probesImmediatelyOnNetworkSwitchAndSkipsRedundantChecksOnSameNetwork() {
        assertFalse(
            DnsFailoverRule.shouldProbeNow(
                autoFailover = true,
                hasValidatedInternet = true,
                currentNetworkId = 42L,
                lastVerifiedNetworkId = 42L,
                lastVerifiedHost = "family.cloudflare-dns.com",
                pinnedHost = "family.cloudflare-dns.com",
                lastProbeElapsedMs = 10_000L,
                nowElapsedMs = 20_000L
            )
        )
        assertTrue(
            DnsFailoverRule.shouldProbeNow(
                autoFailover = true,
                hasValidatedInternet = true,
                currentNetworkId = 99L,
                lastVerifiedNetworkId = 42L,
                lastVerifiedHost = "family.cloudflare-dns.com",
                pinnedHost = "family.cloudflare-dns.com",
                lastProbeElapsedMs = 10_000L,
                nowElapsedMs = 10_500L
            )
        )
        assertTrue(
            DnsFailoverRule.shouldProbeNow(
                autoFailover = true,
                hasValidatedInternet = true,
                currentNetworkId = 42L,
                lastVerifiedNetworkId = 42L,
                lastVerifiedHost = "family.cloudflare-dns.com",
                pinnedHost = "family.cloudflare-dns.com",
                lastProbeElapsedMs = 10_000L,
                nowElapsedMs = 70_000L
            )
        )
    }

    @Test
    fun skipsProbingWithoutValidatedInternetOrAutoFailover() {
        assertFalse(
            DnsFailoverRule.shouldProbeNow(
                autoFailover = false,
                hasValidatedInternet = true,
                currentNetworkId = 99L,
                lastVerifiedNetworkId = 42L,
                lastVerifiedHost = null,
                pinnedHost = "family.cloudflare-dns.com",
                lastProbeElapsedMs = null,
                nowElapsedMs = 10_000L
            )
        )
        assertFalse(
            DnsFailoverRule.shouldProbeNow(
                autoFailover = true,
                hasValidatedInternet = false,
                currentNetworkId = 99L,
                lastVerifiedNetworkId = 42L,
                lastVerifiedHost = null,
                pinnedHost = "family.cloudflare-dns.com",
                lastProbeElapsedMs = null,
                nowElapsedMs = 10_000L
            )
        )
    }
}
