package com.warden.blocker.guard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.warden.blocker.R
import com.warden.blocker.accessibility.A11yNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * "Settings guard" inside the accessibility service — the service only calls [start],
 * [onEvent] and [stop]. Mode "Warn only": when a page that could switch Warden+ off is opened
 * ([SettingsPageDetector]), the user gets a short hint and it is counted. The page itself is
 * left alone. Also watches for the clock or time zone being changed by hand ([ClockWatch]).
 *
 * Cheap for everything else: events from packages that aren't watched return at once.
 */
class SettingsGuardHook(private val service: AccessibilityService) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val handler = Handler(Looper.getMainLooper())
    private val store = SettingsGuardStore.get(service)
    private val detector = SettingsPageDetector(selfIdentity(service))
    private val throttle = GuardThrottle()

    @Volatile private var enabled = false
    @Volatile private var watchDateTime = false

    // Main thread only: the activity / dialog class of the last window change, per package.
    private var stateClass: String? = null
    private var stateClassPkg: String? = null
    private var pendingPkg: String? = null
    private val scan = Runnable { pendingPkg?.let { pkg -> pendingPkg = null; launchScan(pkg, classFor(pkg)) } }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { checkClock() }
    }

    fun start() {
        scope.launch {
            store.enabled.distinctUntilChanged().collect { on ->
                enabled = on
                if (on) GuardAlerts.showStatus(service) else GuardAlerts.hideStatus(service)
            }
        }
        scope.launch { store.watchDateTime.collect { watchDateTime = it } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Both are protected broadcasts only the system can send.
        runCatching { ContextCompat.registerReceiver(service, clockReceiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        checkClock()
    }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (!SettingsPageDetector.isWatchedPackage(pkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            stateClassPkg = pkg
            stateClass = event.className?.toString()
        }
        if (!enabled) return
        // Pages fill in over a few events; look once they've settled a little.
        pendingPkg = pkg
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, SCAN_DELAY_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { service.unregisterReceiver(clockReceiver) }
        GuardAlerts.hideStatus(service) // only shown while the guard is really working
        scope.cancel()
    }

    private fun classFor(pkg: String): String? = if (stateClassPkg == pkg) stateClass else null

    private fun launchScan(pkg: String, className: String?) {
        scope.launch {
            val screen = snapshot(pkg, className) ?: return@launch
            val target = detector.detect(screen, watchDateTime) ?: return@launch
            val now = SystemClock.elapsedRealtime()
            if (!throttle.shouldWarn(target, now)) return@launch
            runCatching { store.recordWarned(target, System.currentTimeMillis()) }
            handler.post {
                Toast.makeText(service, "Settings guard: ${target.label} can switch Warden+ off.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The window [pkg] shows in right now (app window or dialog), or null. */
    private fun snapshot(pkg: String, className: String?): ScreenSnapshot? = runCatching {
        val window = service.windows.firstOrNull { w ->
            w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && w.root?.packageName?.toString() == pkg
        }
        val root = window?.root ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?: return@runCatching null
        ScreenSnapshot.fromTree(pkg, className, window?.title?.toString(), A11yNode(root))
    }.getOrNull()

    private fun checkClock() {
        scope.launch {
            val reading = readClock() ?: return@launch
            val result = ClockWatch.check(store.anchor(), reading)
            store.saveAnchor(result.anchor)
            val change = result.change ?: return@launch
            if (!change.suspicious) return@launch // Android set it itself (network time, travel)
            store.recordClockChange(change, reading.wallMs)
            if (enabled) GuardAlerts.notifyClockChanged(service, change)
        }
    }

    private fun readClock(): ClockReading? = runCatching {
        val cr = service.contentResolver
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

    private companion object {
        const val SCAN_DELAY_MS = 250L

        fun selfIdentity(context: Context) = SelfIdentity(
            packageName = context.packageName,
            appLabel = context.getString(R.string.app_name),
            serviceLabel = context.getString(R.string.accessibility_label),
            serviceDescription = context.getString(R.string.accessibility_description),
            adminDescription = context.getString(R.string.device_admin_description),
        )
    }
}
