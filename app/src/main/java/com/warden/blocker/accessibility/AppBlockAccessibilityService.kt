package com.warden.blocker.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.warden.blocker.block.AccessDecision
import com.warden.blocker.block.LimitReason
import com.warden.blocker.feature.AppFeature
import com.warden.blocker.feature.FeatureCatalog
import com.warden.blocker.feature.FeatureDetector
import com.warden.blocker.system.AccessibilityAlert
import com.warden.blocker.system.BlockLoopAlert
import com.warden.blocker.ui.intercept.InterceptActivity
import com.warden.blocker.util.CriticalApps
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches the foreground app and asks [com.warden.blocker.block.AccessController] what to do,
 * launching [InterceptActivity] for a hard block, limit notice, or mindful pause.
 *
 * Only an app the user really opened is blocked: [ForegroundGate] filters out widgets on the
 * home screen, overlays, keyboards and the like, and keeps the block screen from looping.
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
    private val handler = Handler(Looper.getMainLooper())
    private val gate = ForegroundGate.shared
    private val activityCache = HashMap<String, Boolean>()

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return

        // Only a window *state* change can mean "another app came to the front". Content
        // changes fire constantly — also from widgets on the home screen — and never block.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val confirmed = isActivityOf(pkg, event.className?.toString())
            evaluate(pkg, confirmed, attempt = 0)
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
        if (gate.foreground != before) interceptedFeatureKey = null // new app in front; allow feature re-detection
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
            if (decision is AccessDecision.Allow) return@launch
            // The lookup took a moment: make sure the user is still in that app, not back Home.
            if (activeAppPackage() != pkg) { gate.forget(pkg); return@launch }
            if (!passLoopGuard(pkg, confirmed)) return@launch
            when (decision) {
                is AccessDecision.Allow -> Unit
                is AccessDecision.Pause -> launchIntercept(InterceptActivity.KIND_PAUSE, decision.item.id, null, null)
                is AccessDecision.HardBlock -> launchIntercept(InterceptActivity.KIND_BLOCK, decision.item.id, null, null)
                is AccessDecision.LimitReached -> launchIntercept(InterceptActivity.KIND_LIMIT, decision.item.id, decision.reason, null)
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
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SCAN_THROTTLE_MS = 400L
        const val CACHE_TTL_MS = 2000L
    }
}
