package com.warden.blocker.block

import com.warden.blocker.hosts.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedDomainsTest {

    private fun matcherFor(vararg blocked: String, off: Set<String> = emptySet()) =
        DomainMatcher(RelatedDomains.expand(blocked.toList(), off))

    @Test fun instagramPicturesAreBlockedToo() {
        val m = matcherFor("instagram.com")
        assertTrue(m.isBlocked("scontent-fra5-1.cdninstagram.com"))
        assertTrue(m.isBlocked("scontent.xx.fbcdn.net"))
        assertTrue(m.isBlocked("www.instagram.com"))
    }

    @Test fun youtubeVideosAndThumbnails() {
        val m = matcherFor("youtube.com")
        assertTrue(m.isBlocked("i.ytimg.com"))
        assertTrue(m.isBlocked("rr3---sn-4g5e6nzz.googlevideo.com"))
        assertTrue(m.isBlocked("youtu.be"))
        assertTrue(m.isBlocked("yt3.ggpht.com"))
        // Not the rest of Google.
        assertFalse(m.isBlocked("www.google.com"))
        assertFalse(m.isBlocked("www.gstatic.com"))
        assertFalse(m.isBlocked("lh3.ggpht.com"))
        assertFalse(m.isBlocked("lh3.googleusercontent.com"))
    }

    @Test fun otherSites() {
        assertTrue(matcherFor("tiktok.com").isBlocked("p16-sign-va.tiktokcdn.com"))
        assertTrue(matcherFor("reddit.com").isBlocked("i.redd.it"))
        assertTrue(matcherFor("reddit.com").isBlocked("preview.redd.it"))
        assertTrue(matcherFor("x.com").isBlocked("pbs.twimg.com"))
        assertTrue(matcherFor("twitter.com").isBlocked("x.com"))
        assertTrue(matcherFor("facebook.com").isBlocked("static.xx.fbcdn.net"))
        assertTrue(matcherFor("snapchat.com").isBlocked("cf-st.sc-cdn.net"))
    }

    @Test fun subdomainEntryFindsItsSite() {
        assertEquals("youtube.com", RelatedDomains.siteOf("m.youtube.com"))
        assertEquals("instagram.com", RelatedDomains.siteOf("www.instagram.com"))
        assertNull(RelatedDomains.siteOf("notinstagram.com"))
        assertNull(RelatedDomains.siteOf("example.org"))
    }

    @Test fun switchedOffPerEntry() {
        val m = matcherFor("instagram.com", off = setOf("instagram.com"))
        assertTrue(m.isBlocked("instagram.com"))
        assertFalse(m.isBlocked("scontent.cdninstagram.com"))
    }

    @Test fun neverSharedInfrastructure() {
        val all = RelatedDomains.TABLE.values.flatten().map { it.domain }
        val forbidden = listOf(
            "google.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "ggpht.com",
            "cloudflare.com", "cloudfront.net", "akamaihd.net", "akamaized.net", "fastly.net", "amazonaws.com",
        )
        forbidden.forEach { assertFalse("$it must never be a related domain", it in all) }
    }

    @Test fun sharedCdnsSayWhoElseUsesThem() {
        val fb = RelatedDomains.relatedTo("instagram.com").first { it.domain == "fbcdn.net" }
        assertTrue(fb.sharedWith!!.contains("Facebook"))
    }

    @Test fun relatedOffIsALooseningThatSurvivesTheQueue() {
        val change = ProtectedChange.RelatedOff("instagram.com", "Instagram")
        assertEquals(Loosening.WEAKEN_ITEM, change.kind)
        assertEquals(change, ProtectedChange.fromJson(change.toJson()))
    }
}
