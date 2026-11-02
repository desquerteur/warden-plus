package com.warden.blocker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainInputTest {

    @Test fun plainDomainStaysAsIs() = assertEquals("doubleclick.net", DomainInput.normalize("doubleclick.net"))

    @Test fun stripsSchemeWwwPathAndCase() =
        assertEquals("instagram.com", DomainInput.normalize("  https://www.Instagram.com/reels/abc "))

    @Test fun stripsQueryFragmentAndTrailingDot() {
        assertEquals("example.com", DomainInput.normalize("example.com?q=1"))
        assertEquals("example.com", DomainInput.normalize("http://example.com#top"))
        assertEquals("example.com", DomainInput.normalize("example.com."))
    }

    @Test fun keepsSubdomains() = assertEquals("m.youtube.com", DomainInput.normalize("m.youtube.com"))

    @Test fun blankOrSchemeOnlyIsRejected() {
        assertNull(DomainInput.normalize(""))
        assertNull(DomainInput.normalize("   "))
        assertNull(DomainInput.normalize("https://"))
        assertNull(DomainInput.normalize("www."))
    }

    @Test fun spacesInsideAreRejected() = assertNull(DomainInput.normalize("face book.com"))
}
