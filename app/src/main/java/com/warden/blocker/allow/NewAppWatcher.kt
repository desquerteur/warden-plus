package com.warden.blocker.allow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.usage.AppsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Notices newly installed apps that fall into a blocked category ("New game blocked: …").
 * The block itself needs nothing from here — [AllowGate] reads the category whenever an app
 * opens — this only keeps the category cache fresh and tells the user.
 *
 * Android 8+ no longer delivers PACKAGE_ADDED to receivers declared in the manifest, so the
 * receiver is registered at runtime while Warden+ runs (its accessibility service and VPN keep
 * it running), and [reconcile] at every process start catches installs made in between.
 */
class NewAppWatcher(
    private val context: Context,
    private val store: AllowStore,
    private val apps: InstalledAppsInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            apps.invalidate(pkg)
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (intent.action == Intent.ACTION_PACKAGE_ADDED && !replacing) {
                scope.launch { onInstalled(pkg) }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // System broadcasts reach a not-exported receiver; no other app can fake them.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        scope.launch { reconcile() }
    }

    /** Compares the launchable apps with the ones seen last time and reports new blocked ones. */
    suspend fun reconcile() = lock.withLock {
        val installed = runCatching { apps.launchableApps() }.getOrNull() ?: return@withLock
        val config = store.current()
        val newlyBlocked = AllowPolicy.newlyBlocked(
            store.knownPackages(),
            installed.associate { it.packageName to it.detected },
            config,
        )
        store.setKnownPackages(installed.mapTo(HashSet()) { it.packageName })
        val labels = installed.associate { it.packageName to it.label }
        newlyBlocked.forEach { (pkg, category) -> notifyBlocked(labels[pkg] ?: pkg, category) }
    }

    private suspend fun onInstalled(pkg: String) = lock.withLock {
        val config = store.current()
        store.addKnownPackage(pkg)
        if (config.blockedCategories.isEmpty()) return@withLock
        val facts = apps.facts(pkg, config)
        if (!facts.launchable || facts.alwaysAllowed) return@withLock
        val category = AllowPolicy.blockedCategoryOf(pkg, facts.detectedCategory, config) ?: return@withLock
        notifyBlocked(AppsHelper.labelFor(context, pkg), category)
    }

    private fun notifyBlocked(label: String, category: AppCategory) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New apps blocked", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "New ${category.singular} blocked: $label"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText("Blocked because you block ${category.label.lowercase()}. Wrong? Correct it in Warden+.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$label was just installed and counts as a ${category.singular}, so Warden+ blocks it. " +
                        "If that's wrong, open Warden+ → Blocklist → Block categories.",
                ),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        // Without the notification permission this is a no-op; the app is blocked all the same.
        runCatching { nm.notify(NOTIF_BASE + (label.hashCode() and 0xFFFF), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "warden_new_apps"
        const val NOTIF_BASE = 20_000
    }
}
