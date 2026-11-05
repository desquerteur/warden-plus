package com.warden.blocker.block

/**
 * "Related domains": big sites load their pictures and videos from other domains (Instagram from
 * `cdninstagram.com`, YouTube from `ytimg.com` / `googlevideo.com` …). Blocking only the site's
 * own name leaves those working — pictures of a blocked site still showed up (issue #4). So a
 * blocked site also blocks its own media domains, unless switched off for that entry.
 *
 * Deliberately left out: anything shared with the rest of the web or with unrelated services —
 * general Google (`gstatic.com`, `googleusercontent.com`, `ggpht.com` as a whole), Cloudflare,
 * Akamai, Fastly, Amazon. Google image search thumbnails come from `gstatic.com` and can't be
 * blocked without breaking Google itself. Pure, so the table is unit-tested.
 */
object RelatedDomains {

    /** One media domain; [sharedWith] names other services that break too when it's blocked. */
    data class Related(val domain: String, val sharedWith: String? = null)

    private val META_CDN = Related("fbcdn.net", sharedWith = "all Meta apps (Facebook, Instagram, Threads)")

    /** Site → its own image / video / short-link domains. */
    val TABLE: Map<String, List<Related>> = mapOf(
        "instagram.com" to listOf(Related("cdninstagram.com"), META_CDN, Related("instagr.am")),
        "threads.net" to listOf(Related("cdninstagram.com", sharedWith = "Instagram"), META_CDN),
        "facebook.com" to listOf(META_CDN, Related("fbsbx.com"), Related("fb.com"), Related("fb.me"), Related("facebook.net")),
        "youtube.com" to listOf(
            Related("ytimg.com"), Related("googlevideo.com"), Related("youtu.be"),
            Related("youtube-nocookie.com"), Related("yt3.ggpht.com"), Related("yt3.googleusercontent.com"),
        ),
        "tiktok.com" to listOf(
            Related("tiktokcdn.com"), Related("tiktokcdn-us.com"), Related("tiktokv.com"), Related("tiktokv.us"),
            Related("ttwstatic.com"),
            Related("byteoversea.com", sharedWith = "other ByteDance apps (CapCut, Lemon8)"),
            Related("ibytedtos.com", sharedWith = "other ByteDance apps (CapCut, Lemon8)"),
        ),
        "reddit.com" to listOf(Related("redd.it"), Related("redditmedia.com"), Related("redditstatic.com"), Related("reddituploads.com")),
        "x.com" to listOf(Related("twimg.com"), Related("t.co"), Related("twitter.com")),
        "twitter.com" to listOf(Related("twimg.com"), Related("t.co"), Related("x.com")),
        "snapchat.com" to listOf(Related("sc-cdn.net"), Related("sc-static.net"), Related("snap.com")),
        "pinterest.com" to listOf(Related("pinimg.com"), Related("pin.it")),
        "twitch.tv" to listOf(Related("jtvnw.net"), Related("ttvnw.net"), Related("twitchcdn.net"), Related("twitchsvc.net")),
        "netflix.com" to listOf(Related("nflxvideo.net"), Related("nflximg.net"), Related("nflxext.com"), Related("nflxso.net")),
        "linkedin.com" to listOf(Related("licdn.com"), Related("lnkd.in")),
        "discord.com" to listOf(Related("discordapp.com"), Related("discordapp.net"), Related("discord.gg"), Related("discord.media")),
        "9gag.com" to listOf(Related("9cache.com")),
        "tumblr.com" to listOf(Related("tumblr.co")),
        "pornhub.com" to listOf(Related("phncdn.com")),
        "xvideos.com" to listOf(Related("xvideos-cdn.com")),
        "xhamster.com" to listOf(Related("xhcdn.com")),
    )

    /** The table key [domain] belongs to (`m.youtube.com` → `youtube.com`), or null. */
    fun siteOf(domain: String): String? {
        val d = domain.lowercase().removeSuffix(".").removePrefix("www.")
        return TABLE.keys.firstOrNull { d == it || d.endsWith(".$it") }
    }

    /** What blocking [domain] also blocks (empty for sites not in the table). */
    fun relatedTo(domain: String): List<Related> = siteOf(domain)?.let { TABLE[it] }.orEmpty()

    /**
     * [blocked] plus the related domains of every entry not in [switchedOff] (entries the user
     * said "only this site" for, by their domain). Duplicates are dropped.
     */
    fun expand(blocked: Collection<String>, switchedOff: Set<String>): List<String> {
        val out = LinkedHashSet(blocked)
        for (d in blocked) {
            if (normalize(d) in switchedOff) continue
            relatedTo(d).forEach { out += it.domain }
        }
        return out.toList()
    }

    /** The key [switchedOff] uses for an entry. */
    fun normalize(domain: String): String = domain.lowercase().removeSuffix(".").removePrefix("www.")
}
