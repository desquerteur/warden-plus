package com.warden.blocker.ui

import com.warden.blocker.hosts.HostsSource

/**
 * The one-line count under "Blocking is ON/OFF": blocklist items plus the domains from enabled
 * hosts lists, e.g. "3 items · 77,758 domains". Short enough for one line on a 320dp screen.
 */
/**
 * What is actually enforced while blocking (or a focus session) is on: apps need the
 * accessibility service, websites need Warden's VPN. E.g. "Apps: on · Websites: off (another
 * VPN is active)" when Tailscale & co. hold the only VPN slot.
 */
internal fun protectionStatus(appsOn: Boolean, websitesOn: Boolean, otherVpn: Boolean): String {
    val websites = when {
        websitesOn -> "on"
        otherVpn -> "off (another VPN is active)"
        else -> "off"
    }
    return "Apps: ${if (appsOn) "on" else "off"} · Websites: $websites"
}

internal fun protectedSummary(activeItems: Int, hostsLists: List<HostsSource>): String {
    val listDomains = hostsLists.filter { it.enabled }.sumOf { it.domainCount.toLong() }
    val items = if (activeItems == 1) "1 item" else "$activeItems items"
    return when {
        listDomains == 0L -> "$items protected"
        activeItems == 0 -> "${"%,d".format(listDomains)} domains from lists"
        else -> "$items · ${"%,d".format(listDomains)} domains"
    }
}
