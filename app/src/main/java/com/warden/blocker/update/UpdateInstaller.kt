package com.warden.blocker.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A failure with a message that can be shown to the user as-is. */
class UpdateException(message: String, val needsUninstall: Boolean = false) : Exception(message)

/** Outcome of a committed install session, reported by [InstallResultReceiver]. */
sealed interface InstallStatus {
    /** The system wants the user to confirm; launch [intent] from a visible activity. */
    data class NeedsConfirmation(val intent: Intent) : InstallStatus
    data object Succeeded : InstallStatus
    data object Cancelled : InstallStatus
    data class Failed(val message: String) : InstallStatus
}

/** Downloads a release APK, checks it is ours and hands it to the system installer. */
object UpdateInstaller {

    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val TIMEOUT_MS = 30_000

    private val _status = MutableStateFlow<InstallStatus?>(null)
    val status: StateFlow<InstallStatus?> = _status.asStateFlow()

    internal fun post(status: InstallStatus) { _status.value = status }
    fun clearStatus() { _status.value = null }

    /** Whether the user allowed Warden to install apps ("Install unknown apps"). */
    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads [asset] into the cache. [onProgress] gets 0..1, or null while the size is unknown.
     * Verifies size and (when GitHub publishes one) the SHA-256 digest.
     */
    suspend fun download(context: Context, asset: ApkAsset, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!ReleaseParser.isTrustedDownloadUrl(asset.downloadUrl, UpdateChecker.REPO)) {
                throw UpdateException("The download link doesn't point to the Warden+ GitHub releases.")
            }
            val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val target = File(dir, "update.apk")
            val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true // GitHub redirects to its download CDN (HTTPS only)
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", UpdateChecker.userAgent())
            }
            try {
                val code = try { conn.responseCode } catch (e: IOException) {
                    throw UpdateException("Couldn't reach GitHub. Check your internet connection.")
                }
                if (code != HttpURLConnection.HTTP_OK) throw UpdateException("Download failed (error $code). Try again later.")
                val total = asset.sizeBytes.takeIf { it > 0 } ?: conn.contentLengthLong
                if (total > MAX_APK_BYTES) throw UpdateException("The update file is unexpectedly large.")

                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var lastPercent = -1
                onProgress(if (total > 0) 0f else null)
                try {
                    conn.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                written += n
                                if (written > MAX_APK_BYTES) throw UpdateException("The update file is unexpectedly large.")
                                if (total > 0) {
                                    val percent = (written * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw UpdateException("The download was interrupted. Check your connection and try again.")
                }
                if (total > 0 && written != total) throw UpdateException("The download was incomplete. Try again.")
                val actualSha = digest.digest().toHex()
                if (asset.sha256 != null && asset.sha256 != actualSha) {
                    throw UpdateException("The downloaded file is damaged (checksum mismatch). Try again.")
                }
                target
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Makes sure [apk] is an update of this app signed with the same certificate as the
     * installed one; otherwise Android would refuse it anyway, with a vaguer error.
     */
    fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: throw UpdateException("The downloaded file isn't a valid app.")
        if (archive.packageName != context.packageName) {
            throw UpdateException("The downloaded app isn't Warden+ (${archive.packageName}).")
        }
        val installed = installedInfo(pm, context.packageName)
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateException("The downloaded version is older than the installed one.")
        }
        val newSigners = signerDigests(archive)
        if (newSigners.isEmpty()) throw UpdateException("Couldn't read the signature of the update.")
        if (newSigners != signerDigests(installed)) {
            throw UpdateException(
                "This update is signed with a different key than the installed app (for example a debug " +
                    "build). Uninstall Warden+ once, then install the new version from GitHub. " +
                    "After that, updates work from here.",
                needsUninstall = true,
            )
        }
    }

    /** Streams [apk] into a PackageInstaller session and commits it; the result arrives via [status]. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            throw UpdateException("Android couldn't start the installation (${e.message}).")
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                clearStatus()
                // Mutable: the installer adds the status extras. Explicit, non-exported receiver.
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is UpdateException) e else UpdateException("Android couldn't start the installation (${e.message}).")
        } finally {
            apk.delete()
        }
    }

    /** Human-readable text for a failed install session. */
    fun failureMessage(status: Int, systemMessage: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The update conflicts with the installed app (different signature?). Uninstall Warden+ once, then install the new version from GitHub."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space to install the update."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with your device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The update file is invalid. Try downloading it again."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked by your device."
        else -> "Installation failed" + (systemMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            if (info?.signingInfo != null) return info
        }
        // Older releases (and some archive edge cases) only fill the legacy signatures field.
        return pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo =
        pm.getPackageInfo(
            packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES,
        )

    /** SHA-256 digests of the certificates that currently sign the package. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null)
            ?: info.signatures
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
