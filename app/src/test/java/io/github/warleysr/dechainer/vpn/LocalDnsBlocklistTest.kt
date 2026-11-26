package io.github.warleysr.dechainer.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDnsBlocklistTest {

    private val sampleAdultSet = setOf("pornhub.com", "xvideos.com", "onlyfans.com")
    private val defaultConfig = LocalDnsConfig(enabled = true)

    @Test
    fun `blocks exact and subdomain adult hosts`() {
        assertTrue(LocalDnsBlocklist.evaluate("pornhub.com", defaultConfig, sampleAdultSet) is DnsDecision.Block)
        assertTrue(LocalDnsBlocklist.evaluate("m.pornhub.com", defaultConfig, sampleAdultSet) is DnsDecision.Block)
        assertTrue(LocalDnsBlocklist.evaluate("cdn77.xvideos.com.", defaultConfig, sampleAdultSet) is DnsDecision.Block)
    }

    @Test
    fun `blocks unlisted mirror domains via explicit keywords without false positives`() {
        val mirror = LocalDnsBlocklist.evaluate("free-porn-mirror123.site", defaultConfig, sampleAdultSet)
        assertTrue(mirror is DnsDecision.Block)

        assertEquals(DnsDecision.Allow, LocalDnsBlocklist.evaluate("github.com", defaultConfig, sampleAdultSet))
        assertEquals(DnsDecision.Allow, LocalDnsBlocklist.evaluate("essex.ac.uk", defaultConfig, sampleAdultSet))
        assertEquals(DnsDecision.Allow, LocalDnsBlocklist.evaluate("classic.com", defaultConfig, sampleAdultSet))
    }

    @Test
    fun `blocks DoH bypass hosts and custom domains`() {
        assertTrue(LocalDnsBlocklist.evaluate("dns.google", defaultConfig, sampleAdultSet) is DnsDecision.Block)
        assertTrue(LocalDnsBlocklist.evaluate("cloudflare-dns.com", defaultConfig, sampleAdultSet) is DnsDecision.Block)

        val customCfg = defaultConfig.copy(customBlockedDomains = setOf("reddit.com"))
        assertTrue(LocalDnsBlocklist.evaluate("old.reddit.com", customCfg, sampleAdultSet) is DnsDecision.Block)
    }

    @Test
    fun `rewrites Google including google_com_pk, Bing, DuckDuckGo, and optionally YouTube`() {
        assertEquals(
            DnsDecision.Rewrite("forcesafesearch.google.com"),
            LocalDnsBlocklist.evaluate("www.google.com.pk", defaultConfig, sampleAdultSet)
        )
        assertEquals(
            DnsDecision.Rewrite("strict.bing.com"),
            LocalDnsBlocklist.evaluate("www.bing.com", defaultConfig, sampleAdultSet)
        )
        assertEquals(
            DnsDecision.Rewrite("safe.duckduckgo.com"),
            LocalDnsBlocklist.evaluate("duckduckgo.com", defaultConfig, sampleAdultSet)
        )
        assertEquals(
            DnsDecision.Allow,
            LocalDnsBlocklist.evaluate("m.youtube.com", defaultConfig, sampleAdultSet)
        )
        assertEquals(
            DnsDecision.Rewrite("restrict.youtube.com"),
            LocalDnsBlocklist.evaluate("m.youtube.com", defaultConfig.copy(forceYouTubeRestricted = true), sampleAdultSet)
        )
    }

    @Test
    fun `LocalDnsConfig isLooserThan detects any weakening`() {
        val strict = LocalDnsConfig(
            enabled = true,
            blockAdultDomains = true,
            forceSafeSearch = true,
            customBlockedDomains = setOf("a.com", "b.com")
        )
        assertTrue(strict.copy(enabled = false).isLooserThan(strict))
        assertTrue(strict.copy(forceSafeSearch = false).isLooserThan(strict))
        assertTrue(strict.copy(customBlockedDomains = setOf("a.com")).isLooserThan(strict))

        assertFalse(strict.copy(blockTikTok = true).isLooserThan(strict))
        assertFalse(strict.copy(customBlockedDomains = setOf("a.com", "b.com", "c.com")).isLooserThan(strict))
    }
}
