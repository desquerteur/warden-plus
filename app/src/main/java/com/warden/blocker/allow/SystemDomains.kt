package com.warden.blocker.allow

/**
 * Domains that stay reachable in website "Allow only" mode, so the phone itself keeps
 * working: connectivity checks, push messages, clock sync, account sign-in and Warden+'s own
 * updates. Subdomains match too. Deliberately narrow — no general Google or Samsung domains,
 * which would let search, YouTube etc. back in.
 */
object SystemDomains {

    /** Android's "is there internet?" probes (otherwise Wi-Fi may show "no internet"). */
    val CONNECTIVITY = listOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients1.google.com",
        "clients3.google.com",
    )

    /** Google Play services and Firebase Cloud Messaging — push notifications of all apps. */
    val PLAY_SERVICES = listOf(
        "mtalk.google.com",
        "alt1-mtalk.google.com", "alt2-mtalk.google.com", "alt3-mtalk.google.com", "alt4-mtalk.google.com",
        "alt5-mtalk.google.com", "alt6-mtalk.google.com", "alt7-mtalk.google.com", "alt8-mtalk.google.com",
        "android.clients.google.com",
        "android.googleapis.com",
        "fcm.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "play.googleapis.com",
        "www.googleapis.com",
    )

    /** Network time (the clock must stay right — schedules depend on it). */
    val TIME = listOf(
        "time.android.com",
        "time.google.com",
        "pool.ntp.org",
    )

    /** Signing in to the Google and Samsung accounts. */
    val ACCOUNTS = listOf(
        "accounts.google.com",
        "oauth2.googleapis.com",
        "account.samsung.com",
        "samsungosp.com",
    )

    /** Warden+ checks GitHub releases for updates and downloads them from there. */
    val WARDEN_UPDATES = listOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Reverse lookups (IP → name); harmless and some apps stall without an answer. */
    val INFRASTRUCTURE = listOf("arpa")

    val ALL: List<String> = CONNECTIVITY + PLAY_SERVICES + TIME + ACCOUNTS + WARDEN_UPDATES + INFRASTRUCTURE
}
