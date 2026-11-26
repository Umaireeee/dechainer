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
        assertFalse(def.disableDoH)
        assertFalse(def.forceSafeSearch)
        assertFalse(def.forceYouTubeRestricted)
        assertFalse(def.disableBraveTor)
        assertFalse(def.blockYouTubeShorts)
        assertFalse(def.blockInstagramReels)
        assertFalse(def.blockFacebookReels)
        assertFalse(def.blockTikTok)
        assertFalse(def.blockAdultSites)
        assertTrue(def.customBlockedUrls.isEmpty())
    }

    @Test
    fun `buildUrlBlocklist includes Shorts, Reels, TikTok, Adult sites and custom rules`() {
        val cfg = BrowserHardeningConfig(
            enabled = true,
            blockYouTubeShorts = true,
            blockInstagramReels = true,
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
        assertTrue("pornhub.com" in urls)
        assertTrue("reddit.com/r/popular" in urls)
        assertTrue("x.com" in urls)
    }

    @Test
    fun `buildRestrictionsBundle writes Chromium JSON string for Brave and preserves unrelated keys`() {
        val existing = Bundle().apply {
            putString("HomepageLocation", "https://example.org")
            putStringArray("URLBlocklist", arrayOf("old.example"))
        }
        val cfg = BrowserHardeningConfig(
            enabled = true,
            disableIncognito = true,
            disableDoH = true,
            forceSafeSearch = true,
            forceYouTubeRestricted = true,
            disableBraveTor = true,
            blockYouTubeShorts = true
        )

        // Default / Brave (empty manifestTypes) MUST emit URLBlocklist as a JSON array string
        // with unescaped slashes so Chromium PolicyConverter reads it reliably.
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
        assertEquals("off", braveBundle.getString("DnsOverHttpsMode"))
        assertEquals(1, braveBundle.getInt("IncognitoModeAvailability"))
        assertEquals(2, braveBundle.getInt("ForceYouTubeRestrict"))
        assertTrue(braveBundle.getBoolean("ForceGoogleSafeSearch"))
        assertTrue(braveBundle.getBoolean("TorDisabled"))

        // Manifest explicitly declaring TYPE_MULTI_SELECT uses StringArray
        val arrayBundle = BrowserHardeningManager.buildRestrictionsBundle(
            existing = existing,
            config = cfg,
            manifestTypes = mapOf(
                "URLBlocklist" to RestrictionEntry.TYPE_MULTI_SELECT,
                "IncognitoModeAvailability" to RestrictionEntry.TYPE_CHOICE,
                "ForceYouTubeRestrict" to RestrictionEntry.TYPE_INTEGER,
                "TorDisabled" to RestrictionEntry.TYPE_BOOLEAN
            ),
            isBravePackage = true
        )
        assertEquals("1", arrayBundle.getString("IncognitoModeAvailability"))
        assertEquals(2, arrayBundle.getInt("ForceYouTubeRestrict"))
        assertTrue(arrayBundle.getStringArray("URLBlocklist")!!.contains("youtube.com/shorts"))

        // Disabling removes managed keys while keeping unrelated keys
        val cleared = BrowserHardeningManager.buildRestrictionsBundle(
            existing = braveBundle,
            config = cfg.copy(enabled = false),
            manifestTypes = emptyMap(),
            isBravePackage = true
        )
        assertEquals("https://example.org", cleared.getString("HomepageLocation"))
        assertFalse(cleared.containsKey("URLBlocklist"))
        assertFalse(cleared.containsKey("DnsOverHttpsMode"))
        assertFalse(cleared.containsKey("IncognitoModeAvailability"))
        assertFalse(cleared.containsKey("TorDisabled"))
    }

    @Test
    fun `BrowserHardeningConfig isLooserThan detects any relaxation`() {
        val strict = BrowserHardeningConfig(
            enabled = true,
            disableIncognito = true,
            blockYouTubeShorts = true,
            customBlockedUrls = setOf("reddit.com", "x.com")
        )
        assertTrue(strict.copy(enabled = false).isLooserThan(strict))
        assertTrue(strict.copy(disableIncognito = false).isLooserThan(strict))
        assertTrue(strict.copy(blockYouTubeShorts = false).isLooserThan(strict))
        assertTrue(strict.copy(customBlockedUrls = setOf("reddit.com")).isLooserThan(strict))
        assertFalse(strict.copy(forceYouTubeRestricted = true).isLooserThan(strict))
        assertFalse(strict.copy(customBlockedUrls = setOf("reddit.com", "x.com", "9gag.com")).isLooserThan(strict))

        val roundTrip = BrowserHardeningConfig.fromJsonString(strict.toJsonString())
        assertEquals(strict, roundTrip)
        assertArrayEquals(strict.customBlockedUrls.sorted().toTypedArray(), roundTrip!!.customBlockedUrls.sorted().toTypedArray())
    }
}
