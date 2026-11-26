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
    fun `buildRestrictionsBundle formats policies for Brave and Chrome and preserves unrelated keys`() {
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
            disableBraveTor = true
        )

        // Chrome-style TYPE_MULTI_SELECT / StringArray
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
        assertEquals("https://example.org", arrayBundle.getString("HomepageLocation"))
        assertEquals("off", arrayBundle.getString("DnsOverHttpsMode"))
        assertEquals("1", arrayBundle.getString("IncognitoModeAvailability"))
        assertEquals(2, arrayBundle.getInt("ForceYouTubeRestrict"))
        assertTrue(arrayBundle.getBoolean("ForceGoogleSafeSearch"))
        assertTrue(arrayBundle.getBoolean("TorDisabled"))
        assertTrue(arrayBundle.getStringArray("URLBlocklist")!!.contains("youtube.com/shorts"))

        // Manifest declaring URLBlocklist as TYPE_STRING (JSON array string)
        val stringBundle = BrowserHardeningManager.buildRestrictionsBundle(
            existing = existing,
            config = cfg,
            manifestTypes = mapOf("URLBlocklist" to RestrictionEntry.TYPE_STRING),
            isBravePackage = true
        )
        val rawJson = stringBundle.getString("URLBlocklist")!!
        val parsedArray = org.json.JSONArray(rawJson)
        val parsedList = (0 until parsedArray.length()).map { parsedArray.getString(it) }
        assertTrue("youtube.com/shorts" in parsedList)
        assertEquals(1, stringBundle.getInt("IncognitoModeAvailability"))

        // Disabling removes managed keys while keeping unrelated keys
        val cleared = BrowserHardeningManager.buildRestrictionsBundle(
            existing = arrayBundle,
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
