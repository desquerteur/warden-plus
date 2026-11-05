package com.warden.blocker.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.warden.blocker.allow.AllowBlockNotice
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.VpnApps
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.hosts.DomainMatcher
import com.warden.blocker.system.BlockLoopAlert
import com.warden.blocker.system.VpnTakeover
import com.warden.blocker.ui.intercept.InterceptActivity
import com.warden.blocker.vpn.WardenVpnService
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The accessibility side of VPN bypass protection, kept out of [AppBlockAccessibilityService]
 * (one call from its event handler):
 * - remembers which VPN app was in front, so "Tailscale took over" can name it ([VpnTakeover]);
 * - cancels Android's VPN consent dialog for a blocked VPN app;
 * - Browser mode ([BrowserUrl]): while Warden's filter isn't running, a blocked website shown in a
 *   browser's address bar gets the block screen.
 *
 * Privacy: reads only the address bar of known browsers (only while Browser mode is in force) and
 * the text of Android's VPN consent dialog.
 * Nothing is stored or logged.
 */
class BrowserGuard(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastScan = 0L
    /** The host the block screen was shown for; shown again only after another app came to the front. */
    @Volatile private var blockedHost: String? = null

    @Volatile private var cachedOn = false
    @Volatile private var cachedMatcher: DomainMatcher = DomainMatcher.EMPTY
    @Volatile private var cacheStamp = 0L

    fun onEvent(event: AccessibilityEvent, pkg: String) {
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            runCatching { VpnTakeover.noteForeground(service, pkg) }
            if (!BrowserUrl.isBrowser(pkg)) blockedHost = null // left the browser: block again on return
            if (pkg == VpnApps.CONSENT_DIALOG_PACKAGE) scope.launch { runCatching { checkVpnConsent() } }
        }
        if (!BrowserUrl.isBrowser(pkg)) return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < SCAN_THROTTLE_MS) return
        lastScan = now
        scope.launch { runCatching { check(pkg, now) } }
    }

    private suspend fun check(pkg: String, now: Long) {
        // The filter itself runs: nothing to do (cheap check before any DataStore read).
        if (WardenVpnService.running) return
        if (now - cacheStamp > CACHE_TTL_MS) {
            val c = service.wardenContainer
            val enforced = c.blockEngine.isBlockingActiveNow()
            cachedOn = BrowserUrl.active(c.settings.browserMode.first(), enforced, WardenVpnService.running)
            cachedMatcher = if (cachedOn) c.blockEngine.blocklist(true) else DomainMatcher.EMPTY
            cacheStamp = now
        }
        if (!cachedOn) return
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return // only the browser's own window
        val text = BrowserUrl.ADDRESS_BARS[pkg].orEmpty().firstNotNullOfOrNull { id ->
            runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()?.text?.toString()
        }
        val host = BrowserUrl.hostFrom(text)
        if (host == null || !cachedMatcher.isBlocked(host)) { blockedHost = null; return }
        if (host == blockedHost) return
        blockedHost = host
        when (ForegroundGate.shared.allowIntercept("web:$host", true, SystemClock.uptimeMillis())) {
            ForegroundGate.Intercept.SHOW -> Unit
            ForegroundGate.Intercept.SUPPRESS -> return
            ForegroundGate.Intercept.SUPPRESS_AND_NOTIFY -> { BlockLoopAlert.notifyPaused(service, pkg); return }
        }
        service.startActivity(
            Intent(service, InterceptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(InterceptActivity.EXTRA_KIND, InterceptActivity.KIND_WEBSITE)
                .putExtra(InterceptActivity.EXTRA_ITEM_ID, -1L)
                .putExtra(InterceptActivity.EXTRA_FEATURE_LABEL, host),
        )
    }

    /**
     * Android's VPN "Connection request" dialog for a blocked VPN app (opened by the app, or from
     * VPN settings): cancel it and show the block screen. VPN apps without a screen of their own
     * go straight to this dialog, so the normal app block never sees them.
     */
    private suspend fun checkVpnConsent() {
        val c = service.wardenContainer
        val config = c.allowStore.current()
        if (AppCategory.VPN !in config.blockedCategories || !c.blockEngine.isBlockingActiveNow()) return
        val vpnApps = c.installedApps.vpnPackages()
        val blocked = (vpnApps - VpnApps.trusted(vpnApps, config)).associateWith { AppsHelper.labelFor(service, it) }
        if (blocked.isEmpty()) return
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != VpnApps.CONSENT_DIALOG_PACKAGE) return
        val pkg = VpnApps.consentRequester(visibleText(root), blocked) ?: return
        // "Cancel" (Back) until the dialog is really gone; only then the block screen, else the
        // dialog stays behind it and "OK" is one step away.
        repeat(4) {
            if (service.rootInActiveWindow?.packageName?.toString() != VpnApps.CONSENT_DIALOG_PACKAGE) return@repeat
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(350)
        }
        AllowBlockNotice.packageName = pkg
        AllowBlockNotice.category = AppCategory.VPN
        service.startActivity(
            Intent(service, InterceptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(InterceptActivity.EXTRA_KIND, InterceptActivity.KIND_BLOCK)
                .putExtra(InterceptActivity.EXTRA_ITEM_ID, AllowBlockNotice.CATEGORY_ID),
        )
    }

    /** The texts of a small dialog, joined (bounded: it's only ever a handful of nodes). */
    private fun visibleText(root: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 60) {
            val node = stack.removeLast()
            node.text?.let { out.append(it).append('\n') }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return out.toString()
    }

    fun stop() = scope.cancel()

    private companion object {
        const val SCAN_THROTTLE_MS = 600L
        const val CACHE_TTL_MS = 5_000L
    }
}
