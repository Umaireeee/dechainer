package io.github.warleysr.dechainer.data

import android.content.Context
import android.content.Intent
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.Executors

data class BrowserHardeningConfig(
    val enabled: Boolean = false,
    val disableIncognito: Boolean = false,
    val forceFamilyDns: Boolean = false,
    val familyDnsTemplate: String = BrowserHardeningManager.DEFAULT_FAMILY_DNS_TEMPLATE,
    val lockBrowserDnsSettings: Boolean = false,
    val forceSafeSearch: Boolean = false,
    val forceYouTubeRestricted: Boolean = false,
    val disableBraveVpn: Boolean = false,
    val blockYouTubeShorts: Boolean = false,
    val blockInstagramReels: Boolean = false,
    val blockInstagramExplore: Boolean = false,
    val blockFacebookReels: Boolean = false,
    val blockSnapchatSpotlight: Boolean = false,
    val blockTikTok: Boolean = false,
    val blockAdultSites: Boolean = false,
    val customBlockedUrls: Set<String> = emptySet()
) {
    fun needsBrowserAccessibility(): Boolean = enabled && (
        lockBrowserDnsSettings ||
            disableIncognito ||
            blockYouTubeShorts ||
            blockInstagramReels ||
            blockInstagramExplore ||
            blockFacebookReels ||
            blockTikTok ||
            blockAdultSites ||
            customBlockedUrls.isNotEmpty()
        )

    fun needsSocialAppAccessibility(): Boolean = enabled && (
        blockYouTubeShorts ||
            blockInstagramReels ||
            blockInstagramExplore ||
            blockFacebookReels ||
            blockSnapchatSpotlight ||
            blockTikTok
        )

    fun needsAccessibility(): Boolean = needsBrowserAccessibility() || needsSocialAppAccessibility()

    fun isLooserThan(old: BrowserHardeningConfig): Boolean {
        if (old.enabled && !enabled) return true
        if (!enabled) return false
        if (old.disableIncognito && !disableIncognito) return true
        if (old.forceFamilyDns && !forceFamilyDns) return true
        if (old.forceFamilyDns && forceFamilyDns && !familyDnsTemplate.equals(old.familyDnsTemplate, ignoreCase = true)) {
            return true
        }
        if (old.lockBrowserDnsSettings && !lockBrowserDnsSettings) return true
        if (old.forceSafeSearch && !forceSafeSearch) return true
        if (old.forceYouTubeRestricted && !forceYouTubeRestricted) return true
        if (old.disableBraveVpn && !disableBraveVpn) return true
        if (old.blockYouTubeShorts && !blockYouTubeShorts) return true
        if (old.blockInstagramReels && !blockInstagramReels) return true
        if (old.blockInstagramExplore && !blockInstagramExplore) return true
        if (old.blockFacebookReels && !blockFacebookReels) return true
        if (old.blockSnapchatSpotlight && !blockSnapchatSpotlight) return true
        if (old.blockTikTok && !blockTikTok) return true
        if (old.blockAdultSites && !blockAdultSites) return true
        if (!customBlockedUrls.containsAll(old.customBlockedUrls)) return true
        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("disableIncognito", disableIncognito)
        put("forceFamilyDns", forceFamilyDns)
        put("familyDnsTemplate", familyDnsTemplate)
        put("lockBrowserDnsSettings", lockBrowserDnsSettings)
        put("forceSafeSearch", forceSafeSearch)
        put("forceYouTubeRestricted", forceYouTubeRestricted)
        put("disableBraveVpn", disableBraveVpn)
        put("blockYouTubeShorts", blockYouTubeShorts)
        put("blockInstagramReels", blockInstagramReels)
        put("blockInstagramExplore", blockInstagramExplore)
        put("blockFacebookReels", blockFacebookReels)
        put("blockSnapchatSpotlight", blockSnapchatSpotlight)
        put("blockTikTok", blockTikTok)
        put("blockAdultSites", blockAdultSites)
        put("customBlockedUrls", JSONArray(customBlockedUrls.sorted()))
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun normalizeRule(input: String): String {
            var clean = input.trim().lowercase()
            if (clean.isEmpty() || clean.any { it.isWhitespace() }) return ""
            clean = clean.removePrefix("https://").removePrefix("http://")
            clean = clean.removePrefix("www.")
            val qIdx = clean.indexOf('?')
            if (qIdx >= 0) clean = clean.substring(0, qIdx)
            val hIdx = clean.indexOf('#')
            if (hIdx >= 0) clean = clean.substring(0, hIdx)
            clean = clean.trimEnd('/')
            if (clean.isEmpty() || !clean.contains('.')) return ""
            return clean
        }

        fun fromJson(obj: JSONObject): BrowserHardeningConfig {
            val arr = obj.optJSONArray("customBlockedUrls")
            val urls = if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    normalizeRule(arr.optString(i, "")).takeIf { it.isNotEmpty() }
                }.toSet()
            } else {
                emptySet()
            }
            val template = obj.optString("familyDnsTemplate", "")
                .trim()
                .takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: BrowserHardeningManager.DEFAULT_FAMILY_DNS_TEMPLATE
            return BrowserHardeningConfig(
                enabled = obj.optBoolean("enabled", false),
                disableIncognito = obj.optBoolean("disableIncognito", false),
                forceFamilyDns = obj.optBoolean("forceFamilyDns", false),
                familyDnsTemplate = template,
                lockBrowserDnsSettings = obj.optBoolean("lockBrowserDnsSettings", false),
                forceSafeSearch = obj.optBoolean("forceSafeSearch", false),
                forceYouTubeRestricted = obj.optBoolean("forceYouTubeRestricted", false),
                disableBraveVpn = obj.optBoolean(
                    "disableBraveVpn",
                    obj.optBoolean("disableBraveTor", false)
                ),
                blockYouTubeShorts = obj.optBoolean("blockYouTubeShorts", false),
                blockInstagramReels = obj.optBoolean("blockInstagramReels", false),
                blockInstagramExplore = obj.optBoolean("blockInstagramExplore", false),
                blockFacebookReels = obj.optBoolean("blockFacebookReels", false),
                blockSnapchatSpotlight = obj.optBoolean("blockSnapchatSpotlight", false),
                blockTikTok = obj.optBoolean("blockTikTok", false),
                blockAdultSites = obj.optBoolean("blockAdultSites", false),
                customBlockedUrls = urls
            )
        }

        fun fromJsonString(raw: String?): BrowserHardeningConfig? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}

data class DetectedBrowser(
    val packageName: String,
    val label: String,
    val isManaged: Boolean,
    val isBrave: Boolean,
    val isSuspended: Boolean,
    val isHidden: Boolean,
    val appliedPolicyCount: Int = 0
)

object BrowserHardeningManager {
    const val PREFS = "browser_hardening_prefs"
    private const val KEY_CONFIG = "config_json"

    private val policyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "browser-policy").apply { isDaemon = true }
    }

    val FAMILY_DNS_PRESETS: List<Pair<String, String>> = listOf(
        "Cloudflare Family" to "https://family.cloudflare-dns.com/dns-query",
        "AdGuard Family" to "https://family.adguard-dns.com/dns-query",
        "CleanBrowsing Family" to "https://doh.cleanbrowsing.org/doh/family-filter/",
        "CleanBrowsing Adult" to "https://doh.cleanbrowsing.org/doh/adult-filter/",
        "Control D Family" to "https://freedns.controld.com/family"
    )

    val DEFAULT_FAMILY_DNS_TEMPLATE: String = FAMILY_DNS_PRESETS.first().second

    val MANAGED_BROWSER_PACKAGES = setOf(
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary"
    )

    internal val MANAGED_POLICY_KEYS = setOf(
        "URLBlocklist",
        "IncognitoModeAvailability",
        "DnsOverHttpsMode",
        "DnsOverHttpsTemplates",
        "ForceGoogleSafeSearch",
        "ForceYouTubeRestrict",
        "BraveVPNDisabled"
    )

    internal val YOUTUBE_SHORTS_RULES = listOf(
        "youtube.com/shorts",
        "m.youtube.com/shorts",
        "youtu.be/shorts",
        "youtube.com/feed/shorts"
    )

    internal val INSTAGRAM_REELS_RULES = listOf(
        "instagram.com/reels",
        "instagram.com/reel",
        "instagr.am/reel",
        "instagr.am/reels"
    )

    internal val INSTAGRAM_EXPLORE_RULES = listOf(
        "instagram.com/explore"
    )

    internal val FACEBOOK_REELS_RULES = listOf(
        "facebook.com/reel",
        "facebook.com/reels",
        "m.facebook.com/reel",
        "m.facebook.com/reels",
        "facebook.com/watch",
        "m.facebook.com/watch",
        "fb.watch"
    )

    internal val TIKTOK_RULES = listOf(
        "tiktok.com",
        "m.tiktok.com",
        "vm.tiktok.com",
        "vt.tiktok.com"
    )

    internal val TOP_ADULT_DOMAINS = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "spankbang.com", "eporner.com", "hqporner.com", "tube8.com",
        "txxx.com", "beeg.com", "erome.com", "fapello.com", "coomer.su", "kemono.su",
        "onlyfans.com", "fansly.com", "chaturbate.com", "stripchat.com", "bongacams.com",
        "livejasmin.com", "myfreecams.com", "camsoda.com", "brazzers.com", "bangbros.com",
        "rule34.xxx", "nhentai.net", "hanime.tv", "hentaihaven.xxx", "hitomi.la",
        "missav.com", "javhd.com", "redgifs.com", "scrolller.com", "motherless.com",
        "youjizz.com", "sex.com", "porntrex.com", "sxyprn.com", "thothub.to",
        "simpcity.su", "influencersgonewild.com", "dirtyship.com", "efukt.com",
        "kaotic.com", "heavy-r.com", "literotica.com", "fetlife.com", "e-hentai.org",
        "danbooru.donmai.us", "gelbooru.com", "e621.net", "ixxx.com", "fuq.com",
        "gotporn.com", "thisvid.com", "nuvid.com", "slutload.com", "keezmovies.com",
        "extremetube.com", "sunporno.com", "porndig.com", "daftsex.com", "anyporn.com"
    )

    private val _config = MutableStateFlow(BrowserHardeningConfig())
    val config: StateFlow<BrowserHardeningConfig> = _config.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfig(context: Context): BrowserHardeningConfig {
        val loaded = BrowserHardeningConfig.fromJsonString(prefs(context).getString(KEY_CONFIG, null))
            ?: BrowserHardeningConfig()
        _config.value = loaded
        return loaded
    }

    fun buildUrlBlocklist(config: BrowserHardeningConfig): List<String> {
        val result = LinkedHashSet<String>()
        if (config.blockYouTubeShorts) result.addAll(YOUTUBE_SHORTS_RULES)
        if (config.blockInstagramReels) result.addAll(INSTAGRAM_REELS_RULES)
        if (config.blockInstagramExplore) result.addAll(INSTAGRAM_EXPLORE_RULES)
        if (config.blockFacebookReels) result.addAll(FACEBOOK_REELS_RULES)
        if (config.blockTikTok) result.addAll(TIKTOK_RULES)
        if (config.blockAdultSites) result.addAll(TOP_ADULT_DOMAINS)
        config.customBlockedUrls
            .map { BrowserHardeningConfig.normalizeRule(it) }
            .filter { it.isNotEmpty() }
            .forEach { result.add(it) }
        return result.toList()
    }

    /**
     * Produces the full restrictions [Bundle] for a Chromium browser while preserving unrelated
     * policies already present in [existing].
     */
    fun buildRestrictionsBundle(
        existing: Bundle,
        config: BrowserHardeningConfig,
        manifestTypes: Map<String, Int>,
        isBravePackage: Boolean = false
    ): Bundle {
        val out = Bundle(existing)
        MANAGED_POLICY_KEYS.forEach { out.remove(it) }
        // Clean up any legacy desktop Tor key that older Dechainer versions may have pushed.
        out.remove("TorDisabled")
        if (!config.enabled) return out

        val urls = buildUrlBlocklist(config)
        if (urls.isNotEmpty()) {
            if (manifestTypes["URLBlocklist"] == RestrictionEntry.TYPE_MULTI_SELECT) {
                out.putStringArray("URLBlocklist", urls.toTypedArray())
            } else {
                // Chromium Android's PolicyConverter expects URLBlocklist as a JSON array string
                // (used when manifestTypes is empty or TYPE_STRING, as in Brave/Chrome).
                out.putString("URLBlocklist", JSONArray(urls).toString().replace("\\/", "/"))
            }
        }

        if (config.disableIncognito) {
            val type = manifestTypes["IncognitoModeAvailability"]
            if (type == RestrictionEntry.TYPE_STRING || type == RestrictionEntry.TYPE_CHOICE) {
                out.putString("IncognitoModeAvailability", "1")
            } else {
                out.putInt("IncognitoModeAvailability", 1)
            }
        }

        if (config.forceFamilyDns) {
            val template = config.familyDnsTemplate.trim()
                .takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: DEFAULT_FAMILY_DNS_TEMPLATE
            out.putString("DnsOverHttpsMode", "secure")
            out.putString("DnsOverHttpsTemplates", template)
        }

        if (config.forceSafeSearch) {
            out.putBoolean("ForceGoogleSafeSearch", true)
        }

        if (config.forceYouTubeRestricted) {
            val type = manifestTypes["ForceYouTubeRestrict"]
            if (type == RestrictionEntry.TYPE_STRING || type == RestrictionEntry.TYPE_CHOICE) {
                out.putString("ForceYouTubeRestrict", "2")
            } else {
                out.putInt("ForceYouTubeRestrict", 2)
            }
        }

        if (config.disableBraveVpn && (isBravePackage || "BraveVPNDisabled" in manifestTypes)) {
            out.putBoolean("BraveVPNDisabled", true)
        }

        return out
    }

    fun getInstalledBrowserPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (resolved + ContentBlockerEngine.KNOWN_BROWSER_PACKAGES)
            .filterTo(LinkedHashSet()) { it != context.packageName }
    }

    fun detectBrowsers(context: Context): List<DetectedBrowser> {
        val pm = context.packageManager
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val isOwner = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolvedPackages = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList()).toMutableSet()
        resolvedPackages.addAll(MANAGED_BROWSER_PACKAGES)
        resolvedPackages.remove(context.packageName)

        return resolvedPackages.mapNotNull { pkg ->
            val appInfo = runCatching {
                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }.getOrNull() ?: return@mapNotNull null

            val isHidden = isOwner && runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            val isInstalled = (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            if (!isInstalled && !isHidden) return@mapNotNull null

            val manifestKeys = runCatching {
                rm?.getManifestRestrictions(pkg)?.map { it.key }.orEmpty()
            }.getOrDefault(emptyList())

            val hasSchemaKeys = "URLBlocklist" in manifestKeys || "IncognitoModeAvailability" in manifestKeys
            val isManaged = if (rm != null && manifestKeys.isNotEmpty()) {
                hasSchemaKeys
            } else {
                pkg in MANAGED_BROWSER_PACKAGES
            }
            val isBrave = pkg.startsWith("com.brave.browser")
            val isSuspended = isOwner && runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
            val appliedCount = if (isOwner && isManaged) {
                val liveBundle = runCatching { dpm.getApplicationRestrictions(admin, pkg) }.getOrNull()
                if (liveBundle != null) {
                    val supportedKeys = if (manifestKeys.isNotEmpty()) {
                        MANAGED_POLICY_KEYS intersect manifestKeys.toSet()
                    } else {
                        MANAGED_POLICY_KEYS
                    }
                    supportedKeys.count { liveBundle.containsKey(it) }
                } else {
                    0
                }
            } else {
                0
            }

            DetectedBrowser(
                packageName = pkg,
                label = appInfo.loadLabel(pm).toString(),
                isManaged = isManaged,
                isBrave = isBrave,
                isSuspended = isSuspended,
                isHidden = isHidden,
                appliedPolicyCount = appliedCount
            )
        }.sortedWith(compareByDescending<DetectedBrowser> { it.isBrave }.thenByDescending { it.isManaged }.thenBy { it.label.lowercase() })
    }

    fun applyConfig(context: Context, config: BrowserHardeningConfig) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, config.toJsonString()) }
        _config.value = config
        if (Looper.myLooper() == Looper.getMainLooper()) {
            policyExecutor.execute { pushToManagedBrowsers(ctx, config) }
        } else {
            pushToManagedBrowsers(ctx, config)
        }
    }

    /**
     * Applies either a granular patch (`{"_patch":true,...}`) onto the live config so concurrent
     * tightenings are never wiped, or a legacy full-config JSON payload.
     */
    fun applyPatch(context: Context, payloadJson: String): Boolean {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return false
        if (!obj.optBoolean("_patch", false)) {
            val legacy = BrowserHardeningConfig.fromJson(obj)
            applyConfig(context, legacy)
            return true
        }
        val current = getConfig(context)
        var updated = current
        if (obj.has("enabled")) updated = updated.copy(enabled = obj.getBoolean("enabled"))
        if (obj.has("disableIncognito")) updated = updated.copy(disableIncognito = obj.getBoolean("disableIncognito"))
        if (obj.has("forceFamilyDns")) updated = updated.copy(forceFamilyDns = obj.getBoolean("forceFamilyDns"))
        if (obj.has("familyDnsTemplate")) {
            val tpl = obj.optString("familyDnsTemplate", "").trim()
            if (tpl.startsWith("https://", ignoreCase = true)) {
                updated = updated.copy(familyDnsTemplate = tpl)
            }
        }
        if (obj.has("lockBrowserDnsSettings")) updated = updated.copy(lockBrowserDnsSettings = obj.getBoolean("lockBrowserDnsSettings"))
        if (obj.has("forceSafeSearch")) updated = updated.copy(forceSafeSearch = obj.getBoolean("forceSafeSearch"))
        if (obj.has("forceYouTubeRestricted")) updated = updated.copy(forceYouTubeRestricted = obj.getBoolean("forceYouTubeRestricted"))
        if (obj.has("disableBraveVpn")) updated = updated.copy(disableBraveVpn = obj.getBoolean("disableBraveVpn"))
        if (obj.has("blockYouTubeShorts")) updated = updated.copy(blockYouTubeShorts = obj.getBoolean("blockYouTubeShorts"))
        if (obj.has("blockInstagramReels")) updated = updated.copy(blockInstagramReels = obj.getBoolean("blockInstagramReels"))
        if (obj.has("blockInstagramExplore")) updated = updated.copy(blockInstagramExplore = obj.getBoolean("blockInstagramExplore"))
        if (obj.has("blockFacebookReels")) updated = updated.copy(blockFacebookReels = obj.getBoolean("blockFacebookReels"))
        if (obj.has("blockSnapchatSpotlight")) updated = updated.copy(blockSnapchatSpotlight = obj.getBoolean("blockSnapchatSpotlight"))
        if (obj.has("blockTikTok")) updated = updated.copy(blockTikTok = obj.getBoolean("blockTikTok"))
        if (obj.has("blockAdultSites")) updated = updated.copy(blockAdultSites = obj.getBoolean("blockAdultSites"))
        if (obj.has("removeCustomUrl")) {
            val norm = BrowserHardeningConfig.normalizeRule(obj.getString("removeCustomUrl"))
            if (norm.isNotEmpty()) {
                updated = updated.copy(customBlockedUrls = updated.customBlockedUrls - norm)
            }
        }
        applyConfig(context, updated)
        return true
    }

    fun reapplyIfEnabled(context: Context) {
        val cfg = getConfig(context)
        if (cfg.enabled) {
            pushToManagedBrowsers(context, cfg)
        }
    }

    fun reapplyNow(context: Context) {
        val ctx = context.applicationContext
        val cfg = getConfig(ctx)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            policyExecutor.execute { pushToManagedBrowsers(ctx, cfg) }
        } else {
            pushToManagedBrowsers(ctx, cfg)
        }
    }

    fun releaseForDeviceOwnerRemoval(context: Context) {
        val disabled = BrowserHardeningConfig(enabled = false)
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, disabled.toJsonString()) }
        _config.value = disabled
        pushToManagedBrowsers(ctx, disabled)
    }

    private fun pushToManagedBrowsers(context: Context, config: BrowserHardeningConfig) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)) return

        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
        val targets = detectBrowsers(context).filter { it.isManaged }
        for (browser in targets) {
            val manifestTypes = runCatching {
                rm?.getManifestRestrictions(browser.packageName)
                    ?.associate { it.key to it.type }
                    .orEmpty()
            }.getOrDefault(emptyMap())
            val existing = runCatching { dpm.getApplicationRestrictions(admin, browser.packageName) }
                .getOrDefault(Bundle())
            val updated = buildRestrictionsBundle(
                existing = existing,
                config = config,
                manifestTypes = manifestTypes,
                isBravePackage = browser.isBrave
            )
            runCatching {
                dpm.setApplicationRestrictions(admin, browser.packageName, updated)
            }.onFailure {
                Timber.w(it, "Failed setting managed policies for ${browser.packageName}")
            }
        }
    }
}
