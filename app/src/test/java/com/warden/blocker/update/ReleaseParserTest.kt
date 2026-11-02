package com.warden.blocker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val repo = "desquerteur/warden-plus"
    private val download = "https://github.com/$repo/releases/download/v0.3.0"

    private fun asset(name: String, url: String = "$download/$name", digest: String? = null) = """
        {"name": "$name", "browser_download_url": "$url", "size": 1234,
         "digest": ${if (digest == null) "null" else "\"$digest\""}}
    """

    private fun release(vararg assets: String, body: String? = "\"Fixes\\n- crash\"") = """
        {"tag_name": "v0.3.0", "name": "Warden+ 0.3.0", "body": $body,
         "html_url": "https://github.com/$repo/releases/tag/v0.3.0", "draft": false,
         "assets": [${assets.joinToString(",")}]}
    """

    @Test fun parsesGithubRelease() {
        val sha = "a".repeat(64)
        val r = ReleaseParser.parse(release(asset("warden-plus-0.3.0.apk", digest = "sha256:$sha")), repo)
        assertEquals("v0.3.0", r.tag)
        assertEquals(SemVer(0, 3, 0), r.version)
        assertEquals("Warden+ 0.3.0", r.title)
        assertEquals("Fixes\n- crash", r.notes)
        assertEquals("https://github.com/$repo/releases/tag/v0.3.0", r.pageUrl)
        assertEquals("warden-plus-0.3.0.apk", r.apk?.name)
        assertEquals(1234L, r.apk?.sizeBytes)
        assertEquals(sha, r.apk?.sha256)
    }

    @Test fun nullBodyAndDigestBecomeEmpty() {
        val r = ReleaseParser.parse(release(asset("warden-plus-0.3.0.apk"), body = "null"), repo)
        assertEquals("", r.notes)
        assertNull(r.apk?.sha256)
    }

    @Test fun prefersWardenPlusApkAndSkipsOtherFiles() {
        val r = ReleaseParser.parse(
            release(asset("warden-plus-0.3.0.apk.sha256"), asset("other.apk"), asset("warden-plus-0.3.0.apk")),
            repo,
        )
        assertEquals("warden-plus-0.3.0.apk", r.apk?.name)
    }

    @Test fun noApkAttached() {
        assertNull(ReleaseParser.parse(release(asset("notes.txt")), repo).apk)
        assertNull(ReleaseParser.parse(release(), repo).apk)
    }

    @Test fun ignoresApksHostedElsewhere() {
        val r = ReleaseParser.parse(
            release(
                asset("warden-plus-0.3.0.apk", url = "https://evil.example/warden-plus-0.3.0.apk"),
                asset("warden-plus-0.3.0.apk", url = "http://github.com/$repo/releases/download/v0.3.0/x.apk"),
                asset("warden-plus-0.3.0.apk", url = "https://github.com/someone/else/releases/download/v1/x.apk"),
            ),
            repo,
        )
        assertNull(r.apk)
    }

    @Test fun digestParsing() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, ReleaseParser.parseSha256Digest("sha256:${hex.uppercase()}"))
        assertNull(ReleaseParser.parseSha256Digest(null))
        assertNull(ReleaseParser.parseSha256Digest(""))
        assertNull(ReleaseParser.parseSha256Digest("md5:abc"))
        assertNull(ReleaseParser.parseSha256Digest("sha256:xyz"))
        assertNull(ReleaseParser.parseSha256Digest("sha256:" + hex.dropLast(1)))
    }

    @Test fun isNewerComparesSemantically() {
        assertTrue(ReleaseParser.isNewer(SemVer.parse("v0.10.0"), "0.9.0"))
        assertTrue(ReleaseParser.isNewer(SemVer.parse("v0.2.0"), "0.2.0-beta.1"))
        assertFalse(ReleaseParser.isNewer(SemVer.parse("v0.2.0"), "0.2.0"))
        assertFalse(ReleaseParser.isNewer(SemVer.parse("v0.1.9"), "0.2.0"))
        assertFalse(ReleaseParser.isNewer(null, "0.2.0"))
        assertFalse(ReleaseParser.isNewer(SemVer.parse("v9.0.0"), "not-a-version"))
    }

    @Test fun tagThatIsNoVersion() {
        val r = ReleaseParser.parse("""{"tag_name": "nightly", "name": null, "assets": []}""", repo)
        assertNull(r.version)
        assertEquals("nightly", r.title)
    }
}
