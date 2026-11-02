package com.warden.blocker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(text: String) = SemVer.parse(text)!!

    @Test fun parsesTagsAndVersionNames() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(0, 2, 0), v("0.2"))
        assertEquals(SemVer(1, 0, 0, listOf("beta", "1")), v("1.0.0-beta.1"))
        assertEquals(SemVer(1, 0, 0), v(" V1.0.0+build.5 "))
    }

    @Test fun rejectsNonVersions() {
        for (bad in listOf("", "latest", "v1", "1.2.3.4", "1.x.0", "1.2.3-", "1.2.3-a..b", "99999999999.0.0")) {
            assertNull(bad, SemVer.parse(bad))
        }
    }

    @Test fun ordersNumericallyNotAlphabetically() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.1") > v("0.2.0"))
        assertEquals(0, v("v0.2.0").compareTo(v("0.2.0")))
    }

    @Test fun preReleaseSortsBeforeRelease() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
    }

    @Test fun buildMetadataIsIgnored() {
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
    }

    @Test fun toStringRoundTrips() {
        assertEquals("1.2.3", v("v1.2.3").toString())
        assertEquals("1.0.0-rc.1", v("1.0.0-rc.1").toString())
    }
}
