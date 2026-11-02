package com.warden.blocker.update

import com.warden.blocker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Result of asking GitHub for the latest release. */
sealed interface CheckResult {
    data class UpToDate(val latest: ReleaseInfo) : CheckResult
    data class UpdateAvailable(val release: ReleaseInfo) : CheckResult
    data object NoReleases : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** Looks up the latest published (non-draft, non-prerelease) GitHub release. */
object UpdateChecker {

    const val REPO = BuildConfig.UPDATE_REPO
    private const val TIMEOUT_MS = 15_000

    suspend fun check(installedVersion: String = BuildConfig.VERSION_NAME): CheckResult =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", userAgent())
                }
                try {
                    when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            // The response is a few KB; cap it so a broken proxy can't exhaust memory.
                            val body = conn.inputStream.use { it.readBytesCapped(1_000_000) }.toString(Charsets.UTF_8)
                            val release = ReleaseParser.parse(body, REPO)
                            when {
                                release.version == null -> CheckResult.Failed("The latest release tag \"${release.tag}\" isn't a version number.")
                                ReleaseParser.isNewer(release.version, installedVersion) -> CheckResult.UpdateAvailable(release)
                                else -> CheckResult.UpToDate(release)
                            }
                        }
                        HttpURLConnection.HTTP_NOT_FOUND -> CheckResult.NoReleases
                        HttpURLConnection.HTTP_FORBIDDEN, 429 ->
                            CheckResult.Failed("GitHub is limiting requests right now. Try again in an hour.")
                        else -> CheckResult.Failed("GitHub answered with error $code. Try again later.")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                CheckResult.Failed("Couldn't reach GitHub. Check your internet connection.")
            } catch (e: org.json.JSONException) {
                CheckResult.Failed("GitHub sent an unexpected answer. Try again later.")
            }
        }

    fun userAgent() = "warden-plus/${BuildConfig.VERSION_NAME} (Android)"
}

internal fun java.io.InputStream.readBytesCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) throw IOException("Response too large")
    }
    return out.toByteArray()
}
