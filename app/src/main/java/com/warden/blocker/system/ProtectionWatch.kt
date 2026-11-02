package com.warden.blocker.system

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.flow.first

/**
 * Notices when protection was switched off from outside — "Force stop", a battery saver
 * putting Warden+ to sleep, the VPN or accessibility turned off in Android settings. No app
 * can prevent all of that, but it shouldn't go unnoticed: a notification and a count in Usage.
 *
 * While the filter runs it leaves a heartbeat every minute; a gap found when it (or the app)
 * starts again means it was stopped without the user switching blocking off in Warden+.
 */
object ProtectionWatch {

    private const val PREFS = "warden_watch"
    private const val LAST_BEAT = "last_beat"
    private const val CLEAN_STOP = "clean_stop"
    private const val A11Y_ON = "a11y_on"
    private const val NOTIF_ID = NotificationIds.PROTECTION_WATCH

    /** Gaps up to this long are normal (a minute between beats, a slow restart). */
    const val MIN_GAP_MS = 3 * 60_000L

    /**
     * How long protection was off, or null if it wasn't (or the gap is explained): not expected
     * to run, stopped on purpose ([cleanStopAt] after the last beat), or only off while the
     * phone was restarting — then just the time since boot counts.
     */
    fun interruption(expected: Boolean, lastBeat: Long, cleanStopAt: Long, bootAt: Long, now: Long): Long? {
        if (!expected || lastBeat == 0L || cleanStopAt >= lastBeat) return null
        val gap = now - maxOf(lastBeat, bootAt)
        return gap.takeIf { it > MIN_GAP_MS }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beat(context: Context) = prefs(context).edit { putLong(LAST_BEAT, System.currentTimeMillis()) }

    /** Blocking was stopped on purpose (switch, focus end, or a gap already reported). */
    fun markCleanStop(context: Context) = prefs(context).edit { putLong(CLEAN_STOP, System.currentTimeMillis()) }

    /**
     * Call when the filter starts or the app opens: reports a gap since the last heartbeat.
     * [running] is whether the filter is up right now (then it also leaves a fresh beat).
     */
    suspend fun check(context: Context, running: Boolean) {
        val c = context.wardenContainer
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val expected = c.settings.masterEnabled.first() || c.settings.focusEndsAt.first() > p.getLong(LAST_BEAT, 0L)
        val gap = interruption(
            expected, p.getLong(LAST_BEAT, 0L), p.getLong(CLEAN_STOP, 0L),
            bootAt = now - SystemClock.elapsedRealtime(), now = now,
        )
        if (gap != null) {
            markCleanStop(context) // reported once, not again when the filter comes back
            c.settings.recordProtectionOff(now)
            notify(
                context, "Warden+ was stopped",
                "Blocking was off for about ${gap / 60_000} min — Warden+ was force-stopped or put to sleep. " +
                    if (running) "It's back on now." else "Open Warden+ to turn it back on.",
            )
        }
        if (running) beat(context)
        checkAccessibility(context)
    }

    /** Accessibility switched off while it was on: app blocking stopped. Reported once. */
    suspend fun checkAccessibility(context: Context) {
        val p = prefs(context)
        val on = PermissionsHelper.isAccessibilityEnabled(context)
        val wasOn = p.getBoolean(A11Y_ON, on)
        p.edit { putBoolean(A11Y_ON, on) }
        if (wasOn && !on) {
            context.wardenContainer.settings.recordProtectionOff(System.currentTimeMillis())
            AccessibilityAlert.notifyTurnedOff(context, afterUpdate = false)
        }
    }

    /** The VPN was disconnected in Android settings or another VPN took over. */
    suspend fun onVpnRevoked(context: Context) {
        markCleanStop(context)
        context.wardenContainer.settings.recordProtectionOff(System.currentTimeMillis())
        notify(
            context, "Website blocking was turned off",
            "Warden+'s VPN was disconnected in Android settings or another VPN app took over. Tap to turn it back on.",
        )
    }

    private fun notify(context: Context, title: String, text: String) {
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, MainActivity.requestIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("com.warden.blocker.ALERTS")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
    }
}
