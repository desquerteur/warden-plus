package com.warden.blocker.system

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp
import com.warden.blocker.allow.AllowPolicy
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.VpnApps
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Another app's VPN pushed Warden's filter out (Android allows one VPN at a time): a loud
 * notification with "Restore" (Warden asks for the VPN slot back) and "VPN settings", and a
 * count in Protection and Usage. Names the app when Warden+ can tell who it was.
 */
object VpnTakeover {

    /** How long a VPN app the user had in front still counts as "probably the one". */
    private const val RECENT_MS = 2 * 60_000L

    /**
     * Android takes the slot from Warden+ when the other app *asks* for it; its tunnel comes up a
     * few seconds later. Look this often, this far apart, before saying "only disconnected".
     */
    private const val SETTLE_TRIES = 10
    private const val SETTLE_STEP_MS = 1_000L

    @Volatile private var lastVpnApp: String? = null
    @Volatile private var lastVpnAppAt = 0L

    /** From the accessibility service: [pkg] came to the front. Remembered only if it's a VPN app. */
    fun noteForeground(context: Context, pkg: String) {
        if (pkg == context.packageName) return
        if (pkg in context.wardenContainer.installedApps.vpnPackages()) {
            lastVpnApp = pkg
            lastVpnAppAt = System.currentTimeMillis()
        }
    }

    private fun recentVpnApp(now: Long): String? = lastVpnApp?.takeIf { now - lastVpnAppAt < RECENT_MS }

    /** What the notification says — pure, unit-tested. */
    data class Message(val title: String, val text: String)

    /**
     * [otherVpn]: another VPN carries traffic now (else it was only disconnected). [label]: the
     * app behind it, if known; [blockedApp] / [trusted]: what it is to the "VPN apps" category.
     */
    fun message(otherVpn: Boolean, label: String?, blockedApp: Boolean, trusted: Boolean, browserMode: Boolean): Message {
        if (!otherVpn) {
            return Message(
                "Website filter was disconnected",
                "Warden+'s VPN was disconnected in Android settings. Tap Restore to turn website blocking back on.",
            )
        }
        val who = when {
            label == null -> "Another VPN"
            trusted -> "$label (trusted VPN)"
            blockedApp -> "$label (a blocked VPN app)"
            else -> label
        }
        val browsers = if (browserMode) " Browser mode keeps blocking websites in browsers." else ""
        return Message(
            "$who took over – website blocking is off",
            "Android allows one VPN at a time, so Warden+'s website filter stopped.$browsers " +
                "Tap Restore to take it back, or turn the other VPN off in VPN settings.",
        )
    }

    /** Called from the VPN's onRevoke (off the main thread). */
    suspend fun onRevoked(context: Context) {
        var otherVpn = false
        for (i in 0 until SETTLE_TRIES) {
            delay(SETTLE_STEP_MS)
            otherVpn = VpnController.otherVpnActive(context)
            if (otherVpn) break
        }
        val c = context.wardenContainer
        val now = System.currentTimeMillis()
        val config = c.allowStore.current()
        val vpnApps = c.installedApps.vpnPackages()
        val alwaysOn = runCatching { Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") }.getOrNull()
        val suspect = if (otherVpn) VpnApps.takeoverSuspect(alwaysOn, recentVpnApp(now), vpnApps, context.packageName) else null
        val blocked = suspect != null &&
            AllowPolicy.blockedCategoryOf(suspect, c.installedApps.detectedCategory(suspect), config) == AppCategory.VPN
        val trusted = suspect != null && suspect in VpnApps.trusted(vpnApps, config)
        // Name it only when it matters for blocking (a blocked or trusted VPN app) — otherwise "Another VPN".
        val label = suspect?.takeIf { blocked || trusted }?.let { AppsHelper.labelFor(context, it) }
        if (otherVpn) c.settings.recordVpnSwitch(now)
        notify(context, message(otherVpn, label, blocked, trusted, c.settings.browserMode.first()))
    }

    /** Warden's own filter is back: the notice is out of date. */
    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NotificationIds.VPN_TAKEOVER) }
    }

    private fun notify(context: Context, message: Message) {
        val id = NotificationIds.VPN_TAKEOVER
        val restore = PendingIntent.getActivity(
            context, id, MainActivity.requestIntent(context, restoreFilter = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val vpnSettings = PendingIntent.getActivity(
            context, NotificationIds.VPN_TAKEOVER_SETTINGS, VpnController.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(restore)
            .addAction(0, "Restore", restore)
            .addAction(0, "VPN settings", vpnSettings)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}
