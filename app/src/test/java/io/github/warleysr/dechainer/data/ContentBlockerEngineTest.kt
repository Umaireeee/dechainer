package io.github.warleysr.dechainer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockerEngineTest {

    private val allEnabled = BrowserHardeningConfig(
        enabled = true,
        lockBrowserDnsSettings = true,
        blockYouTubeShorts = true,
        blockInstagramReels = true,
        blockInstagramExplore = true,
        blockFacebookReels = true,
        blockSnapchatSpotlight = true,
        blockTikTok = true,
        blockAdultSites = true,
        customBlockedUrls = setOf("reddit.com/r/popular", "x.com")
    )

    @Test
    fun `disabled config blocks nothing and watches zero packages`() {
        val disabled = allEnabled.copy(enabled = false)
        assertTrue(ContentBlockerEngine.activeWatchedPackages(disabled, setOf("com.android.chrome")).isEmpty())
        assertNull(
            ContentBlockerEngine.evaluate(
                ContentBlockerEngine.ScreenSnapshot(
                    packageName = "com.zhiliaoapp.musically",
                    className = "MainActivity"
                ),
                disabled
            )
        )
    }

    @Test
    fun `YouTube Shorts detected via viewId, tab selection, or text cues while normal videos pass`() {
        val byViewId = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.google.android.youtube",
                matchedViewIds = setOf("com.google.android.youtube:id/reel_watch_fragment_root")
            ),
            allEnabled
        )
        assertEquals("youtube_shorts_app", byViewId?.rule)
        assertEquals(ContentBlockerEngine.EnforcementAction.PRESS_BACK, byViewId?.action)

        val byTab = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "app.revanced.android.youtube",
                selectedTabLabels = setOf("Shorts")
            ),
            allEnabled
        )
        assertEquals("youtube_shorts_app", byTab?.rule)

        val byText = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.google.android.youtube",
                visibleTexts = listOf("Remix this Short")
            ),
            allEnabled
        )
        assertEquals("youtube_shorts_app", byText?.rule)

        val normalWatch = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.google.android.youtube",
                selectedTabLabels = setOf("Home"),
                visibleTexts = listOf("Subscribe", "Comments", "Share")
            ),
            allEnabled
        )
        assertNull(normalWatch)
    }

    @Test
    fun `Instagram Reels blocks clips without blocking Stories or DMs`() {
        val reelsByViewId = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.instagram.android",
                matchedViewIds = setOf("com.instagram.android:id/clips_viewer_view_pager")
            ),
            allEnabled
        )
        assertEquals("instagram_reels_app", reelsByViewId?.rule)

        val storiesAndDms = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.instagram.android",
                matchedViewIds = setOf("com.instagram.android:id/reel_viewer_container"),
                selectedTabLabels = setOf("Home"),
                visibleTexts = listOf("Your story", "Messages")
            ),
            allEnabled
        )
        assertNull(storiesAndDms)
    }

    @Test
    fun `Instagram Explore is controlled independently from Reels`() {
        val reelsOnly = BrowserHardeningConfig(enabled = true, blockInstagramReels = true)
        val exploreSnap = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.instagram.android",
            matchedViewIds = setOf("com.instagram.android:id/explore_grid"),
            selectedTabLabels = setOf("Search and explore")
        )
        assertNull(ContentBlockerEngine.evaluate(exploreSnap, reelsOnly))
        assertEquals("instagram_explore_app", ContentBlockerEngine.evaluate(exploreSnap, allEnabled)?.rule)
    }

    @Test
    fun `Facebook Reels, Snapchat Spotlight, and TikTok app are detected accurately`() {
        val fbMatch = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.facebook.katana",
                matchedViewIds = setOf("com.facebook.katana:id/reels_viewer_container")
            ),
            allEnabled
        )
        assertEquals("facebook_reels_app", fbMatch?.rule)

        val fbNormalFeed = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.facebook.katana",
                selectedTabLabels = setOf("Home"),
                visibleTexts = listOf("What's on your mind?")
            ),
            allEnabled
        )
        assertNull(fbNormalFeed)

        val snapMatch = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.snapchat.android",
                selectedTabLabels = setOf("Spotlight")
            ),
            allEnabled
        )
        assertEquals("snapchat_spotlight_app", snapMatch?.rule)

        val snapChat = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.snapchat.android",
                selectedTabLabels = setOf("Chat")
            ),
            allEnabled
        )
        assertNull(snapChat)

        val tiktokMatch = ContentBlockerEngine.evaluate(
            ContentBlockerEngine.ScreenSnapshot(
                packageName = "com.zhiliaoapp.musically"
            ),
            allEnabled
        )
        assertEquals("tiktok_app", tiktokMatch?.rule)
        assertEquals(ContentBlockerEngine.EnforcementAction.GO_HOME, tiktokMatch?.action)
    }

    @Test
    fun `Browser DNS settings lock blocks Secure DNS and flags across browsers but allows normal web pages`() {
        val chromeSecureDnsSettings = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.android.chrome",
            className = "org.chromium.chrome.browser.settings.SettingsActivity",
            visibleTexts = listOf("Privacy and security", "Use secure DNS", "With your current service provider")
        )
        assertEquals("browser_dns_settings", ContentBlockerEngine.evaluate(chromeSecureDnsSettings, allEnabled)?.rule)

        val braveFlags = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.brave.browser",
            className = "org.chromium.chrome.browser.ChromeTabbedActivity",
            urlBarText = "brave://flags/#dns-over-https"
        )
        assertEquals("browser_dns_settings", ContentBlockerEngine.evaluate(braveFlags, allEnabled)?.rule)

        val firefoxAboutConfig = ContentBlockerEngine.ScreenSnapshot(
            packageName = "org.mozilla.firefox",
            urlBarText = "about:config"
        )
        assertEquals("browser_dns_settings", ContentBlockerEngine.evaluate(firefoxAboutConfig, allEnabled)?.rule)

        val wikipediaDohArticle = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.android.chrome",
            className = "org.chromium.chrome.browser.ChromeTabbedActivity",
            urlBarText = "https://en.wikipedia.org/wiki/DNS_over_HTTPS",
            visibleTexts = listOf("DNS over HTTPS", "Secure DNS", "DNS provider")
        )
        assertNull(ContentBlockerEngine.evaluate(wikipediaDohArticle, allEnabled))

        val googleSearchDoh = ContentBlockerEngine.ScreenSnapshot(
            packageName = "org.mozilla.firefox",
            className = "org.mozilla.fenix.HomeActivity",
            urlBarText = "https://www.google.com/search?q=use+secure+dns",
            visibleTexts = listOf("Use secure DNS")
        )
        assertNull(ContentBlockerEngine.evaluate(googleSearchDoh, allEnabled))
    }

    @Test
    fun `Browser URL blocking catches short-form, adult, and custom paths across all browsers`() {
        val firefoxShorts = ContentBlockerEngine.ScreenSnapshot(
            packageName = "org.mozilla.firefox",
            urlBarText = "m.youtube.com/shorts/abc123"
        )
        assertNotNull(ContentBlockerEngine.evaluate(firefoxShorts, allEnabled))

        val normalYouTubeInFirefox = ContentBlockerEngine.ScreenSnapshot(
            packageName = "org.mozilla.firefox",
            urlBarText = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        assertNull(ContentBlockerEngine.evaluate(normalYouTubeInFirefox, allEnabled))

        val operaAdult = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.opera.browser",
            urlBarText = "https://www.pornhub.com/video"
        )
        assertNotNull(ContentBlockerEngine.evaluate(operaAdult, allEnabled))

        val customSubpath = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.sec.android.app.sbrowser",
            urlBarText = "https://www.reddit.com/r/popular/hot"
        )
        assertEquals("browser_url:reddit.com/r/popular", ContentBlockerEngine.evaluate(customSubpath, allEnabled)?.rule)

        val allowedSubreddit = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.sec.android.app.sbrowser",
            urlBarText = "https://www.reddit.com/r/kotlin"
        )
        assertNull(ContentBlockerEngine.evaluate(allowedSubreddit, allEnabled))

        // Bypass-resistance: userinfo@ and view-source: prefixes must still normalize and block.
        val userinfoBypass = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.brave.browser",
            urlBarText = "https://google.com@www.pornhub.com/video"
        )
        assertNotNull(ContentBlockerEngine.evaluate(userinfoBypass, allEnabled))

        val viewSourceBypass = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.brave.browser",
            urlBarText = "view-source:https://m.youtube.com/shorts/abc123"
        )
        assertNotNull(ContentBlockerEngine.evaluate(viewSourceBypass, allEnabled))

        // Incognito detection & false-positive suppression on home feed cards.
        val incognitoCfg = allEnabled.copy(disableIncognito = true)
        val incognitoTab = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.brave.browser",
            matchedViewIds = setOf("com.brave.browser:id/location_bar_incognito_badge")
        )
        assertEquals("browser_incognito", ContentBlockerEngine.evaluate(incognitoTab, incognitoCfg)?.rule)

        val homeFeedNotShorts = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.google.android.youtube",
            selectedTabLabels = setOf("Home"),
            visibleTexts = listOf("More shorts", "Remix")
        )
        assertNull(ContentBlockerEngine.evaluate(homeFeedNotShorts, allEnabled))

        val braveDnsPage = ContentBlockerEngine.ScreenSnapshot(
            packageName = "com.brave.browser",
            urlBarText = "brave://dns"
        )
        assertEquals("browser_dns_settings", ContentBlockerEngine.evaluate(braveDnsPage, allEnabled)?.rule)
    }
}
