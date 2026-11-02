package com.warden.blocker.data

/** Turns what people type into the "Website" field into the bare domain the VPN matches. */
object DomainInput {

    /**
     * "https://www.Instagram.com/reels?x=1" -> "instagram.com". Returns null when nothing
     * usable is left (blank input, only a scheme, spaces inside the name).
     */
    fun normalize(raw: String): String? {
        val clean = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/").substringBefore("?").substringBefore("#")
            .trimEnd('.')
        if (clean.isEmpty() || clean.any { it.isWhitespace() }) return null
        return clean
    }
}
