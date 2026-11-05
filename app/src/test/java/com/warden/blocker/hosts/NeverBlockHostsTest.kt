package com.warden.blocker.hosts

import com.warden.blocker.block.DohHosts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverBlockHostsTest {

    /** The head and a few real lines of hagezi's doh-vpn-proxy-bypass-onlydomains.txt (2026-11-03), plus parents a future version could list. */
    private val hageziExcerpt = """
        # Title: HaGeZi's Encrypted DNS/VPN/Tor/Proxy Bypass - stop people from sneaking around your DNS!
        # Homepage: https://github.com/hagezi/dns-blocklists
        # License: https://github.com/hagezi/dns-blocklists/blob/main/LICENSE
        # Syntax: Domains (without subdomains)
        #
        ss.0001.xyz
        batvpn.github.io
        justmysocks3.github.io
        nordvpn.com
        protonvpn.com
        tailscale.com
        dns.google
        cloudflare-dns.com
        one.one.one.one
        githubusercontent.com
        github.com
        evil.github.com
    """.trimIndent().lines()

    /** What the import keeps: parsed and filtered exactly like HostsListRepository.ingest. */
    private fun imported(lines: List<String>): DomainHashSet {
        val set = DomainHashSet()
        lines.forEach { line -> HostsParser.parseLine(line) { d -> if (!NeverBlockHosts.wouldBlock(d)) set.add(d) } }
        return set
    }

    @Test fun updateHostsStayReachableWithTheBypassList() {
        val matcher = DomainMatcher(emptyList(), imported(hageziExcerpt))
        NeverBlockHosts.ALL.forEach { assertFalse("$it must stay reachable", matcher.isBlocked(it)) }
        assertFalse(matcher.isBlocked("api.github.com"))
        assertFalse(matcher.isBlocked("objects.githubusercontent.com"))
    }

    @Test fun theRestOfTheListStillBlocks() {
        val matcher = DomainMatcher(emptyList(), imported(hageziExcerpt))
        listOf("nordvpn.com", "www.protonvpn.com", "login.tailscale.com", "batvpn.github.io", "dns.google", "evil.github.com")
            .forEach { assertTrue("$it should be blocked", matcher.isBlocked(it)) }
    }

    @Test fun onlyEntriesThatWouldHitAnUpdateHostAreDropped() {
        assertTrue(NeverBlockHosts.wouldBlock("github.com"))
        assertTrue(NeverBlockHosts.wouldBlock("githubusercontent.com"))
        assertTrue(NeverBlockHosts.wouldBlock("raw.githubusercontent.com"))
        assertFalse(NeverBlockHosts.wouldBlock("evil.github.com"))
        assertFalse(NeverBlockHosts.wouldBlock("gist.githubusercontent.com"))
        assertTrue("a bare TLD would block them (the parser drops it anyway)", NeverBlockHosts.wouldBlock("com"))
    }

    @Test fun manualEntriesAreNotFiltered() {
        // Someone who blocks github.com by hand gets it blocked; only lists are filtered.
        assertTrue(DomainMatcher(listOf("github.com")).isBlocked("api.github.com"))
    }

    @Test fun theBypassTemplateIsOffered() {
        val t = HostsTemplates.ALL.first { it.url == HostsTemplates.VPN_BYPASS_URL }
        assertTrue(t.url.startsWith("https://raw.githubusercontent.com/hagezi/dns-blocklists/"))
        assertTrue("license is named", "GPL" in t.description)
        // The filter forwards to resolvers by IP; DoH names it blocks don't break its own lookups.
        assertTrue(DohHosts.ALL.none { NeverBlockHosts.wouldBlock(it) })
    }
}
