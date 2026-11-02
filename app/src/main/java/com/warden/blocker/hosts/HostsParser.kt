package com.warden.blocker.hosts

/**
 * Turns hosts files, plain domain lists and simple Adblock rules into clean domain names.
 *
 * Accepted line shapes:
 *  - `0.0.0.0 ads.example.com tracker.example.com  # comment` (any IPv4/IPv6 sink address)
 *  - `ads.example.com` (one domain per line)
 *  - `||ads.example.com^` (Adblock; `$important` / `$all` allowed, other modifiers skipped)
 *
 * Loopback names, bare IPs, single-label names, wildcards and anything that isn't a valid
 * DNS name are dropped. Pure Kotlin so it can be unit-tested without Android.
 */
object HostsParser {

    private val IGNORED = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
    )

    /** Parse a whole list; result is lowercase, de-duplicated, in first-seen order. */
    fun parse(lines: Sequence<String>): Set<String> {
        val out = LinkedHashSet<String>()
        lines.forEach { line -> parseLine(line) { out += it } }
        return out
    }

    /** Emit every valid domain found on [line] to [emit] (may be called several times). */
    fun parseLine(line: String, emit: (String) -> Unit) {
        var text = line.removePrefix("﻿").trim()
        if (text.isEmpty() || text[0] == '!' || text[0] == '[' || text.startsWith("@@")) return
        text = text.substringBefore('#').trim()
        if (text.isEmpty()) return

        if (text.startsWith("||")) {
            adblockDomain(text)?.let(emit)
            return
        }

        val tokens = tokenize(text)
        when {
            isIpAddress(tokens[0]) -> for (i in 1 until tokens.size) normalize(tokens[i])?.let(emit)
            tokens.size == 1 -> normalize(tokens[0])?.let(emit)
            // Anything else ("foo bar baz") isn't a format we understand — skip it.
        }
    }

    /** `||example.com^` → `example.com`; null for rules DNS can't express. */
    private fun adblockDomain(rule: String): String? {
        val body = rule.substring(2)
        val end = body.indexOfFirst { it == '^' || it == '/' || it == '$' || it == '|' }
        if (end <= 0) return null
        val rest = body.substring(end)
        val plain = rest == "^" || rest == "^|" || rest == "^\$important" || rest == "^\$all"
        if (!plain) return null
        return normalize(body.substring(0, end))
    }

    /** Lowercase, drop a trailing dot, and validate. */
    fun normalize(token: String): String? {
        val d = token.trim().lowercase().removeSuffix(".")
        if (d in IGNORED || isIpAddress(d) || !isValidDomain(d)) return null
        return d
    }

    /** RFC-1035-ish check: 2+ labels of 1–63 chars from [a-z0-9-_], no leading/trailing hyphen. */
    fun isValidDomain(d: String): Boolean {
        if (d.length !in 3..253) return false
        var labelStart = 0
        var labels = 0
        var lastLabelNumeric = true
        for (i in 0..d.length) {
            if (i == d.length || d[i] == '.') {
                val len = i - labelStart
                if (len !in 1..63) return false
                if (d[labelStart] == '-' || d[i - 1] == '-') return false
                lastLabelNumeric = (labelStart until i).all { d[it] in '0'..'9' }
                labels++
                labelStart = i + 1
            } else {
                val c = d[i]
                val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
                if (!ok) return false
            }
        }
        // A real TLD is never all digits; this also rejects dotted IPv4-like strings.
        return labels >= 2 && !lastLabelNumeric
    }

    /** True for dotted IPv4 (`127.0.0.1`) or anything containing ':' (IPv6, e.g. `::1`). */
    fun isIpAddress(s: String): Boolean {
        if (':' in s) return true
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } }
    }

    /** Split on runs of whitespace without a Regex — this runs ~100k times per import. */
    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>(2)
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val start = i
            while (i < s.length && !s[i].isWhitespace()) i++
            if (i > start) out += s.substring(start, i)
        }
        return out
    }
}
