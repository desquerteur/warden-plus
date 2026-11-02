package com.warden.blocker.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * Apps that must never be blockable — blocking them locks the user out of their own phone
 * (a recurring, much-complained-about failure mode of other blockers). We refuse to add
 * these in the picker and never intercept them at runtime.
 */
object CriticalApps {
    private val CORE = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.cellbroadcastreceiver",
    )

    // Asked on every window change by the accessibility service, so cached briefly.
    @Volatile private var homeCache: Set<String> = emptySet()
    @Volatile private var homeCacheAt = 0L
    private const val HOME_CACHE_MS = 30_000L

    fun isCritical(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName in CORE) return true
        return isLauncher(context, packageName)
    }

    /**
     * Whether [packageName] is a home screen: the default launcher and every other app that
     * can act as one (e.g. Samsung's `com.sec.android.app.launcher`, a launcher the user just
     * switched to, Easy mode). Widgets and the Discover page live inside these.
     */
    fun isLauncher(context: Context, packageName: String): Boolean =
        packageName in homePackages(context)

    fun homePackages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val cached = homeCache
        if (cached.isNotEmpty() && now - homeCacheAt < HOME_CACHE_MS) return cached
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val all = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val default = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        // "android" is the chooser shown while no default home is set — not a real app.
        val homes = (all + listOfNotNull(default)).filter { it != "android" }.toSet()
        homeCache = homes
        homeCacheAt = now
        return homes
    }
}
