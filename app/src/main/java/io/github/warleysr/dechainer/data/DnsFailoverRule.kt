package io.github.warleysr.dechainer.data

/**
 * Pure decision logic for Clean DNS auto-selection and failover.
 */
object DnsFailoverRule {
    val CLEAN_HOSTS: List<String> = listOf(
        "family.cloudflare-dns.com",
        "family.adguard-dns.com",
        "adult-filter-dns.cleanbrowsing.org",
        "family-filter-dns.cleanbrowsing.org",
        "family.freedns.controld.com"
    )

    /**
     * Known bootstrap IPv4 addresses for each clean provider so DoT reachability probing works even
     * when the phone's current Private DNS is broken or IPv6 routes drop port 853.
     */
    val BOOTSTRAP_IPS: Map<String, List<String>> = mapOf(
        "family.cloudflare-dns.com" to listOf("1.1.1.3", "1.0.0.3"),
        "family.adguard-dns.com" to listOf("94.140.14.15", "94.140.15.16"),
        "adult-filter-dns.cleanbrowsing.org" to listOf("185.228.168.10", "185.228.169.11"),
        "family-filter-dns.cleanbrowsing.org" to listOf("185.228.168.168", "185.228.169.168"),
        "family.freedns.controld.com" to listOf("76.76.2.4", "76.76.10.4")
    )

    const val DEFAULT_PROBE_INTERVAL_MS = 60_000L

    fun isCleanHost(host: String?, candidates: List<String> = CLEAN_HOSTS): Boolean =
        host != null && candidates.any { it.equals(host, ignoreCase = true) }

    fun bootstrapIpsFor(host: String): List<String> =
        BOOTSTRAP_IPS.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }?.value.orEmpty()

    /**
     * Picks the clean DNS host to enforce.
     * - When [autoFailover] is off, keeps [pinnedHost] untouched.
     * - When [autoFailover] is on and [pinnedHost] is reachable, keeps [pinnedHost] (no thrashing).
     * - When [pinnedHost] is missing or unreachable, switches to the first reachable server in [candidates].
     * - If every server is unreachable right now (e.g. offline or port 853 blocked), keeps
     *   [pinnedHost] or falls back to the first clean server so filtering stays armed.
     */
    fun chooseServer(
        pinnedHost: String?,
        autoFailover: Boolean,
        reachability: Map<String, Boolean>,
        candidates: List<String> = CLEAN_HOSTS
    ): String? {
        if (!autoFailover) return pinnedHost
        if (pinnedHost != null && reachability[pinnedHost] == true) return pinnedHost
        val working = candidates.firstOrNull { reachability[it] == true }
        return working ?: pinnedHost ?: candidates.firstOrNull()
    }

    /**
     * True when [DnsGuard] should run a live DoT reachability check right now.
     * Skips probing when there is no validated internet or when the active host was already verified
     * on this exact network handle within [minIntervalMs], keeping idle battery cost at zero.
     */
    fun shouldProbeNow(
        autoFailover: Boolean,
        hasValidatedInternet: Boolean,
        currentNetworkId: Long?,
        lastVerifiedNetworkId: Long?,
        lastVerifiedHost: String?,
        pinnedHost: String?,
        lastProbeElapsedMs: Long?,
        nowElapsedMs: Long,
        minIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS
    ): Boolean {
        if (!autoFailover || !hasValidatedInternet) return false
        if (pinnedHost == null) return true
        if (currentNetworkId != null && currentNetworkId != lastVerifiedNetworkId) return true
        if (!pinnedHost.equals(lastVerifiedHost, ignoreCase = true)) return true
        if (lastProbeElapsedMs == null || nowElapsedMs - lastProbeElapsedMs >= minIntervalMs) return true
        return false
    }
}
