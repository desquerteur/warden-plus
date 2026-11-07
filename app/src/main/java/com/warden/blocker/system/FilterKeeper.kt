package com.warden.blocker.system

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.WardenApp
import com.warden.blocker.block.BlockStateClock
import com.warden.blocker.block.ScheduleEvaluator
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.vpn.WardenVpnService
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps the website filter (VPN) running whenever it should ([BlockStateClock.filterShouldRun]).
 * The VPN follows schedules on its own clock, so this is the safety net for when it isn't
 * running: an alarm at the next schedule start re-arms it, and so do boot and app updates
 * ([BootReceiver]). Android doesn't restart a force-stopped app; opening Warden+ does.
 */
object FilterKeeper {
    private const val TAG = "WardenFilter"

    /** Wake a few seconds after the window opened, so it's surely "active" then. */
    private const val ALARM_MARGIN_MS = 5_000L

    @Volatile private var started = false

    /** Re-plans the alarm whenever something that decides it changes. */
    fun start(app: Application) {
        if (started) return
        started = true
        val c = app.wardenContainer
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            merge(
                c.settings.masterEnabled.unit(),
                c.settings.alwaysOn.unit(),
                c.repository.schedules.unit(),
            ).collect { runCatching { scheduleNext(app) }.onFailure { Log.e(TAG, "Couldn't plan the next check", it) } }
        }
    }

    /**
     * Starts the filter if it should run and isn't. Needs the VPN consent given before. From
     * the background Android may refuse; with [notifyOnFailure] the user then gets a
     * notification that re-arms it with one tap.
     */
    suspend fun ensureRunning(context: Context, notifyOnFailure: Boolean) {
        val c = context.wardenContainer
        if (WardenVpnService.running || !c.blockEngine.filterShouldRun()) return
        if (VpnController.consentIntent(context) != null) return // only the app can ask for consent
        runCatching { VpnController.start(context) }
            .onFailure {
                Log.e(TAG, "Couldn't start the filter in the background", it)
                if (notifyOnFailure) notifyPaused(context)
            }
    }

    /** Sets (or clears) the alarm for the next schedule start. */
    suspend fun scheduleNext(context: Context) {
        val c = context.wardenContainer
        val now = TrustedClock.calendar()
        val nextStart = ScheduleEvaluator.nextStart(c.repository.enabledSchedules(), now)?.timeInMillis
        val at = BlockStateClock.nextFilterCheck(
            c.settings.masterEnabled.first(), c.settings.alwaysOn.first(), nextStart, now.timeInMillis,
        )
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        if (at == null) { am.cancel(pi); return }
        val wakeAt = TrustedClock.toSystemTime(at + ALARM_MARGIN_MS) // alarms run on the phone's clock
        // Exact when allowed (Android 12+: "Alarms & reminders"); otherwise Android may run it a
        // few minutes late in Doze — the VPN, if running, has switched on by itself already.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pi)
        }.onFailure { Log.e(TAG, "Couldn't set the alarm", it) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, FilterAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyPaused(context: Context) {
        val pi = PendingIntent.getActivity(
            context, NotificationIds.FILTER_PAUSED, MainActivity.requestIntent(context, enableBlocking = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WardenApp.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Website blocking is paused")
            .setContentText("A schedule started, but Android didn't let the filter start. Tap to turn it on.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A schedule started, but Android didn't let Warden+ start its website filter in the " +
                        "background. Tap to turn it on. Tip: Block protection › Battery: never put to sleep.",
                ),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NotificationIds.FILTER_PAUSED, notification) }
    }

    private fun <T> Flow<T>.unit(): Flow<Unit> = map { }
}

/** The alarm at a schedule start: re-arms the filter if needed and plans the next one. */
class FilterAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FilterKeeper.ensureRunning(context, notifyOnFailure = true)
                FilterKeeper.scheduleNext(context)
            } finally {
                pending.finish()
            }
        }
    }
}
