package io.github.warleysr.dechainer.data

/**
 * Pure detection engine for:
 * 1. Browser DNS / flags settings lock and Incognito fallback across all browsers.
 * 2. Distracting short-form feed detection inside native social apps (YouTube Shorts,
 *    Instagram Reels & Explore, Facebook Reels & Watch, Snapchat Spotlight, TikTok).
 * 3. URL & domain blocking across all browsers via address-bar inspection.
 */
object ContentBlockerEngine {

    enum class EnforcementAction {
        PRESS_BACK,
        GO_HOME
    }

    data class BlockMatch(
        val rule: String,
        val action: EnforcementAction
    )

    data class ScreenSnapshot(
        val packageName: String,
        val className: String = "",
        val urlBarText: String? = null,
        val matchedViewIds: Set<String> = emptySet(),
        val visibleTexts: List<String> = emptyList(),
        val selectedTabLabels: Set<String> = emptySet(),
        val contentDescriptions: List<String> = emptyList()
    )

    val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "com.google.android.apps.youtube.mango"
    )

    val INSTAGRAM_PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    val FACEBOOK_PACKAGES = setOf(
        "com.facebook.katana",
        "com.facebook.lite"
    )

    val SNAPCHAT_PACKAGES = setOf(
        "com.snapchat.android"
    )

    val TIKTOK_PACKAGES = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.aweme"
    )

    val KNOWN_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.opera.touch",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "org.cromite.cromite",
        "us.spotco.fennec_dos",
        "org.mozilla.fennec_fdroid",
        "net.waterfox.android.release",
        "com.yandex.browser",
        "mark.via.gp",
        "com.mi.globalbrowser",
        "com.android.browser",
        "com.UCMobile.intl"
    )

    internal val YOUTUBE_SHORTS_VIEW_IDS = setOf(
        "reel_watch_fragment_root",
        "reel_recycler",
        "reel_player_page_container",
        "reel_watch_player",
        "reel_watch_sequence_view_container",
        "shorts_player_view",
        "shorts_video_view",
        "reel_progress_bar"
    )

    // Intentionally excludes `reel_viewer_container`, which Instagram uses for Stories.
    internal val INSTAGRAM_REELS_VIEW_IDS = setOf(
        "clips_viewer_view_pager",
        "clips_viewer_container",
        "clips_video_container",
        "clips_ufi_component",
        "clips_author_info_component",
        "clips_media_component"
    )

    internal val INSTAGRAM_EXPLORE_VIEW_IDS = setOf(
        "explore_grid",
        "discovery_surface_container",
        "explore_action_bar"
    )

    internal val FACEBOOK_REELS_VIEW_IDS = setOf(
        "reels_viewer_container",
        "fb_shorts_viewer_container",
        "fb_shorts_video_view",
        "shorts_viewer_root"
    )

    internal val SNAPCHAT_SPOTLIGHT_VIEW_IDS = setOf(
        "spotlight_view",
        "spotlight_feed_container",
        "spotlight_player"
    )

    internal val BROWSER_INCOGNITO_VIEW_IDS = setOf(
        "location_bar_incognito_badge",
        "incognito_badge",
        "incognito_ntp_container",
        "new_tab_incognito_container",
        "private_browsing_badge"
    )

    private val GENERIC_URL_BAR_ID_SUFFIXES = listOf(
        "url_bar",
        "location_bar_edit_text",
        "url_field",
        "omnibox_text",
        "address_bar",
        "bro_omnibar_address_title_text"
    )

    private val FIREFOX_URL_BAR_ID_SUFFIXES = listOf(
        "mozac_browser_toolbar_url_view",
        "url_bar"
    )

    private val DUCKDUCKGO_URL_BAR_ID_SUFFIXES = listOf(
        "omniboxTextInput",
        "url_bar"
    )

    private val DNS_CLASS_TOKENS = listOf(
        "securedns",
        "dnsoverhttps",
        "dohsettings",
        "privatedns",
        "dns_settings",
        "dnssettings"
    )

    private val INTERNAL_DNS_URL_PATTERNS = listOf(
        "chrome://settings/security",
        "brave://settings/security",
        "edge://settings/privacy",
        "edge://settings/security",
        "vivaldi://settings/privacy",
        "opera://settings",
        "chrome://dns",
        "brave://dns",
        "chrome://flags",
        "brave://flags",
        "edge://flags",
        "opera://flags",
        "vivaldi://flags",
        "chrome://net-internals",
        "brave://net-internals",
        "edge://net-internals",
        "vivaldi://net-internals",
        "about:config",
        "about:preferences",
        "about:networking"
    )

    private val STRONG_DNS_SETTINGS_PHRASES = listOf(
        "use secure dns",
        "secure dns",
        "dns over https",
        "dns-over-https",
        "dns provider",
        "doh provider",
        "custom dns",
        "use your current service provider",
        "with your current service provider",
        "cleanbrowsing (family filter)",
        "cloudflare (1.1.1.1)",
        "google (public dns)"
    )

    private val LANGUAGE_NEUTRAL_DNS_TOKENS = listOf(
        "1.1.1.1",
        "cloudflare",
        "adguard",
        "cleanbrowsing",
        "quad9",
        "nextdns",
        "doh"
    )

    fun browserUrlBarViewIds(packageName: String): List<String> {
        val suffixes = when {
            packageName.startsWith("org.mozilla.") ||
                packageName == "us.spotco.fennec_dos" ||
                packageName == "net.waterfox.android.release" -> FIREFOX_URL_BAR_ID_SUFFIXES
            packageName == "com.duckduckgo.mobile.android" -> DUCKDUCKGO_URL_BAR_ID_SUFFIXES
            packageName.startsWith("com.opera.") -> listOf("url_field", "url_bar")
            packageName.startsWith("com.sec.android.app.sbrowser") -> listOf("location_bar_edit_text", "url_bar")
            else -> GENERIC_URL_BAR_ID_SUFFIXES
        }
        return (suffixes + GENERIC_URL_BAR_ID_SUFFIXES)
            .distinct()
            .map { "$packageName:id/$it" }
    }

    fun viewIdsToProbeFor(
        packageName: String,
        config: BrowserHardeningConfig,
        browserPackages: Set<String> = KNOWN_BROWSER_PACKAGES
    ): List<String> {
        if (!config.enabled) return emptyList()
        val suffixes = LinkedHashSet<String>()
        if (config.blockYouTubeShorts && packageName in YOUTUBE_PACKAGES) {
            suffixes.addAll(YOUTUBE_SHORTS_VIEW_IDS)
        }
        if (packageName in INSTAGRAM_PACKAGES) {
            if (config.blockInstagramReels) suffixes.addAll(INSTAGRAM_REELS_VIEW_IDS)
            if (config.blockInstagramExplore) suffixes.addAll(INSTAGRAM_EXPLORE_VIEW_IDS)
        }
        if (config.blockFacebookReels && packageName in FACEBOOK_PACKAGES) {
            suffixes.addAll(FACEBOOK_REELS_VIEW_IDS)
        }
        if (config.blockSnapchatSpotlight && packageName in SNAPCHAT_PACKAGES) {
            suffixes.addAll(SNAPCHAT_SPOTLIGHT_VIEW_IDS)
        }
        if (config.disableIncognito && (packageName in browserPackages || packageName in KNOWN_BROWSER_PACKAGES)) {
            suffixes.addAll(BROWSER_INCOGNITO_VIEW_IDS)
        }
        return suffixes.map { "$packageName:id/$it" }
    }

    fun activeWatchedPackages(
        config: BrowserHardeningConfig,
        browserPackages: Set<String>
    ): Set<String> {
        if (!config.enabled) return emptySet()
        val watched = LinkedHashSet<String>()
        if (config.blockTikTok) watched.addAll(TIKTOK_PACKAGES)
        if (config.blockYouTubeShorts) watched.addAll(YOUTUBE_PACKAGES)
        if (config.blockInstagramReels || config.blockInstagramExplore) watched.addAll(INSTAGRAM_PACKAGES)
        if (config.blockFacebookReels) watched.addAll(FACEBOOK_PACKAGES)
        if (config.blockSnapchatSpotlight) watched.addAll(SNAPCHAT_PACKAGES)
        if (config.needsBrowserAccessibility()) {
            watched.addAll(browserPackages)
            watched.addAll(KNOWN_BROWSER_PACKAGES)
        }
        return watched
    }

    fun isTransientViewClassName(className: String): Boolean {
        if (className.isBlank()) return true
        return className.startsWith("android.widget.") ||
            className.startsWith("android.view.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.google.android.material.")
    }

    fun isWebPageUrl(urlBarText: String?): Boolean {
        val raw = urlBarText?.trim()?.lowercase() ?: return false
        if (raw.isEmpty()) return false
        if (INTERNAL_DNS_URL_PATTERNS.any { raw.startsWith(it) || raw.contains(it) }) return false
        if (raw.contains("://") && !raw.startsWith("http://") && !raw.startsWith("https://")) return false
        if (raw.startsWith("about:")) return false
        return extractHostAndPath(raw) != null
    }

    fun isSettingsClassName(className: String): Boolean {
        val lower = className.lowercase()
        return lower.contains("settings") ||
            lower.contains("preference") ||
            lower.contains("privacy") ||
            lower.contains("security") ||
            lower.contains("dialog")
    }

    fun extractHostAndPath(rawUrl: String?): Pair<String, String>? {
        var clean = rawUrl?.trim()?.lowercase() ?: return null
        if (clean.isEmpty() || clean.contains(' ')) return null
        if (clean.startsWith("view-source:")) {
            clean = clean.removePrefix("view-source:").trim()
        }
        if (clean.startsWith("about:") || clean.startsWith("chrome:") ||
            clean.startsWith("brave:") || clean.startsWith("edge:") ||
            clean.startsWith("opera:") || clean.startsWith("vivaldi:")
        ) {
            return null
        }
        clean = clean.removePrefix("https://").removePrefix("http://")
        val slashPos = clean.indexOf('/')
        val authority = if (slashPos >= 0) clean.substring(0, slashPos) else clean
        val atPos = authority.lastIndexOf('@')
        if (atPos >= 0) {
            clean = clean.substring(atPos + 1)
        }
        clean = clean.removePrefix("www.")
        if (clean.isEmpty()) return null

        val slashIdx = clean.indexOf('/')
        val queryIdx = clean.indexOf('?')
        val hashIdx = clean.indexOf('#')
        val hostEnd = listOf(slashIdx, queryIdx, hashIdx).filter { it >= 0 }.minOrNull() ?: clean.length

        var host = clean.substring(0, hostEnd)
        val colonIdx = host.indexOf(':')
        if (colonIdx >= 0) host = host.substring(0, colonIdx)
        if (host.isEmpty() || !host.contains('.') || host.startsWith('.') || host.endsWith('.')) {
            return null
        }

        val rawPath = if (slashIdx in 0 until clean.length) {
            val pathEnd = listOf(queryIdx, hashIdx).filter { it > slashIdx }.minOrNull() ?: clean.length
            clean.substring(slashIdx, pathEnd).trimEnd('/')
        } else {
            ""
        }
        return host to rawPath
    }

    fun matchesUrlRule(host: String, path: String, rule: String): Boolean {
        val normalized = BrowserHardeningConfig.normalizeRule(rule)
        if (normalized.isEmpty()) return false
        val slashIdx = normalized.indexOf('/')
        val ruleHost = if (slashIdx >= 0) normalized.substring(0, slashIdx) else normalized
        val rulePath = if (slashIdx >= 0) normalized.substring(slashIdx).trimEnd('/') else ""

        val hostMatches = host == ruleHost || host.endsWith(".$ruleHost")
        if (!hostMatches) return false
        if (rulePath.isEmpty()) return true
        return path == rulePath || path.startsWith("$rulePath/")
    }

    fun evaluate(
        snapshot: ScreenSnapshot,
        config: BrowserHardeningConfig,
        browserPackages: Set<String> = KNOWN_BROWSER_PACKAGES
    ): BlockMatch? {
        if (!config.enabled) return null
        val pkg = snapshot.packageName

        if (config.blockTikTok && pkg in TIKTOK_PACKAGES) {
            return BlockMatch("tiktok_app", EnforcementAction.GO_HOME)
        }

        val matchedSuffixes = snapshot.matchedViewIds
            .mapTo(HashSet()) { it.substringAfter(":id/", it) }

        if (config.blockYouTubeShorts && pkg in YOUTUBE_PACKAGES) {
            if (isYouTubeShortsScreen(snapshot, matchedSuffixes)) {
                return BlockMatch("youtube_shorts_app", EnforcementAction.PRESS_BACK)
            }
        }

        if (pkg in INSTAGRAM_PACKAGES) {
            if (config.blockInstagramReels && isInstagramReelsScreen(snapshot, matchedSuffixes)) {
                return BlockMatch("instagram_reels_app", EnforcementAction.PRESS_BACK)
            }
            if (config.blockInstagramExplore && isInstagramExploreScreen(snapshot, matchedSuffixes)) {
                return BlockMatch("instagram_explore_app", EnforcementAction.PRESS_BACK)
            }
        }

        if (config.blockFacebookReels && pkg in FACEBOOK_PACKAGES) {
            if (isFacebookReelsScreen(snapshot, matchedSuffixes)) {
                return BlockMatch("facebook_reels_app", EnforcementAction.PRESS_BACK)
            }
        }

        if (config.blockSnapchatSpotlight && pkg in SNAPCHAT_PACKAGES) {
            if (isSnapchatSpotlightScreen(snapshot, matchedSuffixes)) {
                return BlockMatch("snapchat_spotlight_app", EnforcementAction.PRESS_BACK)
            }
        }

        if (pkg in browserPackages || pkg in KNOWN_BROWSER_PACKAGES) {
            if (config.lockBrowserDnsSettings && isBrowserDnsSettingsScreen(snapshot)) {
                return BlockMatch("browser_dns_settings", EnforcementAction.PRESS_BACK)
            }
            if (config.disableIncognito && matchedSuffixes.any { it in BROWSER_INCOGNITO_VIEW_IDS }) {
                return BlockMatch("browser_incognito", EnforcementAction.PRESS_BACK)
            }
            val parsedUrl = extractHostAndPath(snapshot.urlBarText)
            if (parsedUrl != null) {
                val (host, path) = parsedUrl
                val rules = BrowserHardeningManager.buildUrlBlocklist(config)
                val hit = rules.firstOrNull { rule -> matchesUrlRule(host, path, rule) }
                if (hit != null) {
                    return BlockMatch("browser_url:$hit", EnforcementAction.PRESS_BACK)
                }
            }
        }

        return null
    }

    private fun isYouTubeShortsScreen(snapshot: ScreenSnapshot, matchedSuffixes: Set<String>): Boolean {
        if (matchedSuffixes.any { it in YOUTUBE_SHORTS_VIEW_IDS }) return true
        if (snapshot.selectedTabLabels.any { it.equals("shorts", ignoreCase = true) }) return true
        val combined = combinedLowerTexts(snapshot)
        return combined.any {
            it.contains("remix this short") || it.contains("play next short")
        }
    }

    private fun isInstagramReelsScreen(snapshot: ScreenSnapshot, matchedSuffixes: Set<String>): Boolean {
        if (matchedSuffixes.any { it in INSTAGRAM_REELS_VIEW_IDS }) return true
        if (snapshot.selectedTabLabels.any { it.equals("reels", ignoreCase = true) }) return true
        val combined = combinedLowerTexts(snapshot)
        return combined.any { it.contains("reels camera") }
    }

    private fun isInstagramExploreScreen(snapshot: ScreenSnapshot, matchedSuffixes: Set<String>): Boolean {
        if (matchedSuffixes.any { it in INSTAGRAM_EXPLORE_VIEW_IDS }) return true
        return snapshot.selectedTabLabels.any {
            it.equals("search and explore", ignoreCase = true) || it.equals("explore", ignoreCase = true)
        }
    }

    private fun isFacebookReelsScreen(snapshot: ScreenSnapshot, matchedSuffixes: Set<String>): Boolean {
        if (matchedSuffixes.any { it in FACEBOOK_REELS_VIEW_IDS }) return true
        return snapshot.selectedTabLabels.any { label ->
            val l = label.lowercase()
            l.startsWith("reels") || l.startsWith("video") || l.startsWith("watch")
        }
    }

    private fun isSnapchatSpotlightScreen(snapshot: ScreenSnapshot, matchedSuffixes: Set<String>): Boolean {
        if (matchedSuffixes.any { it in SNAPCHAT_SPOTLIGHT_VIEW_IDS }) return true
        return snapshot.selectedTabLabels.any { it.equals("spotlight", ignoreCase = true) }
    }

    private fun isBrowserDnsSettingsScreen(snapshot: ScreenSnapshot): Boolean {
        val rawUrl = snapshot.urlBarText?.trim()?.lowercase().orEmpty()
        if (rawUrl.isNotEmpty() && INTERNAL_DNS_URL_PATTERNS.any { rawUrl.startsWith(it) || rawUrl.contains(it) }) {
            return true
        }
        // Check live web page URL BEFORE DNS_CLASS_TOKENS so a stale settings class never blocks normal browsing.
        if (isWebPageUrl(snapshot.urlBarText)) return false

        val lowerClass = snapshot.className.lowercase()
        if (DNS_CLASS_TOKENS.any { lowerClass.contains(it) }) {
            return true
        }

        val combined = combinedLowerTexts(snapshot)
        if (combined.any { text -> STRONG_DNS_SETTINGS_PHRASES.any { phrase -> text.contains(phrase) } }) {
            return true
        }
        val hasDnsWord = combined.any { it.contains("dns") }
        val hasDnsControl = combined.any { text ->
            text.contains("choose another provider") ||
                text.contains("max protection") ||
                text.contains("increased protection") ||
                text.contains("default protection") ||
                LANGUAGE_NEUTRAL_DNS_TOKENS.any { text.contains(it) }
        }
        return hasDnsWord && hasDnsControl
    }

    private fun combinedLowerTexts(snapshot: ScreenSnapshot): List<String> = buildList(
        snapshot.visibleTexts.size + snapshot.contentDescriptions.size
    ) {
        snapshot.visibleTexts.forEach { if (it.isNotBlank()) add(it.trim().lowercase()) }
        snapshot.contentDescriptions.forEach { if (it.isNotBlank()) add(it.trim().lowercase()) }
    }
}
