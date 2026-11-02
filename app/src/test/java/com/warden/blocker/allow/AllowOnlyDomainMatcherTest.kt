package com.warden.blocker.allow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowOnlyDomainMatcherTest {

    private val matcher = AllowOnlyDomainMatcher(listOf("wikipedia.org", "Maps.Example.com."))

    @Test fun allowedDomainsAndTheirSubdomainsPass() {
        assertFalse(matcher.isBlocked("wikipedia.org"))
        assertFalse(matcher.isBlocked("de.wikipedia.org"))
        assertFalse(matcher.isBlocked("DE.Wikipedia.ORG."))
        assertFalse(matcher.isBlocked("maps.example.com"))
    }

    @Test fun everythingElseIsBlocked() {
        assertTrue(matcher.isBlocked("instagram.com"))
        assertTrue(matcher.isBlocked("www.youtube.com"))
        assertTrue(matcher.isBlocked("notwikipedia.org")) // suffix match is per label, not per character
        assertTrue(matcher.isBlocked("example.com")) // a parent of an allowed name isn't allowed
        assertTrue(matcher.isBlocked("wikipedia.org.evil.com"))
        assertFalse(matcher.isEmpty)
    }

    @Test fun anEmptyAllowListStillKeepsThePhoneWorking() {
        val nothing = AllowOnlyDomainMatcher(emptyList())
        assertTrue(nothing.isBlocked("google.com"))
        assertFalse(nothing.isBlocked("connectivitycheck.gstatic.com"))
        assertFalse(nothing.isBlocked("mtalk.google.com"))
        assertFalse(nothing.isBlocked("alt3-mtalk.google.com"))
        assertFalse(nothing.isBlocked("time.android.com"))
        assertFalse(nothing.isBlocked("2.pool.ntp.org"))
        assertFalse(nothing.isBlocked("accounts.google.com"))
        assertFalse(nothing.isBlocked("4.3.2.1.in-addr.arpa"))
    }

    @Test fun wardenUpdatesStayReachable() {
        val nothing = AllowOnlyDomainMatcher(emptyList())
        listOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
            .forEach { assertFalse(it, nothing.isBlocked(it)) }
    }

    @Test fun systemDomainsDontOpenBrowsingSites() {
        val nothing = AllowOnlyDomainMatcher(emptyList())
        listOf("www.google.com", "youtube.com", "www.gstatic.com", "googleusercontent.com", "samsung.com")
            .forEach { assertTrue(it, nothing.isBlocked(it)) }
    }

    @Test fun localSingleLabelNamesAreNeverBlocked() {
        assertFalse(matcher.isBlocked("localhost"))
        assertFalse(matcher.isBlocked("printer."))
        assertFalse(matcher.isBlocked(""))
    }

    @Test fun systemListHasNoDuplicatesOrUppercase() {
        assertEquals(SystemDomains.ALL.size, SystemDomains.ALL.toSet().size)
        SystemDomains.ALL.forEach { assertEquals(it, it.lowercase().trim()) }
    }
}
