package io.github.warleysr.dechainer.data

/**
 * The adult-site filters the app may pin, best first. Android probes a provider before it accepts it,
 * so one that a network blocks is refused and the next is tried. No Android in here: unit tests cover it.
 *
 * Cloudflare and AdGuard first: both are fast. The CleanBrowsing Family filter comes last because the
 * app's own notes say it is throttled.
 */
object DnsProviders {
    val ORDER: List<String> = listOf(
        "family.cloudflare-dns.com",
        "family.adguard-dns.com",
        "adult-filter-dns.cleanbrowsing.org",
        "family-filter-dns.cleanbrowsing.org"
    )

    /** The first provider not yet tried on this network, or null when every one has been. */
    fun next(tried: Set<String>): String? = ORDER.firstOrNull { it !in tried }

    /**
     * Whether the pinned provider looks broken on the current network: the network has internet in
     * principle but Android could not validate it. A network with no internet at all is not the DNS's fault.
     */
    fun looksBroken(validated: Boolean, hasInternet: Boolean): Boolean = hasInternet && !validated
}
