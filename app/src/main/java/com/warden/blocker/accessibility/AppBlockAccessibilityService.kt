package com.warden.blocker.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.warden.blocker.block.AccessDecision
import com.warden.blocker.block.LimitReason
import com.warden.blocker.feature.AppFeature
import com.warden.blocker.feature.FeatureCatalog
import com.warden.blocker.feature.FeatureDetector
import com.warden.blocker.guard.SettingsGuardHook
import com.warden.blocker.system.AccessibilityAlert
import com.warden.blocker.system.BlockLoopAlert
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.ui.intercept.InterceptActivity
import com.warden.blocker.util.CriticalApps
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the foreground app and asks [com.warden.blocker.block.AccessController] what to do,
 * launching [InterceptActivity] for a hard block, limit notice, or mindful pause.
 *
 * Only an app the user really opened is blocked: [ForegroundGate] filters out widgets on the
 * home screen, overlays, keyboards and the like, and keeps the block screen from looping.
 * [WindowShield] keeps it blocked in split screens and pop-up windows: it looks at every visible
 * app window, not just the focused one (issue #3: a block screen pushed aside in a pop-up).
 *
 * It also performs *in-app feature* blocking: while a supported app is foreground, it scans
 * the visible node tree (throttled) for signals of a blocked feature — e.g. Instagram Reels
 * or YouTube Shorts — and intercepts when one is on screen.
 *
 * Privacy: it inspects the foreground package name and, for feature detection, view ids /
 * visible labels of on-screen nodes. Nothing is stored or transmitted.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsGuard by lazy { SettingsGuardHook(this) }
    private val browserGuard by lazy { BrowserGuard(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val gate = ForegroundGate.shared
    private val shield = WindowShield.shared
    private val activityCache = HashMap<String, Boolean>()
    // Only touched on the main thread (accessibility events, rechecks and the grant timer).
    private var grantRecheck: Job? = null

    private var lastFeatureScan = 0L
    @Volatile private var interceptedFeatureKey: String? = null

    // Cache blocking-active + enabled features so scroll-heavy apps don't hammer DataStore/DB
    // on every content-changed event. Refreshed at most once per CACHE_TTL_MS.
    @Volatile private var cachedBlockingActive = false
    @Volatile private var cachedFeatureKeys: Set<String> = emptySet()
    @Volatile private var cacheStamp = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Remembered so Home and the after-update check can say when Android switched us off.
        scope.launch { runCatching { wardenContainer.settings.markAccessibilityUsed() } }
        AccessibilityAlert.cancel(this)
        settingsGuard.start()
        // The block screen lost focus (a pop-up over it, on Samsung): look at all windows now.
        WindowShield.checkRequest = { handler.post { scheduleWindowCheck(FOCUS_LOST_SETTLE_MS) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // A window appeared, moved, was resized or took focus: split screens and pop-ups.
            scheduleWindowCheck()
            return
        }
        val pkg = event.packageName?.toString() ?: return
        // Settings guard (warn only): before the foreground logic, which never looks at Settings (critical).
        settingsGuard.onEvent(event)
        browserGuard.onEvent(event, pkg) // VPN bypass protection: Browser mode, who took the VPN

        // Only a window *state* change can mean "another app came to the front". Content
        // changes fire constantly — also from widgets on the home screen — and never block.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val confirmed = isActivityOf(pkg, event.className?.toString())
            evaluate(pkg, confirmed, attempt = 0)
            scheduleWindowCheck()
        }
        if (pkg == packageName) return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            maybeScanFeatures(pkg)
        }
    }

    // --- Whole-app blocking ---
    private fun evaluate(pkg: String, confirmed: Boolean, attempt: Int) {
        val before = gate.foreground
        val verdict = gate.onWindowStateChanged(
            eventPkg = pkg,
            activeAppPkg = activeAppPackage(),
            confirmed = confirmed,
            isCritical = ::isCritical,
            now = SystemClock.uptimeMillis(),
            attempt = attempt,
        )
        if (gate.foreground != before) {
            interceptedFeatureKey = null // new app in front; allow feature re-detection
            grantRecheck?.cancel() // and an earlier grant's timer is moot
        }
        when (verdict) {
            ForegroundGate.Verdict.Ignore -> Unit
            is ForegroundGate.Verdict.RecheckAt ->
                handler.postAtTime({ evaluate(verdict.pkg, verdict.confirmed, verdict.attempt) }, verdict.atMs)
            is ForegroundGate.Verdict.Check -> checkAccess(verdict.pkg, verdict.confirmed)
        }
    }

    private fun checkAccess(pkg: String, confirmed: Boolean) {
        scope.launch {
            val decision = wardenContainer.accessController.decideForPackage(pkg)
            if (decision is AccessDecision.Allow) {
                shield.allowed(pkg, SystemClock.uptimeMillis())
                recheckWhenGrantEnds(pkg)
                return@launch
            }
            // The lookup took a moment: make sure the user is still in that app, not back Home.
            if (activeAppPackage() != pkg) { gate.forget(pkg); return@launch }
            if (!passLoopGuard(pkg, confirmed)) return@launch
            showBlockScreen(pkg, decision)
        }
    }

    private fun showBlockScreen(pkg: String, decision: AccessDecision) {
        lastBlockScreenAt = SystemClock.uptimeMillis()
        when (decision) {
            is AccessDecision.Allow -> return
            is AccessDecision.Pause -> launchIntercept(InterceptActivity.KIND_PAUSE, decision.item.id, null, null)
            is AccessDecision.HardBlock -> launchIntercept(InterceptActivity.KIND_BLOCK, decision.item.id, null, null)
            is AccessDecision.LimitReached -> launchIntercept(InterceptActivity.KIND_LIMIT, decision.item.id, decision.reason, null)
        }
        // From now on, keep an eye on it in every window, not just the focused one.
        shield.shield(pkg, SystemClock.uptimeMillis())
        handler.post { scheduleWindowCheck() }
    }

    // --- Split screens and pop-up windows ---
    // Only touched on the main thread.
    private var windowCheckSince = 0L
    @Volatile private var lastBlockScreenAt = 0L
    private val windowCheck = Runnable {
        windowCheckSince = 0L
        checkWindows()
    }

    /** Look at all windows once they have settled (window events come in bursts). */
    private fun scheduleWindowCheck(delayMs: Long = WINDOW_SETTLE_MS) {
        val now = SystemClock.uptimeMillis()
        if (windowCheckSince == 0L) {
            windowCheckSince = now
        } else if (now - windowCheckSince >= WINDOW_MAX_WAIT_MS) {
            return // a pop-up being dragged sends events nonstop: let the pending check run
        }
        handler.removeCallbacks(windowCheck)
        handler.postDelayed(windowCheck, delayMs)
    }

    private fun checkWindows() {
        val now = SystemClock.uptimeMillis()
        val step = shield.assess(appWindows(), packageName, ::isCritical, now)
        if (step != WindowShield.Step.None && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window step $step")
        when (step) {
            WindowShield.Step.None -> Unit
            is WindowShield.Step.Later -> scheduleWindowCheck((step.atMs - now).coerceAtLeast(50L))
            is WindowShield.Step.CheckSide -> enforce(step.pkg, Remedy.BLOCK_SCREEN)
            is WindowShield.Step.ShowBlockScreen -> enforce(step.pkg, Remedy.BLOCK_SCREEN)
            is WindowShield.Step.GoHome -> enforce(step.pkg, Remedy.HOME)
            is WindowShield.Step.PressWindowButton -> enforce(step.pkg, Remedy.WINDOW_BUTTON)
            is WindowShield.Step.Back -> enforce(step.pkg, Remedy.BACK)
            is WindowShield.Step.GaveUp -> BlockLoopAlert.notifyWindowStuck(this, step.pkg)
        }
    }

    private enum class Remedy { BLOCK_SCREEN, HOME, WINDOW_BUTTON, BACK }

    /**
     * [pkg] is visible somewhere without the block screen covering it. Ask again (a grant or the
     * end of a schedule lets it through), then do [remedy]: show the block screen, leave to the
     * home screen, press its pop-up window's Maximize / Close button, or Back.
     */
    private fun enforce(pkg: String, remedy: Remedy) {
        scope.launch {
            val decision = wardenContainer.accessController.decideForPackage(pkg)
            withContext(Dispatchers.Main) {
                if (decision is AccessDecision.Allow) {
                    shield.allowed(pkg, SystemClock.uptimeMillis())
                    return@withContext
                }
                // Nothing to press: no loop-guard attempt used up, go on with the next step.
                val button = if (remedy == Remedy.WINDOW_BUTTON) findWindowButton(pkg) else null
                if (remedy == Remedy.WINDOW_BUTTON && button == null) {
                    shield.noWindowButton(pkg)
                    scheduleWindowCheck(50L)
                    return@withContext
                }
                // Own key: if a window can't be got rid of, this pauses only the window guard.
                if (!passLoopGuard(WINDOW_KEY_PREFIX + pkg, confirmed = true)) return@withContext
                when (remedy) {
                    Remedy.HOME -> {
                        shield.shield(pkg, SystemClock.uptimeMillis())
                        gate.noteWentHome(SystemClock.uptimeMillis())
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        scheduleWindowCheck(REMEDY_SETTLE_MS)
                    }
                    Remedy.WINDOW_BUTTON -> {
                        val clicked = button?.target?.click() == true
                        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "pressed ${button?.control} (${button?.label}) for $pkg: $clicked")
                        if (!clicked) shield.noWindowButton(pkg)
                        scheduleWindowCheck(if (clicked) REMEDY_SETTLE_MS else 50L)
                    }
                    Remedy.BACK -> {
                        // Only while the pop-up has focus (WindowShield checks), so Back goes to it.
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        scheduleWindowCheck(REMEDY_SETTLE_MS)
                    }
                    Remedy.BLOCK_SCREEN ->
                        if (SystemClock.uptimeMillis() - lastBlockScreenAt < BLOCK_SCREEN_STARTING_MS) {
                            // A block screen was just launched and may not be on screen yet: look again.
                            scheduleWindowCheck(BLOCK_SCREEN_STARTING_MS)
                        } else {
                            showBlockScreen(pkg, decision)
                        }
                }
            }
        }
    }

    /**
     * The Maximize / Close / Minimize button of [pkg]'s pop-up window, searched in its own
     * window and in System UI's caption windows (see [WindowControls]). Null if there's none.
     */
    private fun findWindowButton(pkg: String): WindowControls.Hit? = runCatching {
        val all = windows // z-order, top first
        val rootsByWindow = all.map { it.root }
        val owners = rootsByWindow.map { it?.packageName?.toString() }
        val targetIndex = all.indices.firstOrNull { i ->
            all[i].type == AccessibilityWindowInfo.TYPE_APPLICATION && !all[i].isInPictureInPictureMode && owners[i] == pkg
        } ?: return@runCatching null
        val r = Rect()
        all[targetIndex].getBoundsInScreen(r)
        val box = WindowControls.Box(r.left, r.top, r.right, r.bottom)
        // The app's own window and the System UI / Samsung caption windows right above it —
        // never other apps, never another pop-up's caption.
        val roots = (listOf(targetIndex) + WindowControls.decorationsOf(targetIndex, owners))
            .mapNotNull { i -> rootsByWindow[i]?.let { A11yControl(it) } }
        val strip = (CAPTION_STRIP_DP * resources.displayMetrics.density).toInt()
        val hit = WindowControls.find(roots, box, strip)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "window button for $pkg at $box: ${hit?.control} ${hit?.label}; windows=" +
                all.joinToString { w ->
                    val b = Rect().also { w.getBoundsInScreen(it) }
                    "${w.type}/${w.root?.packageName}/${b.toShortString()}"
                })
        }
        hit
    }.getOrNull()

    /** All visible app windows (not keyboards, system bars, overlays). Empty if Android can't say. */
    private fun appWindows(): List<WindowShield.AppWindow> = runCatching {
        val (sw, sh) = screenSize()
        val r = Rect()
        windows.mapNotNull { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val pkg = w.root?.packageName?.toString() ?: return@mapNotNull null
            w.getBoundsInScreen(r)
            WindowShield.AppWindow(
                pkg = pkg,
                active = w.isActive || w.isFocused,
                coversScreen = WindowShield.covers(r.width(), r.height(), sw, sh),
                area = WindowShield.area(r.width(), r.height(), sw, sh),
                pip = w.isInPictureInPictureMode,
            )
        }
    }.getOrDefault(emptyList())

    /** Size of the screen in use (the Fold's cover or inner screen), in pixels. */
    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 0 to 0
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /**
     * [pkg] is let through on a timed grant ("Continue for 1 min"): when it runs out and the
     * app is still in front, decide again, so staying in the app brings the pause back. One
     * timer, no polling; it's cancelled as soon as another app comes to the front.
     */
    private suspend fun recheckWhenGrantEnds(pkg: String) {
        val repo = wardenContainer.repository
        val item = repo.appItem(pkg) ?: return
        val endsAt = repo.activeGrant(item.id, TrustedClock.now())?.expiresAt ?: return
        withContext(Dispatchers.Main) {
            if (gate.foreground != pkg) return@withContext
            grantRecheck?.cancel()
            grantRecheck = scope.launch(Dispatchers.Main) {
                delay(endsAt - TrustedClock.now() + GRANT_RECHECK_MARGIN_MS)
                if (gate.foreground != pkg) return@launch
                // Still in the app: let the gate treat it as freshly opened, with all its checks.
                gate.forget(pkg)
                evaluate(pkg, confirmed = true, attempt = 0)
            }
        }
    }

    private fun passLoopGuard(key: String, confirmed: Boolean): Boolean =
        when (gate.allowIntercept(key, confirmed, SystemClock.uptimeMillis())) {
            ForegroundGate.Intercept.SHOW -> true
            ForegroundGate.Intercept.SUPPRESS -> false
            ForegroundGate.Intercept.SUPPRESS_AND_NOTIFY -> { BlockLoopAlert.notifyPaused(this, key); false }
        }

    /**
     * Package of the app window the user is actually using — not of a widget, overlay,
     * keyboard, notification shade or picture-in-picture window. Null if Android can't say.
     */
    private fun activeAppPackage(): String? = runCatching {
        val window = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused) && !it.isInPictureInPictureMode
        } ?: return@runCatching null
        // A window that just appeared may not have its content yet; it is the active one, though.
        (window.root ?: rootInActiveWindow)?.packageName?.toString()
    }.getOrNull()

    /** Whether [className] is one of [pkg]'s activities, i.e. the app itself was opened. */
    private fun isActivityOf(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val key = "$pkg/$className"
        return synchronized(activityCache) {
            activityCache.getOrPut(key) {
                runCatching { packageManager.getActivityInfo(ComponentName(pkg, className), 0) }.isSuccess
            }.also { if (activityCache.size > 512) activityCache.clear() }
        }
    }

    private fun isCritical(pkg: String): Boolean = CriticalApps.isCritical(this, pkg)

    // --- In-app feature blocking ---
    private fun maybeScanFeatures(pkg: String) {
        val features = FeatureCatalog.byPackage(pkg)
        if (features.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastFeatureScan < SCAN_THROTTLE_MS) return
        lastFeatureScan = now

        scope.launch {
            if (now - cacheStamp > CACHE_TTL_MS) {
                cachedBlockingActive = wardenContainer.blockEngine.isBlockingActiveNow()
                cachedFeatureKeys = wardenContainer.settings.enabledFeatureKeys.first()
                cacheStamp = now
            }
            if (!cachedBlockingActive) return@launch
            val active = features.filter { it.key in cachedFeatureKeys }
            if (active.isEmpty()) return@launch

            // Only inside the app's own window: a YouTube widget on the home screen is not YouTube.
            val root = rootInActiveWindow
            if (!gate.mayScanFeatures(pkg, root?.packageName?.toString(), ::isCritical, SystemClock.uptimeMillis())) return@launch
            val hit = FeatureDetector.detect(active, A11yNode(root))
            if (hit == null) {
                interceptedFeatureKey = null
                return@launch
            }
            if (hit.key == interceptedFeatureKey) return@launch // already handled; avoid re-launch loop
            interceptedFeatureKey = hit.key
            // Seen inside the app's own window, so it counts as a deliberate open for the loop guard.
            if (!passLoopGuard("feature:${hit.key}", confirmed = true)) return@launch
            launchIntercept(InterceptActivity.KIND_FEATURE, -1L, null, hit)
        }
    }

    private fun launchIntercept(kind: String, itemId: Long, reason: LimitReason?, feature: AppFeature?) {
        val intent = Intent(this, InterceptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(InterceptActivity.EXTRA_KIND, kind)
            .putExtra(InterceptActivity.EXTRA_ITEM_ID, itemId)
            .putExtra(InterceptActivity.EXTRA_REASON, reason?.name)
            .putExtra(InterceptActivity.EXTRA_FEATURE_LABEL, feature?.label)
        startActivity(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        WindowShield.checkRequest = null
        settingsGuard.stop()
        browserGuard.stop()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SCAN_THROTTLE_MS = 400L
        const val CACHE_TTL_MS = 2000L

        /** Wake just after the grant's end, so it has surely expired. */
        const val GRANT_RECHECK_MARGIN_MS = 500L

        /** Quiet time after window changes before all windows are looked at. */
        const val WINDOW_SETTLE_MS = 450L
        const val WINDOW_MAX_WAIT_MS = 1_500L
        const val WINDOW_KEY_PREFIX = "window:"
        const val BLOCK_SCREEN_STARTING_MS = 1_500L
        const val FOCUS_LOST_SETTLE_MS = 150L
        /** Time for a pressed window button / Back / Home to take effect before looking again. */
        const val REMEDY_SETTLE_MS = 700L
        /** How far from a pop-up's top edge its caption buttons may sit. */
        const val CAPTION_STRIP_DP = 72f
        /** `adb shell setprop log.tag.WardenWindows DEBUG` shows what the window guard does. */
        const val TAG = "WardenWindows"
    }
}
