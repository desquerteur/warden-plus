package com.warden.blocker.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
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

/**
 * "Settings guard" inside the accessibility service — the service only calls [start],
 * [onEvent] and [stop]. Mode "Warn only": when a page that could switch Warden+ off is opened
 * ([SettingsPageDetector]), the user gets a short hint and it is counted. The page itself is
 * left alone. Changes of the clock are watched by [com.warden.blocker.time.TrustedClock].
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

    fun start() {
        scope.launch {
            store.enabled.distinctUntilChanged().collect { on ->
                enabled = on
                if (on) GuardAlerts.showStatus(service) else GuardAlerts.hideStatus(service)
            }
        }
        scope.launch { store.watchDateTime.collect { watchDateTime = it } }
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
