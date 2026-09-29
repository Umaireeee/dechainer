package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.DnsGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins when the DNS filter is put back — and, just as important, when it's left alone. */
class DnsGuardTest {

    @Test
    fun switchedOffInSettingsIsRestored() {
        assertTrue(DnsGuard.needsRestore("family.adguard-dns.com", null))
    }

    @Test
    fun changedToAnotherProviderIsRestored() {
        assertTrue(DnsGuard.needsRestore("family.adguard-dns.com", "dns.google"))
    }

    @Test
    fun theChosenProviderIsLeftAlone() {
        assertFalse(DnsGuard.needsRestore("family.adguard-dns.com", "family.adguard-dns.com"))
    }

    @Test
    fun hostNamesCompareWithoutCase() {
        assertFalse(DnsGuard.needsRestore("family.adguard-dns.com", "Family.AdGuard-DNS.com"))
    }

    /** With nothing chosen in the app, the guard must never impose a DNS the person didn't pick. */
    @Test
    fun nothingPinnedMeansNothingEnforced() {
        assertFalse(DnsGuard.needsRestore(null, null))
        assertFalse(DnsGuard.needsRestore(null, "dns.google"))
    }
}
