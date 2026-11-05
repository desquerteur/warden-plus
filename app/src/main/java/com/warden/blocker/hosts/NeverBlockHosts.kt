package com.warden.blocker.hosts

/**
 * Hosts an imported list may never block: Warden's own update check and download (GitHub).
 * Big bypass lists (hagezi "DoH/VPN/Proxy bypass") block many `*.github.io` proxies and could
 * one day list a parent like `githubusercontent.com` — that would silently break updates.
 *
 * Only imported lists are filtered: a domain the user blocks by hand still blocks. The website
 * filter forwards DNS to the network's resolver by IP address, so no name lookup of its own can
 * be broken by a list.
 */
object NeverBlockHosts {
    val ALL: List<String> = listOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com",
    )

    /**
     * Whether a list entry [domain] would block one of [ALL] — the entry itself or a parent of it
     * (a list entry blocks its subdomains too). `evil.github.com` is fine, `githubusercontent.com` isn't.
     */
    fun wouldBlock(domain: String): Boolean = ALL.any { it == domain || it.endsWith(".$domain") }
}
