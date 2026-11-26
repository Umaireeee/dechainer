package io.github.warleysr.dechainer.data

import android.content.Context
import android.content.Intent
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class BrowserHardeningConfig(
    val enabled: Boolean = false,
    val disableIncognito: Boolean = true,
    val disableDoH: Boolean = true,
    val forceSafeSearch: Boolean = true,
    val forceYouTubeRestricted: Boolean = false,
    val disableBraveTor: Boolean = true,
    val blockYouTubeShorts: Boolean = true,
    val blockInstagramReels: Boolean = true,
    val blockFacebookReels: Boolean = true,
    val blockTikTok: Boolean = true,
    val blockAdultSites: Boolean = true,
    val customBlockedUrls: Set<String> = emptySet()
) {
    fun isLooserThan(old: BrowserHardeningConfig): Boolean {
        if (old.enabled && !enabled) return true
        if (!enabled) return false
        if (old.disableIncognito && !disableIncognito) return true
        if (old.disableDoH && !disableDoH) return true
        if (old.forceSafeSearch && !forceSafeSearch) return true
        if (old.forceYouTubeRestricted && !forceYouTubeRestricted) return true
        if (old.disableBraveTor && !disableBraveTor) return true
        if (old.blockYouTubeShorts && !blockYouTubeShorts) return true
        if (old.blockInstagramReels && !blockInstagramReels) return true
        if (old.blockFacebookReels && !blockFacebookReels) return true
        if (old.blockTikTok && !blockTikTok) return true
        if (old.blockAdultSites && !blockAdultSites) return true
        if (!customBlockedUrls.containsAll(old.customBlockedUrls)) return true
        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("disableIncognito", disableIncognito)
        put("disableDoH", disableDoH)
        put("forceSafeSearch", forceSafeSearch)
        put("forceYouTubeRestricted", forceYouTubeRestricted)
        put("disableBraveTor", disableBraveTor)
        put("blockYouTubeShorts", blockYouTubeShorts)
        put("blockInstagramReels", blockInstagramReels)
        put("blockFacebookReels", blockFacebookReels)
        put("blockTikTok", blockTikTok)
        put("blockAdultSites", blockAdultSites)
        put("customBlockedUrls", JSONArray(customBlockedUrls.sorted()))
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun normalizeRule(input: String): String {
            var clean = input.trim().lowercase()
            clean = clean.removePrefix("https://").removePrefix("http://")
            clean = clean.trimEnd('/')
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
            return BrowserHardeningConfig(
                enabled = obj.optBoolean("enabled", false),
                disableIncognito = obj.optBoolean("disableIncognito", true),
                disableDoH = obj.optBoolean("disableDoH", true),
                forceSafeSearch = obj.optBoolean("forceSafeSearch", true),
                forceYouTubeRestricted = obj.optBoolean("forceYouTubeRestricted", false),
                disableBraveTor = obj.optBoolean("disableBraveTor", true),
                blockYouTubeShorts = obj.optBoolean("blockYouTubeShorts", true),
                blockInstagramReels = obj.optBoolean("blockInstagramReels", true),
                blockFacebookReels = obj.optBoolean("blockFacebookReels", true),
                blockTikTok = obj.optBoolean("blockTikTok", true),
                blockAdultSites = obj.optBoolean("blockAdultSites", true),
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
    val isHidden: Boolean
)

object BrowserHardeningManager {
    private const val PREFS = "browser_hardening_prefs"
    private const val KEY_CONFIG = "config_json"

    val MANAGED_BROWSER_PACKAGES = setOf(
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta"
    )

    private val MANAGED_POLICY_KEYS = setOf(
        "URLBlocklist",
        "IncognitoModeAvailability",
        "DnsOverHttpsMode",
        "ForceGoogleSafeSearch",
        "ForceYouTubeRestrict",
        "TorDisabled"
    )

    private val YOUTUBE_SHORTS_RULES = listOf(
        "youtube.com/shorts",
        "m.youtube.com/shorts",
        "youtu.be/shorts",
        "youtube.com/feed/shorts"
    )

    private val INSTAGRAM_REELS_RULES = listOf(
        "instagram.com/reels",
        "instagram.com/reel",
        "instagram.com/explore"
    )

    private val FACEBOOK_REELS_RULES = listOf(
        "facebook.com/reel",
        "facebook.com/reels",
        "m.facebook.com/reel",
        "m.facebook.com/reels",
        "facebook.com/watch",
        "m.facebook.com/watch"
    )

    private val TIKTOK_RULES = listOf(
        "tiktok.com",
        "m.tiktok.com"
    )

    private val TOP_ADULT_DOMAINS = listOf(
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
        if (!config.enabled) return out

        val urls = buildUrlBlocklist(config)
        if (urls.isNotEmpty()) {
            if (manifestTypes["URLBlocklist"] == RestrictionEntry.TYPE_STRING) {
                out.putString("URLBlocklist", JSONArray(urls).toString())
            } else {
                out.putStringArray("URLBlocklist", urls.toTypedArray())
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

        if (config.disableDoH) {
            out.putString("DnsOverHttpsMode", "off")
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

        if (config.disableBraveTor && (isBravePackage || "TorDisabled" in manifestTypes)) {
            out.putBoolean("TorDisabled", true)
        }

        return out
    }

    fun detectBrowsers(context: Context): List<DetectedBrowser> {
        val pm = context.packageManager
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        val isOwner = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolvedPackages = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toMutableSet()
        resolvedPackages.addAll(MANAGED_BROWSER_PACKAGES)
        resolvedPackages.remove(context.packageName)

        return resolvedPackages.mapNotNull { pkg ->
            val appInfo = runCatching {
                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }.getOrNull() ?: return@mapNotNull null

            val manifestKeys = runCatching {
                rm?.getManifestRestrictions(pkg)?.map { it.key }.orEmpty()
            }.getOrDefault(emptyList())

            val isManaged = pkg in MANAGED_BROWSER_PACKAGES ||
                "URLBlocklist" in manifestKeys ||
                "IncognitoModeAvailability" in manifestKeys
            val isBrave = pkg.startsWith("com.brave.browser")
            val isSuspended = isOwner && runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
            val isHidden = isOwner && runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)

            DetectedBrowser(
                packageName = pkg,
                label = appInfo.loadLabel(pm).toString(),
                isManaged = isManaged,
                isBrave = isBrave,
                isSuspended = isSuspended,
                isHidden = isHidden
            )
        }.sortedWith(compareByDescending<DetectedBrowser> { it.isBrave }.thenByDescending { it.isManaged }.thenBy { it.label.lowercase() })
    }

    fun applyConfig(context: Context, config: BrowserHardeningConfig) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { putString(KEY_CONFIG, config.toJsonString()) }
        _config.value = config
        pushToManagedBrowsers(ctx, config)
    }

    fun reapplyIfEnabled(context: Context) {
        val cfg = getConfig(context)
        if (cfg.enabled) {
            pushToManagedBrowsers(context, cfg)
        }
    }

    fun releaseForDeviceOwnerRemoval(context: Context) {
        val disabled = BrowserHardeningConfig(enabled = false)
        applyConfig(context, disabled)
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
