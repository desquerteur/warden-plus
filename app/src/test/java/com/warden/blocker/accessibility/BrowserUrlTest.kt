package com.warden.blocker.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlTest {

    @Test fun hostFromWhatBrowsersShow() {
        assertEquals("youtube.com", BrowserUrl.hostFrom("youtube.com/watch?v=abc"))
        assertEquals("m.youtube.com", BrowserUrl.hostFrom("https://m.youtube.com/shorts/x"))
        assertEquals("www.reddit.com", BrowserUrl.hostFrom("HTTPS://WWW.REDDIT.COM"))
        assertEquals("example.com", BrowserUrl.hostFrom("http://user:pw@example.com:8080/x"))
        assertEquals("instagram.com", BrowserUrl.hostFrom(" instagram.com "))
    }

    @Test fun searchesAndEmptyBarsAreNoWebsite() {
        assertNull(BrowserUrl.hostFrom("how to watch youtube.com"))
        assertNull(BrowserUrl.hostFrom("cats"))
        assertNull(BrowserUrl.hostFrom(""))
        assertNull(BrowserUrl.hostFrom(null))
        assertNull(BrowserUrl.hostFrom("Search or type URL"))
    }

    @Test fun knownBrowsers() {
        assertTrue(BrowserUrl.isBrowser("com.android.chrome"))
        assertTrue(BrowserUrl.isBrowser("com.sec.android.app.sbrowser"))
        assertTrue(BrowserUrl.isBrowser("org.mozilla.firefox"))
        assertFalse(BrowserUrl.isBrowser("com.instagram.android"))
        BrowserUrl.ADDRESS_BARS.forEach { (pkg, ids) -> ids.forEach { assertTrue(it.startsWith("$pkg:id/")) } }
    }

    @Test fun onlyWhileTheFilterIsOff() {
        assertTrue(BrowserUrl.active(enabled = true, enforced = true, filterRunning = false))
        assertFalse(BrowserUrl.active(enabled = true, enforced = true, filterRunning = true))
        assertFalse(BrowserUrl.active(enabled = true, enforced = false, filterRunning = false))
        assertFalse(BrowserUrl.active(enabled = false, enforced = true, filterRunning = false))
    }
}
