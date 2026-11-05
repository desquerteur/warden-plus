package com.warden.blocker.allow

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.warden.blocker.util.CriticalApps
import java.util.concurrent.ConcurrentHashMap

/** One launchable app with its detected category, for the allow and category screens. */
data class CategorizedApp(val packageName: String, val label: String, val detected: AppCategory?)

/**
 * The Android side of [AppFacts]: categories and launcher icons from PackageManager, plus the
 * apps that must stay reachable. Results are cached; [invalidate] on package changes.
 *
 * Uses only the package visibility Warden+ already has (launchable apps and the launcher via
 * `<queries>`) — no QUERY_ALL_PACKAGES. Apps it can't see count as not launchable, so they're
 * never blocked by "Allow only" or a category.
 */
class InstalledAppsInfo(private val context: Context) {

    private data class Cached(val category: AppCategory?, val launchable: Boolean)

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile private var defaults: Set<String> = emptySet()
    @Volatile private var defaultsKey: Pair<Boolean, Boolean>? = null
    @Volatile private var defaultsAt = 0L

    fun facts(packageName: String, config: AllowConfig): AppFacts {
        val c = cached(packageName)
        return AppFacts(c.category, c.launchable, isAlwaysAllowed(packageName, config))
    }

    fun detectedCategory(packageName: String): AppCategory? = cached(packageName).category

    @Volatile private var vpnCache: Set<String>? = null

    /**
     * Installed VPN apps (not Warden+), see [VpnApps]. Visible through the `<queries>` entry for
     * the VpnService action, also without a launcher icon. Cached until [invalidate].
     */
    fun vpnPackages(): Set<String> = vpnCache ?: run {
        val services = runCatching {
            context.packageManager.queryIntentServices(Intent(VpnApps.SERVICE_ACTION), PackageManager.MATCH_ALL)
                .map { ServiceEntry(it.serviceInfo.packageName, it.serviceInfo.permission) }
        }.getOrDefault(emptyList())
        VpnApps.detect(services, context.packageName).also { vpnCache = it }
    }

    fun isAlwaysAllowed(packageName: String, config: AllowConfig): Boolean =
        packageName in AlwaysAllowed.PACKAGES ||
            CriticalApps.isCritical(context, packageName) ||
            packageName in runtimeDefaults(config)

    /** Packages with a launcher icon (not Warden+), without labels — cheap. Call off the main thread. */
    fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapTo(HashSet()) { it.activityInfo.packageName } - context.packageName
    }

    /** Launchable apps (critical ones included), sorted by name. Call off the main thread. */
    fun launchableApps(): List<CategorizedApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                val label = pm.getApplicationLabel(info).toString()
                CategorizedApp(pkg, label, AppCategories.detect(info.category, info.flags, pkg in vpnPackages()))
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Forget what's known about [packageName] (null: everything) after an install or update. */
    fun invalidate(packageName: String?) {
        if (packageName == null) cache.clear() else cache.remove(packageName)
        vpnCache = null
        defaultsAt = 0L
    }

    private fun cached(packageName: String): Cached = cache.getOrPut(packageName) {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
        Cached(
            category = info?.let { AppCategories.detect(it.category, it.flags, packageName in vpnPackages()) },
            launchable = info != null && runCatching { pm.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false),
        )
    }

    /** Default phone app, keyboards and (if switched on) the default SMS and camera apps. */
    private fun runtimeDefaults(config: AllowConfig): Set<String> {
        val key = config.allowSms to config.allowCamera
        val now = SystemClock.elapsedRealtime()
        if (key == defaultsKey && now - defaultsAt < DEFAULTS_TTL_MS) return defaults
        val found = HashSet<String>()
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(found::add)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { found += it.packageName }
        }
        if (config.allowSms) runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(found::add)
        if (config.allowCamera) {
            listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE).forEach { action ->
                runCatching {
                    // Only the built-in camera: other apps that can take photos stay blockable.
                    context.packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_SYSTEM_ONLY)
                        .forEach { found += it.activityInfo.packageName }
                }
            }
        }
        defaults = found
        defaultsKey = key
        defaultsAt = now
        return found
    }

    private companion object {
        /** Default apps and keyboards rarely change; re-check now and then. */
        const val DEFAULTS_TTL_MS = 60_000L
    }
}
