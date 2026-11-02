package com.warden.blocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.warden.blocker.allow.AllowGate
import com.warden.blocker.allow.AllowStore
import com.warden.blocker.allow.InstalledAppsInfo
import com.warden.blocker.allow.NewAppWatcher
import com.warden.blocker.block.AccessController
import com.warden.blocker.block.BlockEngine
import com.warden.blocker.system.ProtectionGuard
import com.warden.blocker.data.BlockRepository
import com.warden.blocker.data.SettingsStore
import com.warden.blocker.data.WardenDatabase
import com.warden.blocker.hosts.HostsListRepository
import com.warden.blocker.update.UpdateCheckWorker
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.usage.UsageStatsHelper

/**
 * Poor-man's DI: a single container of app-scoped singletons reachable from
 * services and activities via [WardenApp.container]. Keeps v1 dependency-free;
 * swap for Hilt later if the graph grows.
 */
class WardenApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.newAppWatcher.start()
        createNotificationChannel()
        UpdateCheckWorker.sync(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            VPN_CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        // Important notices, e.g. Android switched app blocking off after an update.
        val alerts = NotificationChannel(ALERTS_CHANNEL_ID, "Protection alerts", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, alerts))
    }

    companion object {
        const val VPN_CHANNEL_ID = "warden_blocking"
        const val ALERTS_CHANNEL_ID = "warden_alerts"
    }
}

class AppContainer(app: Application) {
    val settings = SettingsStore(app)
    val repository = BlockRepository(WardenDatabase.get(app).blockDao())
    val hostsLists = HostsListRepository(app)
    val allowStore = AllowStore(app)
    val installedApps = InstalledAppsInfo(app)
    val allowGate = AllowGate(allowStore, settings, repository, installedApps) { AppsHelper.labelFor(app, it) }
    val newAppWatcher = NewAppWatcher(app, allowStore, installedApps)
    val blockEngine = BlockEngine(repository, settings, { hostsLists.activeDomainSet() }, allowGate::webMatcher)
    val protection = ProtectionGuard(app, this)
    val accessController = AccessController(
        repository, blockEngine,
        usageMinutesToday = { pkg -> UsageStatsHelper.todayUsageMinutesFor(app, pkg) },
        allowGate = allowGate::appDecision,
    )
}

/** Convenience accessor from any Context. */
val android.content.Context.wardenContainer: AppContainer
    get() = (applicationContext as WardenApp).container
