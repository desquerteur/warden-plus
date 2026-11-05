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
    /** Website "Allow only" filter while it's in force (see AllowGate), else null. */
    private val allowOnlyWeb: suspend () -> DomainMatcher? = { null },
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

    /** See [BlockStateClock.filterShouldRun]. */
    suspend fun filterShouldRun(now: Long = System.currentTimeMillis()): Boolean =
        BlockStateClock.filterShouldRun(settings.masterEnabled.first(), settings.focusEndsAt.first(), now)

    suspend fun blockedDomains(): List<String> {
        if (!isBlockingActiveNow()) return emptyList()
        return manualAndDohDomains()
    }

    /**
     * Everything the VPN should sinkhole right now: manual domains, DoH hosts and lists.
     * Pass [active] when the caller has just evaluated [isBlockingActiveNow] itself.
     */
    suspend fun blocklist(active: Boolean? = null): DomainMatcher {
        if (!(active ?: isBlockingActiveNow())) {
            builtTimed = null
            return DomainMatcher.EMPTY
        }
        allowOnlyWeb()?.let {
            builtTimed = null // "Allow only" ignores the blocklist, so its time spans don't matter
            return it
        }
        val now = System.currentTimeMillis()
        val lists = listDomains()
        builtTimed = BuiltTimed(repo.timedItemsInEffect(now), lists)
        return DomainMatcher(manualAndDohDomains(now), lists)
    }

    /** What time-limited content the last active [blocklist] contained (null = built inactive). */
    private class BuiltTimed(val itemIds: List<Long>, val lists: DomainHashSet?)

    @Volatile private var builtTimed: BuiltTimed? = null

    /**
     * True when an entry or hosts list with a "from … / until …" span started or stopped
     * blocking since the last [blocklist] — the VPN's clock then rebuilds, like it does when a
     * schedule starts or ends. A new list set object means the active lists changed.
     */
    suspend fun timedContentChanged(now: Long = System.currentTimeMillis()): Boolean {
        val built = builtTimed ?: return false
        return built.itemIds != repo.timedItemsInEffect(now) || built.lists !== listDomains()
    }

    private suspend fun manualAndDohDomains(now: Long = System.currentTimeMillis()): List<String> {
        // Blocked sites bring their image / video domains along (issue #4), unless switched off per entry.
        val domains = RelatedDomains.expand(repo.enabledDomains(now), settings.relatedOff.first()).toMutableList()
        if (settings.blockDoh.first()) domains += DohHosts.ALL
        return domains
    }
}
