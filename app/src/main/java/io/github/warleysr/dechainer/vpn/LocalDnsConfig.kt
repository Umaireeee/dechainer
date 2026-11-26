package io.github.warleysr.dechainer.vpn

import org.json.JSONArray
import org.json.JSONObject

data class LocalDnsConfig(
    val enabled: Boolean = false,
    val blockAdultDomains: Boolean = true,
    val blockAdultKeywords: Boolean = true,
    val forceSafeSearch: Boolean = true,
    val forceYouTubeRestricted: Boolean = false,
    val blockDoHBypass: Boolean = true,
    val blockTikTok: Boolean = false,
    val customBlockedDomains: Set<String> = emptySet()
) {
    fun isLooserThan(old: LocalDnsConfig): Boolean {
        if (old.enabled && !enabled) return true
        if (!enabled) return false
        if (old.blockAdultDomains && !blockAdultDomains) return true
        if (old.blockAdultKeywords && !blockAdultKeywords) return true
        if (old.forceSafeSearch && !forceSafeSearch) return true
        if (old.forceYouTubeRestricted && !forceYouTubeRestricted) return true
        if (old.blockDoHBypass && !blockDoHBypass) return true
        if (old.blockTikTok && !blockTikTok) return true
        if (!customBlockedDomains.containsAll(old.customBlockedDomains)) return true
        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("blockAdultDomains", blockAdultDomains)
        put("blockAdultKeywords", blockAdultKeywords)
        put("forceSafeSearch", forceSafeSearch)
        put("forceYouTubeRestricted", forceYouTubeRestricted)
        put("blockDoHBypass", blockDoHBypass)
        put("blockTikTok", blockTikTok)
        put("customBlockedDomains", JSONArray(customBlockedDomains.sorted()))
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun normalizeDomain(input: String): String {
            var clean = input.trim().lowercase()
            clean = clean.removePrefix("https://").removePrefix("http://")
            clean = clean.substringBefore('/').substringBefore('?').substringBefore('#')
            clean = clean.substringBefore(':').trimEnd('.').removePrefix("*.")
            return clean
        }

        fun fromJson(obj: JSONObject): LocalDnsConfig {
            val arr = obj.optJSONArray("customBlockedDomains")
            val domains = if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    normalizeDomain(arr.optString(i, "")).takeIf { it.isNotEmpty() }
                }.toSet()
            } else {
                emptySet()
            }
            return LocalDnsConfig(
                enabled = obj.optBoolean("enabled", false),
                blockAdultDomains = obj.optBoolean("blockAdultDomains", true),
                blockAdultKeywords = obj.optBoolean("blockAdultKeywords", true),
                forceSafeSearch = obj.optBoolean("forceSafeSearch", true),
                forceYouTubeRestricted = obj.optBoolean("forceYouTubeRestricted", false),
                blockDoHBypass = obj.optBoolean("blockDoHBypass", true),
                blockTikTok = obj.optBoolean("blockTikTok", false),
                customBlockedDomains = domains
            )
        }

        fun fromJsonString(raw: String?): LocalDnsConfig? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}
