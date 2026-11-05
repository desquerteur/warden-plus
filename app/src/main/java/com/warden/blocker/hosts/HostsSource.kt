package com.warden.blocker.hosts

/**
 * One imported blocklist. The domains themselves live in a plain text file
 * (`filesDir/hostlists/<id>.txt`, one per line); this is just the metadata.
 *
 * @param url https source for downloadable lists, null for files picked from the phone.
 * @param activeFrom/activeUntil epoch millis the list blocks from / until (null = no limit).
 */
data class HostsSource(
    val id: String,
    val name: String,
    val url: String?,
    val enabled: Boolean = true,
    val domainCount: Int = 0,
    val updatedAt: Long = 0L,
    val lastError: String? = null,
    val activeFrom: Long? = null,
    val activeUntil: Long? = null,
)

/** Progress of a running import, for the UI. [totalBytes] is -1 when unknown. */
data class ImportProgress(
    val sourceName: String,
    val bytesRead: Long = 0,
    val totalBytes: Long = -1,
    val domains: Int = 0,
)

/** Ready-made lists offered with one tap. */
data class HostsTemplate(val name: String, val description: String, val url: String)

object HostsTemplates {
    private const val BASE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/"

    /**
     * hagezi's "Encrypted DNS/VPN/Tor/Proxy Bypass" list (GPL-3.0, github.com/hagezi/dns-blocklists),
     * plain domains. The old `hosts/` folder is gone; this is the "Wildcard Domains" variant.
     * GitHub's update hosts are never taken from it ([NeverBlockHosts]).
     */
    const val VPN_BYPASS_URL =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-vpn-proxy-bypass-onlydomains.txt"
    const val VPN_BYPASS_NAME = "VPN, proxy & DoH bypass"

    /** "Unified" is the base list; the others are add-ons (the `-only` variants), so
     *  combining them never downloads the base list twice. */
    val ALL = listOf(
        HostsTemplate("StevenBlack Unified", "Ads & malware (~80k domains)", BASE + "hosts"),
        HostsTemplate("Fake news", "StevenBlack add-on", BASE + "alternates/fakenews-only/hosts"),
        HostsTemplate("Gambling", "StevenBlack add-on", BASE + "alternates/gambling-only/hosts"),
        HostsTemplate("Adult content", "StevenBlack add-on", BASE + "alternates/porn-only/hosts"),
        HostsTemplate("Social media", "StevenBlack add-on", BASE + "alternates/social-only/hosts"),
        HostsTemplate(
            VPN_BYPASS_NAME,
            "VPN, proxy & encrypted-DNS sites (~17k) · hagezi, GPL-3.0",
            VPN_BYPASS_URL,
        ),
    )
}
