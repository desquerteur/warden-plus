package com.warden.blocker.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp
import com.warden.blocker.system.NotificationIds

/**
 * Notifications of "Settings guard": the always-visible "Settings guard is on" note while it is
 * switched on (nothing runs unseen), and a notice when the clock was changed by hand.
 */
object GuardAlerts {

    private const val STATUS_ID = NotificationIds.SETTINGS_GUARD
    private const val CLOCK_ID = NotificationIds.CLOCK_CHANGED
    private const val STATUS_CHANNEL_ID = "warden_settings_guard"

    fun showStatus(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL_ID, "Settings guard", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Settings guard is on – managed by Warden+")
            .setContentText("You get a hint when a page that could switch Warden+ off is opened.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "You get a hint when a system page that could switch Warden+ off is opened " +
                        "(its App info, Accessibility, Device admin, VPN). The page stays usable. " +
                        "Turn it off in Warden+ → Settings → Protection → Settings guard.",
                ),
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context, STATUS_ID))
            .build()
        // Without the notification permission this is a no-op; the Settings card still says it.
        runCatching { nm.notify(STATUS_ID, notification) }
    }

    fun hideStatus(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(STATUS_ID) }
    }

    fun notifyClockChanged(context: Context, change: ClockChange) {
        val what = describeClockChange(change.kind, change.deltaMs)
        val zones = if (change.fromZone != null && change.toZone != null) " (${change.fromZone} → ${change.toZone})" else ""
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("The clock was changed")
            .setContentText("Your blocks keep running on the real time.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "The clock was changed – your blocks keep running on the real time. " +
                        "$what$zones by hand. Focus sessions, \"Block until\", schedules, waits and " +
                        "locks don't end any sooner. Turn on automatic date & time to set the clock right " +
                        "again. Warden+ counts it in Settings → Protection → Settings guard.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("com.warden.blocker.ALERTS")
            .setContentIntent(openApp(context, CLOCK_ID))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(CLOCK_ID, notification) }
    }

    private fun openApp(context: Context, id: Int) = PendingIntent.getActivity(
        context, id, MainActivity.requestIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
