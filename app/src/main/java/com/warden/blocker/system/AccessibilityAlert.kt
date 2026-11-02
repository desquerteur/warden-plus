package com.warden.blocker.system

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp

/**
 * Android switches a sideloaded app's accessibility service off whenever the app is updated
 * from an APK file ("restricted settings", Android 13+). App blocking then silently stops, so
 * Warden says so: a notification right after the update and a red card on Home.
 */
object AccessibilityAlert {

    private const val NOTIF_ID = 1002

    /**
     * Whether a missing accessibility service is worth an alarm: it was in use before, or the
     * user clearly relies on it (blocking switched on, apps on the blocklist). A fresh install
     * that was never set up just gets the normal "Finish setup" card.
     */
    fun shouldWarn(accessibilityOn: Boolean, usedBefore: Boolean, blockingOn: Boolean, hasBlockedApps: Boolean): Boolean =
        !accessibilityOn && (usedBefore || blockingOn || hasBlockedApps)

    fun notifyTurnedOff(context: Context, afterUpdate: Boolean = true) {
        val why = if (afterUpdate) "Android switched it off after the update" else "It was switched off in Android settings"
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, MainActivity.requestIntent(context, openSetup = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("App blocking was turned off")
            .setContentText("$why. Tap to turn it back on.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Warden+'s accessibility access is off (${why.replaceFirstChar { it.lowercase() }}), so apps " +
                        "aren't blocked right now. Tap to turn it back on.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Own group: otherwise Android bundles it with the VPN notice, and tapping the bundle
            // just opens Home instead of Setup.
            .setGroup("com.warden.blocker.ALERTS")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        // Without the notification permission this is a no-op; Home still shows the red card.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}
