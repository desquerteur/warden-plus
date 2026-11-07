package com.warden.blocker.time

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.warden.blocker.gray.GrayMode
import com.warden.blocker.guard.ClockChange
import com.warden.blocker.guard.ClockReading
import com.warden.blocker.guard.GuardAlerts
import com.warden.blocker.guard.SettingsGuardStore
import com.warden.blocker.system.FilterKeeper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** What the UI shows about the clock. */
data class ClockStatus(
    val autoTime: Boolean = true,
    /** Phone clock minus real time; beyond [TrustedTime.TOLERANCE_MS] the clock was set. */
    val offsetMs: Long = 0L,
    val source: TimeSource = TimeSource.SYSTEM,
    /** The time zone was changed by hand and schedules keep the old one. */
    val zoneHeld: Boolean = false,
) {
    val clockOff: Boolean get() = kotlin.math.abs(offsetMs) > TrustedTime.TOLERANCE_MS
}

/**
 * The clock every blocking decision uses instead of `System.currentTimeMillis()` — see
 * [TrustedTime] for the rules. Started once in `WardenApp.onCreate`; before that (and in unit
 * tests) it simply is the phone's clock.
 *
 * Statistics and displays that aren't about blocking keep the phone's clock.
 */
object TrustedClock {

    private const val TAG = "TrustedClock"
    private const val PREFS = "warden_trusted_clock"
    private const val TICK_MS = 60_000L
    /** Between HTTPS questions while the clock can't be trusted (Android's own network time is asked every tick). */
    private const val HTTPS_RETRY_MS = 15 * 60_000L
    /** Even a confirmed time drifts a little; ask again after this while the phone clock isn't automatic. */
    private const val NETWORK_REFRESH_MS = 6 * 60 * 60_000L

    @Volatile private var state: TrustState? = null
    @Volatile private var lastHttpsAttempt = -1L
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(ClockStatus())
    val status: StateFlow<ClockStatus> = _status.asStateFlow()

    // --- The time ---

    /** Trusted "now", epoch millis. */
    fun now(): Long {
        val s = state ?: return System.currentTimeMillis()
        return TrustedTime.now(s, SystemClock.elapsedRealtime())
    }

    /** The zone schedules follow: the phone's, unless it was changed by hand. */
    fun zone(): ZoneId {
        val s = state
        if (s != null && s.zoneHeld) runCatching { return ZoneId.of(s.zoneId) }
        return ZoneId.systemDefault()
    }

    fun timeZone(): TimeZone = TimeZone.getTimeZone(zone())

    /** A calendar at [at] (default: trusted now) in [zone]. */
    fun calendar(at: Long = now()): Calendar = Calendar.getInstance(timeZone()).apply { timeInMillis = at }

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()

    /** Start of today (trusted), epoch millis. */
    fun startOfToday(): Long = today().atStartOfDay(zone()).toInstant().toEpochMilli()

    /**
     * The phone-clock time for a trusted moment [trustedAt] — for alarms (AlarmManager RTC) and
     * other system APIs that only know the phone's clock.
     */
    fun toSystemTime(trustedAt: Long): Long = System.currentTimeMillis() + (trustedAt - now())

    /** Android's "automatic date & time". */
    fun isAutoTimeOn(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1 }.getOrDefault(true)

    fun isAutoZoneOn(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1 }.getOrDefault(true)

    // --- Lifecycle ---

    fun start(context: Context) {
        synchronized(lock) {
            if (state != null) return
            app = context.applicationContext
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val r = reading()
            state = if (r == null) null else TrustedTime.restore(load(), r)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        // All three are protected broadcasts only the system can send.
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) }
                delay(TICK_MS)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> checkpointNow(sync = true)
                Intent.ACTION_TIME_CHANGED -> scope.launch { onClockEvent(timeSet = true) }
                Intent.ACTION_TIMEZONE_CHANGED -> scope.launch { onClockEvent(timeSet = false) }
            }
        }
    }

    /** The regular look: catches changes, follows a right clock, saves a checkpoint, asks the network if needed. */
    private suspend fun tick() {
        val r = reading() ?: return
        val manipulations = mutableListOf<ClockChange>()
        synchronized(lock) {
            var s = state ?: TrustedTime.restore(load(), r)
            TrustedTime.observe(s, r).let { s = it.state; it.manipulation?.let(manipulations::add) }
            TrustedTime.onZone(s, r).let { s = it.state; it.manipulation?.let(manipulations::add) }
            s = TrustedTime.checkpoint(s, r)
            commit(s, r)
        }
        manipulations.forEach { report(it) }
        if (manipulations.isNotEmpty()) replan()
        askNetworkIfNeeded()
    }

    private suspend fun onClockEvent(timeSet: Boolean) {
        val r = reading() ?: return
        val update = synchronized(lock) {
            val s = state ?: TrustedTime.restore(load(), r)
            val u = if (timeSet) TrustedTime.onTimeSet(s, r) else TrustedTime.onZone(s, r)
            commit(TrustedTime.checkpoint(u.state, r), r)
            u
        }
        update.manipulation?.let { report(it) }
        replan()
        askNetworkIfNeeded(force = update.manipulation != null)
    }

    private suspend fun askNetworkIfNeeded(force: Boolean = false) {
        val r = reading() ?: return
        val s = state ?: return
        if (!force && !TrustedTime.wantsNetwork(s, r, NETWORK_REFRESH_MS)) return
        val net = NetworkTime.fromSystem() ?: run {
            val since = r.elapsedMs - lastHttpsAttempt
            if (lastHttpsAttempt >= 0 && since in 0 until HTTPS_RETRY_MS) return
            lastHttpsAttempt = r.elapsedMs
            kotlinx.coroutines.withContext(Dispatchers.IO) { NetworkTime.fromHttps() }
        } ?: return
        val before = now()
        synchronized(lock) {
            val current = reading() ?: return
            val cur = state ?: return
            commit(TrustedTime.onNetworkTime(cur, current, net.wallMs, net.elapsedMs), current)
        }
        Log.i(TAG, "network time: trusted moved by ${now() - before} ms")
        // Moving more than a little changes what's blocked now; let alarms follow.
        if (kotlin.math.abs(now() - before) > TrustedTime.TOLERANCE_MS) replan()
    }

    /** Saves the checkpoint (synchronously at shutdown: there's no later). */
    private fun checkpointNow(sync: Boolean) {
        val r = reading() ?: return
        synchronized(lock) {
            val s = state ?: return
            commit(TrustedTime.checkpoint(s, r), r, sync)
        }
    }

    // Under [lock].
    private fun commit(s: TrustState, r: ClockReading, sync: Boolean = false) {
        state = s
        save(s, sync)
        _status.value = ClockStatus(
            autoTime = r.autoTime,
            offsetMs = r.wallMs - TrustedTime.now(s, r.elapsedMs),
            source = s.source,
            zoneHeld = s.zoneHeld,
        )
    }

    /** Counted in "Settings guard" and told in a notification — the blocks themselves aren't affected. */
    private suspend fun report(change: ClockChange) {
        Log.w(TAG, "clock set by hand: $change")
        runCatching { SettingsGuardStore.get(app).recordClockChange(change, now()) }
        GuardAlerts.notifyClockChanged(app, change)
    }

    /** Alarms are set on the phone's clock: aim them again after it moved. */
    private suspend fun replan() {
        runCatching { FilterKeeper.scheduleNext(app) }
        runCatching { GrayMode.syncAndPlan(app) }
        runCatching { app.wardenContainer.protection.processDue() }
    }

    @SuppressLint("NewApi")
    private fun reading(): ClockReading? {
        if (!::app.isInitialized) return null
        return runCatching {
            val cr = app.contentResolver
            val wall = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            ClockReading(
                wallMs = wall,
                elapsedMs = SystemClock.elapsedRealtime(),
                bootCount = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0),
                zoneId = zone.id,
                zoneOffsetMs = zone.getOffset(wall),
                autoTime = Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) == 1,
                autoZone = Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 1) == 1,
            )
        }.getOrNull()
    }

    // --- Storage (SharedPreferences: read synchronously at start, before anything decides) ---

    private fun load(): TrustState? {
        if (!prefs.contains("anchor_wall")) return null
        return runCatching {
            TrustState(
                anchorWallMs = prefs.getLong("anchor_wall", 0L),
                anchorElapsedMs = prefs.getLong("anchor_elapsed", 0L),
                bootCount = prefs.getInt("boot_count", -1),
                source = TimeSource.valueOf(prefs.getString("source", null) ?: TimeSource.SYSTEM.name),
                checkpointWallMs = prefs.getLong("checkpoint_wall", 0L),
                offsetMs = prefs.getLong("offset", 0L),
                networkElapsedMs = prefs.getLong("network_elapsed", -1L),
                zoneId = prefs.getString("zone", null) ?: TimeZone.getDefault().id,
                zoneHeld = prefs.getBoolean("zone_held", false),
            )
        }.getOrNull()
    }

    private fun save(s: TrustState, sync: Boolean) {
        prefs.edit(commit = sync) {
            putLong("anchor_wall", s.anchorWallMs)
            putLong("anchor_elapsed", s.anchorElapsedMs)
            putInt("boot_count", s.bootCount)
            putString("source", s.source.name)
            putLong("checkpoint_wall", s.checkpointWallMs)
            putLong("offset", s.offsetMs)
            putLong("network_elapsed", s.networkElapsedMs)
            putString("zone", s.zoneId)
            putBoolean("zone_held", s.zoneHeld)
        }
    }
}
