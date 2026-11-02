package com.warden.blocker.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(val packageName: String, val label: String)

/** Lists user-launchable apps for the block-app picker. */
object AppsHelper {
    fun launchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved
            .asSequence()
            .map { it.activityInfo.packageName }
            .filterNot { com.warden.blocker.util.CriticalApps.isCritical(context, it) }
            .distinct()
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                InstalledApp(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Human-readable app name for a package. Also covers system apps and apps uninstalled
     * since; if Android won't tell us (e.g. package visibility), a readable guess from the
     * package name ([fallbackAppLabel]).
     */
    fun labelFor(context: Context, packageName: String): String {
        val label = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            pm.getApplicationLabel(info).toString()
        }.getOrNull()
        // getApplicationLabel() returns the package name itself when the app has no label.
        return if (label.isNullOrBlank() || label == packageName) fallbackAppLabel(packageName) else label
    }

    /** Loads an app's launcher icon as an ImageBitmap (null if the package is gone). */
    fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
    }.getOrNull()
}
