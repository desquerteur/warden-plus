package com.warden.blocker.block

import com.warden.blocker.data.BlockRepository
import com.warden.blocker.data.SettingsStore
import com.warden.blocker.hosts.DomainHashSet
import com.warden.blocker.hosts.DomainMatcher
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Answers "is blocking enforced right now?" from the master switch + schedules. The VPN
 * asks it for [blockedDomains]; per-app decisions go through [AccessController].
 */
class BlockEngine(
    private val repo: BlockRepository,
    private val settings: SettingsStore,
    /** Domains from enabled imported hosts lists (see HostsListRepository), null if none. */
    private val listDomains: suspend () -> DomainHashSet? = { null },
) {
    /**
     * True when blocking is enforced right now. A focus session forces it on for its
     * duration; otherwise it's the master switch AND (always-on OR an active schedule).
     */
    suspend fun isBlockingActiveNow(now: Calendar = Calendar.getInstance()): Boolean {
        if (settings.focusEndsAt.first() > now.timeInMillis) return true
        if (!settings.masterEnabled.first()) return false
        if (settings.alwaysOn.first()) return true
        return ScheduleEvaluator.anyActive(repo.enabledSchedules(), now)
    }

    suspend fun blockedDomains(): List<String> {
        if (!isBlockingActiveNow()) return emptyList()
        return manualAndDohDomains()
    }

    /**
     * Everything the VPN should sinkhole right now: manual domains, DoH hosts and lists.
     * Pass [active] when the caller has just evaluated [isBlockingActiveNow] itself.
     */
    suspend fun blocklist(active: Boolean? = null): DomainMatcher {
        if (!(active ?: isBlockingActiveNow())) return DomainMatcher.EMPTY
        return DomainMatcher(manualAndDohDomains(), listDomains())
    }

    private suspend fun manualAndDohDomains(): List<String> {
        val domains = repo.enabledDomains().toMutableList()
        if (settings.blockDoh.first()) domains += DohHosts.ALL
        return domains
    }
}
