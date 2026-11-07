package com.warden.blocker.gray

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.ScheduleEvaluator
import com.warden.blocker.block.Verdict
import com.warden.blocker.owner.OwnerSync
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/** What the card and the tile show. */
data class GrayStatus(val permission: Boolean = false, val grayNow: Boolean = false)

/**
 * Makes the whole screen gray with Android's own colour correction ("Grayscale" /
 * monochromacy). Writing it needs WRITE_SECURE_SETTINGS, granted once over ADB (see
 * docs/GRAY_MODE.md). Follows the blocking state at every change and minute boundary while the
 * app runs (the same minute clock as the device-owner rules), and by an alarm at the next
 * change when it doesn't.
 *
 * Wiring: `GrayMode.start(this)` in `WardenApp.onCreate()`, `GrayMode.syncAndPlan()` in BootReceiver.
 */
object GrayMode {
    private const val TAG = "WardenGray"
    const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
    const val KEY_MODE = "accessibility_display_daltonizer"
    /** Android's default colour-correction mode (deuteranomaly), used when "unset" can't be written. */
    private const val DEFAULT_MODE = "12"
    private const val ALARM_MARGIN_MS = 2_000L
    const val ADB_GRANT = "adb shell pm grant com.warden.blocker android.permission.WRITE_SECURE_SETTINGS"

    private val lock = Mutex()
    @Volatile private var started = false
    @Volatile private var plannedAt: Long? = -1L

    private val _status = MutableStateFlow(GrayStatus())
    val status: StateFlow<GrayStatus> = _status.asStateFlow()

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun read(cr: ContentResolver) = DaltonizerState(
        runCatching { Settings.Secure.getString(cr, KEY_ENABLED) }.getOrNull(),
        runCatching { Settings.Secure.getString(cr, KEY_MODE) }.getOrNull(),
    )

    /** Mode before "on" (so it never flashes another filter), "off" before the mode. */
    private fun write(cr: ContentResolver, s: DaltonizerState) {
        fun putMode() {
            if (runCatching { Settings.Secure.putString(cr, KEY_MODE, s.mode) }.getOrDefault(false)) return
            if (s.mode == null) Settings.Secure.putString(cr, KEY_MODE, DEFAULT_MODE)
        }
        if (s.enabled == "1") {
            putMode()
            Settings.Secure.putString(cr, KEY_ENABLED, "1")
        } else {
            Settings.Secure.putString(cr, KEY_ENABLED, s.enabled ?: "0")
            putMode()
        }
    }

    fun start(app: Application) {
        if (started) return
        started = true
        val c = app.wardenContainer
        val prefs = GrayModePrefs.get(app)
        // A sleeping phone runs no clock and delivers inexact alarms late; the screen must be
        // right the moment it comes on again, so screen-on and unlock check too.
        val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { wakeups.tryEmit(Unit) }
        }
        runCatching {
            ContextCompat.registerReceiver(
                app, wakeReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.e(TAG, "Couldn't listen for screen-on", it) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            merge(
                wakeups,
                prefs.config.unit(),
                c.settings.masterEnabled.unit(),
                c.settings.alwaysOn.unit(),
                c.settings.focusEndsAt.unit(),
                c.repository.schedules.unit(),
                OwnerSync.minuteTicks(app),
            ).conflate().collect {
                runCatching { syncAndPlan(app) }.onFailure { Log.e(TAG, "Gray mode sync failed", it) }
            }
        }
    }

    /**
     * The one way to change the setting (card and tile): stricter or equal is saved at once,
     * anything that weakens it goes through Block protection (PIN, a wait or a lock).
     */
    suspend fun requestConfig(context: Context, new: GrayModeConfig): Verdict {
        val prefs = GrayModePrefs.get(context)
        val old = prefs.config.value
        if (old == new) return Verdict.Allow
        if (GrayModeLogic.loosens(old, new)) {
            return context.wardenContainer.protection.request(ProtectedChange.SetGrayMode(new))
        }
        prefs.setConfig(new)
        syncAndPlan(context)
        return Verdict.Allow
    }

    /** Brings the screen to the wanted state now and sets the alarm for the next change. */
    suspend fun syncAndPlan(context: Context) {
        sync(context)
        planNext(context)
    }

    /** Should the screen be gray right now? */
    suspend fun wanted(context: Context, config: GrayModeConfig = GrayModePrefs.get(context).config.value): Boolean {
        if (!config.enabled) return false
        val blocking = config.whenMode == GrayWhen.WHILE_BLOCKING && context.wardenContainer.blockEngine.isBlockingActiveNow()
        val now = TrustedClock.calendar()
        return GrayModeLogic.shouldBeGray(config, blocking, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }

    suspend fun sync(context: Context) = lock.withLock {
        val prefs = GrayModePrefs.get(context)
        val config = prefs.config.value
        val memory = prefs.memory
        val permission = hasPermission(context)
        val cr = context.contentResolver
        val want = wanted(context, config)
        if (permission && (want || memory.applied)) {
            val current = read(cr)
            val switch = if (want) GrayRestore.turnOn(current, memory) else GrayRestore.turnOff(current, memory)
            runCatching { switch.write?.let { write(cr, it) } }
                .onSuccess { prefs.memory = switch.memory }
                .onFailure { Log.e(TAG, "Couldn't change colour correction", it) }
        }
        val newStatus = GrayStatus(permission, read(cr).isGray)
        if (newStatus != _status.value) {
            _status.value = newStatus
            requestTileUpdate(context)
        }
    }

    /** When the wanted state may change next while the app isn't running (null = never by itself). */
    suspend fun nextChange(context: Context, now: Long = TrustedClock.now()): Long? {
        val config = GrayModePrefs.get(context).config.value
        if (!config.enabled) return null
        return when (config.whenMode) {
            GrayWhen.ALWAYS -> null
            GrayWhen.SCHEDULE -> GrayModeLogic.nextWindowEdge(now, TrustedClock.zone(), config.startMinute, config.endMinute)
            GrayWhen.WHILE_BLOCKING -> {
                val c = context.wardenContainer
                val schedules = c.repository.enabledSchedules()
                val cal = TrustedClock.calendar().apply { timeInMillis = now }
                GrayModeLogic.earliestAfter(
                    now,
                    listOf(
                        ScheduleEvaluator.nextStart(schedules, cal)?.timeInMillis,
                        ScheduleEvaluator.activeUntil(schedules, cal)?.timeInMillis,
                        c.settings.focusEndsAt.first(),
                    ),
                )
            }
        }
    }

    private suspend fun planNext(context: Context) {
        // Alarms run on the phone's clock (whole seconds, so the same plan isn't set again and again).
        val at = nextChange(context)?.plus(ALARM_MARGIN_MS)?.let { TrustedClock.toSystemTime(it) / 1000 * 1000 }
        if (at == plannedAt) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, GrayAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pi)
        } else {
            // Same rule as the filter's alarm: exact when allowed, else Android may run it a bit late.
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            val ok = runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }.onFailure { Log.e(TAG, "Couldn't set the alarm", it) }.isSuccess
            if (!ok) return
        }
        plannedAt = at
    }

    private fun requestTileUpdate(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, GrayModeTileService::class.java)) }
    }

    private fun <T> Flow<T>.unit(): Flow<Unit> = map { }
}

/** The alarm at the next gray-mode change: applies it and plans the one after. */
class GrayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                GrayMode.syncAndPlan(context)
            } finally {
                pending.finish()
            }
        }
    }
}
