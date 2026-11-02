package com.warden.blocker.update

import org.json.JSONObject

/** The parts of a GitHub release the updater needs. */
data class ReleaseInfo(
    val tag: String,
    val version: SemVer?,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apk: ApkAsset?,
)

data class ApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 published by GitHub (newer releases only), or null. */
    val sha256: String?,
)

/** Pure parsing/selection logic for the GitHub releases API, kept free of Android for unit tests. */
object ReleaseParser {

    /** Parses one object from `GET /repos/{owner}/{repo}/releases/latest`. */
    fun parse(json: String, repo: String): ReleaseInfo {
        val o = JSONObject(json)
        val tag = o.str("tag_name")
        val assets = o.optJSONArray("assets")
        val apks = buildList {
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                add(
                    ApkAsset(
                        name = a.str("name"),
                        downloadUrl = a.str("browser_download_url"),
                        sizeBytes = a.optLong("size", -1L),
                        sha256 = parseSha256Digest(a.str("digest")),
                    )
                )
            }
        }
        return ReleaseInfo(
            tag = tag,
            version = SemVer.parse(tag),
            title = o.str("name").ifBlank { tag },
            notes = o.str("body").trim(),
            pageUrl = o.str("html_url"),
            apk = selectApk(apks, repo),
        )
    }

    /**
     * Picks the APK to install: only assets downloaded from this repository's release
     * downloads over HTTPS, preferring the `warden-plus-<version>.apk` naming of release.yml.
     */
    fun selectApk(assets: List<ApkAsset>, repo: String): ApkAsset? {
        val candidates = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && isTrustedDownloadUrl(it.downloadUrl, repo)
        }
        return candidates.firstOrNull { it.name.startsWith("warden-plus", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    fun isTrustedDownloadUrl(url: String, repo: String): Boolean =
        url.startsWith("https://github.com/$repo/releases/download/")

    /** GitHub reports asset digests as `sha256:<hex>`. */
    fun parseSha256Digest(digest: String?): String? {
        if (digest.isNullOrBlank() || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** True when [latest] is a newer version than [installedVersionName]. */
    fun isNewer(latest: SemVer?, installedVersionName: String): Boolean {
        val installed = SemVer.parse(installedVersionName) ?: return false
        return latest != null && latest > installed
    }
}

// optString() turns JSON null into the text "null"; GitHub sends null for empty bodies/digests.
private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)
