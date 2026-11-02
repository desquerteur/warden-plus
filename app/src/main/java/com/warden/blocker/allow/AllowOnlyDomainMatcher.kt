package com.warden.blocker.allow

import com.warden.blocker.hosts.DomainMatcher

/**
 * Website "Allow only": the VPN blocks every name except the [allowed] domains (and their
 * subdomains) plus [SystemDomains]. Single-label names ("localhost", printer names) are local
 * and never blocked.
 */
class AllowOnlyDomainMatcher(allowed: Collection<String>) : DomainMatcher(emptyList()) {

    private val allowedMatcher = DomainMatcher(allowed + SystemDomains.ALL)

    override val isEmpty: Boolean get() = false

    override fun isBlocked(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        if (h.isEmpty() || '.' !in h) return false
        return !allowedMatcher.isBlocked(h)
    }
}
