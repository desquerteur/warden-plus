package com.warden.blocker.hosts

/**
 * Decides whether a DNS name is blocked: it is if the name itself or any parent domain is in
 * the manual list ([exact]) or in an imported hosts list ([lists]). Work is O(number of
 * labels) — one hash lookup per suffix — no matter how many domains are blocked.
 */
open class DomainMatcher(
    exactDomains: Collection<String>,
    private val lists: DomainHashSet? = null,
) {
    private val exact: Set<String> = exactDomains.mapTo(HashSet()) { it.lowercase().removeSuffix(".") }

    open val isEmpty: Boolean get() = exact.isEmpty() && (lists == null || lists.size == 0)

    open fun isBlocked(host: String): Boolean {
        if (isEmpty) return false
        val h = host.lowercase().removeSuffix(".")
        var start = 0
        while (start < h.length) {
            if (lists != null && lists.contains(h, start)) return true
            if (exact.isNotEmpty() && h.substring(start) in exact) return true
            val dot = h.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return false
    }

    companion object {
        val EMPTY = DomainMatcher(emptyList())
    }
}
