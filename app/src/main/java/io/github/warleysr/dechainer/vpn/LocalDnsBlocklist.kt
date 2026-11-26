package io.github.warleysr.dechainer.vpn

import android.content.Context
import io.github.warleysr.dechainer.R

sealed interface DnsDecision {
    data object Allow : DnsDecision
    data class Block(val reason: String) : DnsDecision
    data class Rewrite(val targetCname: String) : DnsDecision
}

/**
 * Pure on-device DNS classifier for adult domains, explicit domain keywords, DoH bypass
 * endpoints, TikTok, SafeSearch CNAME rewrites, YouTube Restricted Mode, and custom blocklists.
 */
object LocalDnsBlocklist {

    private val DOH_BYPASS_HOSTS = setOf(
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "dns.cloudflare.com",
        "one.one.one.one",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.adguard-dns.com",
        "dns.adguard.com",
        "dns.nextdns.io",
        "doh.opendns.com",
        "dns.opendns.com",
        "doh.cleanbrowsing.org",
        "freedns.controld.com",
        "dns.controld.com",
        "dns.mullvad.net",
        "doh.dns.sb"
    )

    private val TIKTOK_DOMAINS = setOf(
        "tiktok.com",
        "tiktokv.com",
        "tiktokcdn.com",
        "tiktokcdn-us.com",
        "musical.ly",
        "byteoversea.com",
        "ibytedtos.com"
    )

    private val EXPLICIT_KEYWORDS = listOf(
        "porn", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "spankbang",
        "brazzers", "bangbros", "eporner", "hqporner", "hentai", "rule34", "nhentai",
        "chaturbate", "stripchat", "bongacams", "livejasmin", "myfreecams", "camsoda",
        "onlyfans", "fansly", "fapello", "erome", "pornhub", "camgirl", "livecam",
        "sexcam", "sexvideo", "sexchat", "milf", "nsfw", "boobs", "pussy",
        "blowjob", "handjob", "creampie", "gangbang", "shemale", "javhd", "missav",
        "hanime", "escort", "xxx"
    )

    private val SAFE_ALLOWLIST = setOf(
        "google.com", "youtube.com", "youtu.be", "wikipedia.org", "wikimedia.org",
        "github.com", "githubusercontent.com", "stackoverflow.com", "android.com",
        "googleapis.com", "gstatic.com", "brave.com", "microsoft.com", "bing.com",
        "duckduckgo.com", "apple.com", "whatsapp.com", "whatsapp.net", "zoom.us",
        "essex.ac.uk", "sussex.ac.uk", "middlesex.edu", "classic.com"
    )

    private val GOOGLE_SEARCH_REGEX =
        Regex("^(www\\.)?google\\.([a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    @Volatile
    private var bundledAdultDomains: Set<String> = emptySet()

    fun ensureLoaded(context: Context) {
        if (bundledAdultDomains.isNotEmpty()) return
        synchronized(this) {
            if (bundledAdultDomains.isNotEmpty()) return
            bundledAdultDomains = context.resources.openRawResource(R.raw.adult_domains)
                .bufferedReader()
                .useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
        }
    }

    internal fun matchesAnySuffix(domain: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var current = domain
        while (current.isNotEmpty()) {
            if (current in domains) return true
            val dot = current.indexOf('.')
            if (dot < 0) break
            current = current.substring(dot + 1)
        }
        return false
    }

    fun evaluate(
        rawDomain: String,
        config: LocalDnsConfig,
        adultDomains: Set<String> = bundledAdultDomains
    ): DnsDecision {
        val domain = rawDomain.trim().lowercase().trimEnd('.')
        if (domain.isEmpty()) return DnsDecision.Allow

        if (matchesAnySuffix(domain, config.customBlockedDomains)) {
            return DnsDecision.Block("custom")
        }

        if (config.blockTikTok && matchesAnySuffix(domain, TIKTOK_DOMAINS)) {
            return DnsDecision.Block("tiktok")
        }

        if (config.blockDoHBypass && matchesAnySuffix(domain, DOH_BYPASS_HOSTS)) {
            return DnsDecision.Block("doh_bypass")
        }

        if (config.blockAdultDomains && matchesAnySuffix(domain, adultDomains)) {
            return DnsDecision.Block("adult_domain")
        }

        if (config.blockAdultKeywords && !matchesAnySuffix(domain, SAFE_ALLOWLIST)) {
            val labels = domain.split('.')
            val hasExplicitToken = labels.any { label ->
                EXPLICIT_KEYWORDS.any { kw ->
                    if (kw == "xxx") label == "xxx" || label.startsWith("xxx") || label.endsWith("xxx")
                    else label.contains(kw)
                }
            }
            if (hasExplicitToken) {
                return DnsDecision.Block("adult_keyword")
            }
        }

        if (config.forceSafeSearch) {
            if (GOOGLE_SEARCH_REGEX.matches(domain)) {
                return DnsDecision.Rewrite("forcesafesearch.google.com")
            }
            if (domain == "bing.com" || domain == "www.bing.com") {
                return DnsDecision.Rewrite("strict.bing.com")
            }
            if (domain == "duckduckgo.com" || domain == "www.duckduckgo.com") {
                return DnsDecision.Rewrite("safe.duckduckgo.com")
            }
        }

        if (config.forceYouTubeRestricted) {
            if (domain == "youtube.com" ||
                domain == "www.youtube.com" ||
                domain == "m.youtube.com" ||
                domain == "youtubei.googleapis.com" ||
                domain == "www.youtube-nocookie.com"
            ) {
                return DnsDecision.Rewrite("restrict.youtube.com")
            }
        }

        return DnsDecision.Allow
    }
}
