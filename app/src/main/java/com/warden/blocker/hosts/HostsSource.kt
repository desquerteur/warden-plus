package com.warden.blocker.hosts

/**
 * One imported blocklist. The domains themselves live in a plain text file
 * (`filesDir/hostlists/<id>.txt`, one per line); this is just the metadata.
 *
 * @param url https source for downloadable lists, null for files picked from the phone.
 */
data class HostsSource(
    val id: String,
    val name: String,
    val url: String?,
    val enabled: Boolean = true,
    val domainCount: Int = 0,
    val updatedAt: Long = 0L,
    val lastError: String? = null,
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

    /** "Unified" is the base list; the others are add-ons (the `-only` variants), so
     *  combining them never downloads the base list twice. */
    val ALL = listOf(
        HostsTemplate("StevenBlack Unified", "Ads & malware (~80k domains)", BASE + "hosts"),
        HostsTemplate("Fake news", "StevenBlack add-on", BASE + "alternates/fakenews-only/hosts"),
        HostsTemplate("Gambling", "StevenBlack add-on", BASE + "alternates/gambling-only/hosts"),
        HostsTemplate("Adult content", "StevenBlack add-on", BASE + "alternates/porn-only/hosts"),
        HostsTemplate("Social media", "StevenBlack add-on", BASE + "alternates/social-only/hosts"),
    )
}
