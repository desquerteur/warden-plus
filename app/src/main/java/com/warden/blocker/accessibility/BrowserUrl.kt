package com.warden.blocker.accessibility

import com.warden.blocker.hosts.HostsParser

/**
 * Browser mode: while Warden's website filter can't run (another VPN, e.g. Tailscale, holds
 * Android's only VPN slot), the accessibility service reads the address bar of common browsers
 * and blocks a blocked website there. Pure, so it is unit-tested.
 *
 * Honest limits: only these browsers, only what their address bar shows (apps with their own
 * web views and private DNS tricks aren't covered), and only while accessibility is on.
 */
object BrowserUrl {

    /** Browser package → view ids of its address bar. */
    val ADDRESS_BARS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text",
        ),
        "com.sec.android.app.sbrowser.beta" to listOf("com.sec.android.app.sbrowser.beta:id/location_bar_edit_text"),
        "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.firefox_beta" to listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.focus" to listOf("org.mozilla.focus:id/mozac_browser_toolbar_url_view"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
    )

    fun isBrowser(pkg: String): Boolean = pkg in ADDRESS_BARS

    /** Whether Browser mode should look at all: switched on, blocking enforced, and the filter not running. */
    fun active(enabled: Boolean, enforced: Boolean, filterRunning: Boolean): Boolean = enabled && enforced && !filterRunning

    /**
     * The website in an address bar's [text], or null for a search, an empty bar or anything that
     * isn't a domain. Browsers show "youtube.com/watch…", "https://m.youtube.com/…" or, with
     * "show full URL", the scheme too.
     */
    fun hostFrom(text: String?): String? {
        var t = text?.trim()?.lowercase() ?: return null
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null // a search, not an address
        t = t.substringAfter("://", t)
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringAfterLast('@') // user:pass@host
        t = t.substringBefore(':') // port
        return HostsParser.normalize(t)
    }
}
