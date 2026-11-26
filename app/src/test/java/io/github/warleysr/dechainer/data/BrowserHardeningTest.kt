package io.github.warleysr.dechainer.data

import android.content.RestrictionEntry
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserHardeningTest {

    @Test
    fun `default BrowserHardeningConfig leaves all restrictions off`() {
        val def = BrowserHardeningConfig()
        assertFalse(def.enabled)
        assertFalse(def.disableIncognito)
        assertFalse(def.forceFamilyDns)
        assertEquals(BrowserHardeningManager.DEFAULT_FAMILY_DNS_TEMPLATE, def.familyDnsTemplate)
        assertFalse(def.lockBrowserDnsSettings)
        assertFalse(def.forceSafeSearch)
        assertFalse(def.forceYouTubeRestricted)
        assertFalse(def.disableBraveVpn)
        assertFalse(def.blockYouTubeShorts)
        assertFalse(def.blockInstagramReels)
        assertFalse(def.blockInstagramExplore)
        assertFalse(def.blockFacebookReels)
        assertFalse(def.blockSnapchatSpotlight)
        assertFalse(def.blockTikTok)
        assertFalse(def.blockAdultSites)
        assertTrue(def.customBlockedUrls.isEmpty())
        assertFalse(def.needsAccessibility())
    }

    @Test
    fun `buildUrlBlocklist includes Shorts, Reels, Explore, TikTok, Adult sites and custom rules`() {
        val cfg = BrowserHardeningConfig(
            enabled = true,
            blockYouTubeShorts = true,
            blockInstagramReels = true,
            blockInstagramExplore = true,
            blockFacebookReels = true,
            blockTikTok = true,
            blockAdultSites = true,
            customBlockedUrls = setOf("https://Reddit.com/r/popular/", "x.com")
        )
        val urls = BrowserHardeningManager.buildUrlBlocklist(cfg)
        assertTrue("youtube.com/shorts" in urls)
        assertTrue("m.youtube.com/shorts" in urls)
        assertTrue("instagram.com/reels" in urls)
        assertTrue("instagram.com/explore" in urls)
        assertTrue("facebook.com/reel" in urls)
        assertTrue("tiktok.com" in urls)
        assertTrue("vm.tiktok.com" in urls)
        assertTrue("pornhub.com" in urls)
        assertTrue("reddit.com/r/popular" in urls)
        assertTrue("x.com" in urls)
    }

    @Test
    fun `buildUrlBlocklist separates Instagram Reels and Explore`() {
        val reelsOnly = BrowserHardeningConfig(enabled = true, blockInstagramReels = true)
        val reelsUrls = BrowserHardeningManager.buildUrlBlocklist(reelsOnly)
        assertTrue("instagram.com/reels" in reelsUrls)
        assertFalse("instagram.com/explore" in reelsUrls)
    }

    @Test
    fun `buildRestrictionsBundle writes Chromium JSON string and Family DoH policy`() {
        val existing = Bundle().apply {
            putString("HomepageLocation", "https://example.org")
            putStringArray("URLBlocklist", arrayOf("old.example"))
        }
        val adguardFamily = "https://family.adguard-dns.com/dns-query"
        val cfg = BrowserHardeningConfig(
            enabled = true,
            disableIncognito = true,
            forceFamilyDns = true,
            familyDnsTemplate = adguardFamily,
            forceSafeSearch = true,
            forceYouTubeRestricted = true,
            disableBraveVpn = true,
            blockYouTubeShorts = true
        )

        val braveBundle = BrowserHardeningManager.buildRestrictionsBundle(
            existing = existing,
            config = cfg,
            manifestTypes = emptyMap(),
            isBravePackage = true
        )
        assertEquals("https://example.org", braveBundle.getString("HomepageLocation"))
        val rawJson = braveBundle.getString("URLBlocklist")!!
        assertTrue(rawJson.contains("\"youtube.com/shorts\""))
        val parsedArray = org.json.JSONArray(rawJson)
        val parsedList = (0 until parsedArray.length()).map { parsedArray.getString(it) }
        assertTrue("youtube.com/shorts" in parsedList)
        assertEquals("secure", braveBundle.getString("DnsOverHttpsMode"))
        assertEquals(adguardFamily, braveBundle.getString("DnsOverHttpsTemplates"))
        assertEquals(1, braveBundle.getInt("IncognitoModeAvailability"))
        assertEquals(2, braveBundle.getInt("ForceYouTubeRestrict"))
        assertTrue(braveBundle.getBoolean("ForceGoogleSafeSearch"))
        assertTrue(braveBundle.getBoolean("BraveVPNDisabled"))

        val arrayBundle = BrowserHardeningManager.buildRestrictionsBundle(
            existing = existing,
            config = cfg,
            manifestTypes = mapOf(
                "URLBlocklist" to RestrictionEntry.TYPE_MULTI_SELECT,
                "IncognitoModeAvailability" to RestrictionEntry.TYPE_CHOICE,
                "ForceYouTubeRestrict" to RestrictionEntry.TYPE_INTEGER,
                "BraveVPNDisabled" to RestrictionEntry.TYPE_BOOLEAN
            ),
            isBravePackage = true
        )
        assertEquals("1", arrayBundle.getString("IncognitoModeAvailability"))
        assertEquals(2, arrayBundle.getInt("ForceYouTubeRestrict"))
        assertTrue(arrayBundle.getStringArray("URLBlocklist")!!.contains("youtube.com/shorts"))

        val cleared = BrowserHardeningManager.buildRestrictionsBundle(
            existing = braveBundle,
            config = cfg.copy(enabled = false),
            manifestTypes = emptyMap(),
            isBravePackage = true
        )
        assertEquals("https://example.org", cleared.getString("HomepageLocation"))
        assertFalse(cleared.containsKey("URLBlocklist"))
        assertFalse(cleared.containsKey("DnsOverHttpsMode"))
        assertFalse(cleared.containsKey("DnsOverHttpsTemplates"))
        assertFalse(cleared.containsKey("IncognitoModeAvailability"))
        assertFalse(cleared.containsKey("BraveVPNDisabled"))
    }

    @Test
    fun `BrowserHardeningConfig isLooserThan detects any relaxation and migrates legacy json`() {
        val strict = BrowserHardeningConfig(
            enabled = true,
            disableIncognito = true,
            forceFamilyDns = true,
            familyDnsTemplate = "https://family.cloudflare-dns.com/dns-query",
            lockBrowserDnsSettings = true,
            blockYouTubeShorts = true,
            blockInstagramExplore = true,
            blockSnapchatSpotlight = true,
            customBlockedUrls = setOf("reddit.com", "x.com")
        )
        assertTrue(strict.copy(enabled = false).isLooserThan(strict))
        assertTrue(strict.copy(disableIncognito = false).isLooserThan(strict))
        assertTrue(strict.copy(forceFamilyDns = false).isLooserThan(strict))
        assertTrue(
            strict.copy(familyDnsTemplate = "https://family.adguard-dns.com/dns-query")
                .isLooserThan(strict)
        )
        assertTrue(strict.copy(lockBrowserDnsSettings = false).isLooserThan(strict))
        assertTrue(strict.copy(blockYouTubeShorts = false).isLooserThan(strict))
        assertTrue(strict.copy(blockInstagramExplore = false).isLooserThan(strict))
        assertTrue(strict.copy(blockSnapchatSpotlight = false).isLooserThan(strict))
        assertTrue(strict.copy(customBlockedUrls = setOf("reddit.com")).isLooserThan(strict))
        assertFalse(strict.copy(forceYouTubeRestricted = true).isLooserThan(strict))
        assertFalse(strict.copy(customBlockedUrls = setOf("reddit.com", "x.com", "9gag.com")).isLooserThan(strict))

        val roundTrip = BrowserHardeningConfig.fromJsonString(strict.toJsonString())
        assertEquals(strict, roundTrip)
        assertArrayEquals(strict.customBlockedUrls.sorted().toTypedArray(), roundTrip!!.customBlockedUrls.sorted().toTypedArray())

        // Legacy v2.4.6 JSON with disableDoH/disableBraveTor must deserialize cleanly
        val legacyJson = """{"enabled":true,"disableDoH":true,"disableBraveTor":true,"blockYouTubeShorts":true}"""
        val migrated = BrowserHardeningConfig.fromJsonString(legacyJson)!!
        assertTrue(migrated.enabled)
        assertTrue(migrated.blockYouTubeShorts)
        assertTrue(migrated.disableBraveVpn)
        assertFalse(migrated.forceFamilyDns)
        assertEquals(BrowserHardeningManager.DEFAULT_FAMILY_DNS_TEMPLATE, migrated.familyDnsTemplate)

        assertEquals("reddit.com/r/popular", BrowserHardeningConfig.normalizeRule("https://www.Reddit.com/r/popular/?utm_source=x#top"))
        assertEquals("", BrowserHardeningConfig.normalizeRule("not a valid rule"))
        assertEquals("", BrowserHardeningConfig.normalizeRule("localhost"))
    }
}
