package com.warden.blocker.system

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp
import com.warden.blocker.usage.AppsHelper

/**
 * Last line of defence against locking the user out: the block screen kept coming back for
 * the same app within seconds (see ForegroundGate.allowIntercept), so Warden+ stopped showing
 * it for a minute — and says so instead of trapping the user in a loop.
 */
object BlockLoopAlert {

    private const val NOTIF_ID = NotificationIds.BLOCK_LOOP

    fun notifyPaused(context: Context, key: String) {
        val name = if (key.startsWith("feature:")) "this feed" else AppsHelper.labelFor(context, key.removePrefix("window:"))
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, MainActivity.requestIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Block screen paused for $name")
            .setContentText("It kept popping up, so Warden+ stopped it for a minute.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "The block screen for $name kept coming back within seconds — for example " +
                        "because of a home-screen widget. So you don't get stuck, Warden+ stopped " +
                        "showing it for one minute. Blocking continues after that.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("com.warden.blocker.ALERTS")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        // Without the notification permission this is a no-op; the loop is still broken.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
    }

    /**
     * A blocked app's pop-up / split window stayed on screen although Warden+ pressed its
     * window buttons, Back and Home (WindowShield's ladder): ask the user to close it. It stays
     * blocked — touching it brings the block screen back.
     */
    fun notifyWindowStuck(context: Context, pkg: String) {
        val name = AppsHelper.labelFor(context, pkg)
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID_WINDOW, MainActivity.requestIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("$name is blocked – close its pop-up window")
            .setContentText("Warden+ couldn't close the floating window of $name.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$name is blocked, but its pop-up or split-screen window stayed on screen. " +
                        "Warden+ tried its window buttons, Back and Home. Close the window with its X " +
                        "(or drag it away). Each time you use it, the block screen comes back.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("com.warden.blocker.ALERTS")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID_WINDOW, notification) }
    }

    private const val NOTIF_ID_WINDOW = NotificationIds.WINDOW_STUCK
}
