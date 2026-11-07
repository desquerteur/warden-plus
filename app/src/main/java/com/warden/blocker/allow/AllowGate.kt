package com.warden.blocker.allow

import com.warden.blocker.block.AccessDecision
import com.warden.blocker.block.ScheduleEvaluator
import com.warden.blocker.data.BlockRepository
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.SettingsStore
import com.warden.blocker.hosts.DomainMatcher
import com.warden.blocker.time.TrustedClock
import kotlinx.coroutines.flow.first

/**
 * Feeds [AllowPolicy] the live state. Called only while blocking is enforced:
 * - [appDecision] from [com.warden.blocker.block.AccessController.decideForPackage],
 * - [webMatcher] from [com.warden.blocker.block.BlockEngine.blocklist] (the VPN).
 */
class AllowGate(
    private val store: AllowStore,
    private val settings: SettingsStore,
    private val repo: BlockRepository,
    private val apps: InstalledAppsInfo,
    private val appLabel: (String) -> String,
) {
    /** Whether the VPN's current list was built with website "Allow only" (null: not yet). */
    @Volatile private var webBuiltAllowOnly: Boolean? = null

    /**
     * A hard block when "Allow only" or a blocked category stops [packageName]; null when the
     * normal blocklist rules decide ([item] is its blocklist entry, if any).
     */
    suspend fun appDecision(packageName: String, item: BlockedItem?): AccessDecision? {
        val config = store.current()
        if (!config.affectsApps) return null
        val allowOnly = allowOnlyNow(config.appMode, config.scope)
        return when (val verdict = verdict(packageName, item, config, allowOnly)) {
            AppVerdict.FollowBlocklist -> null
            AppVerdict.NotAllowed -> AccessDecision.HardBlock(blockedItem(packageName, AllowBlockNotice.NOT_ALLOWED_ID, item))
            is AppVerdict.Category -> {
                AllowBlockNotice.category = verdict.category
                AccessDecision.HardBlock(blockedItem(packageName, AllowBlockNotice.CATEGORY_ID, item))
            }
        }
    }

    /**
     * The packages among [packageNames] that "Allow only" or a blocked category stops right now —
     * [appDecision] for each, but the state is read once and the block screen's notice is left
     * alone (the device-owner sync checks every app, not the one being opened). [items] are the
     * blocklist entries in effect, by package. Call only while blocking is enforced.
     */
    suspend fun blockedAmong(packageNames: Collection<String>, items: Map<String, BlockedItem>): Set<String> {
        val config = store.current()
        if (!config.affectsApps) return emptySet()
        val allowOnly = allowOnlyNow(config.appMode, config.scope)
        return packageNames.filterTo(HashSet()) { verdict(it, items[it], config, allowOnly) != AppVerdict.FollowBlocklist }
    }

    private fun verdict(packageName: String, item: BlockedItem?, config: AllowConfig, allowOnly: Boolean): AppVerdict =
        AllowPolicy.decideApp(packageName, config, apps.facts(packageName, config), allowOnly, item?.enabled == true)

    /** The VPN's filter for website "Allow only" right now, or null for the normal blocklist. */
    suspend fun webMatcher(): DomainMatcher? {
        val config = store.current()
        val on = allowOnlyNow(config.webMode, config.scope)
        webBuiltAllowOnly = on
        return if (on) AllowOnlyDomainMatcher(config.allowedDomains) else null
    }

    /** True when website "Allow only" switched on or off (scope) since the VPN last built its list. */
    suspend fun webModeStale(): Boolean {
        val built = webBuiltAllowOnly ?: return false
        val config = store.current()
        return allowOnlyNow(config.webMode, config.scope) != built
    }

    private suspend fun allowOnlyNow(mode: ListMode, scope: AllowScope): Boolean {
        if (mode != ListMode.ALLOW_ONLY) return false
        val focusActive = scope != AllowScope.WHENEVER_BLOCKING &&
            settings.focusEndsAt.first() > TrustedClock.now()
        val scheduleActive = scope == AllowScope.SCHEDULES_AND_FOCUS && !focusActive &&
            ScheduleEvaluator.anyActive(repo.enabledSchedules(), TrustedClock.calendar())
        return AllowPolicy.allowOnlyNow(mode, scope, focusActive, scheduleActive)
    }

    /** A stand-in blocklist entry for the block screen (see [AllowBlockNotice]). */
    private fun blockedItem(packageName: String, id: Long, item: BlockedItem?): BlockedItem {
        AllowBlockNotice.packageName = packageName
        return BlockedItem(
            id = id,
            type = BlockType.APP,
            value = packageName,
            label = item?.label ?: appLabel(packageName),
            interceptMode = InterceptMode.BLOCK,
        )
    }
}

/**
 * The block screen only receives an item id. Apps blocked by "Allow only" or a category have no
 * blocklist entry, so they get these reserved ids, and the screen reads the details here.
 */
object AllowBlockNotice {
    const val NOT_ALLOWED_ID = -2L
    const val CATEGORY_ID = -3L

    @Volatile var packageName: String? = null
    @Volatile var category: AppCategory? = null

    fun isAllowBlock(itemId: Long) = itemId == NOT_ALLOWED_ID || itemId == CATEGORY_ID

    fun title(itemId: Long): String = if (itemId == CATEGORY_ID) {
        "Blocked: ${category?.label ?: "this category"}"
    } else {
        "Only allowed apps right now"
    }

    fun body(itemId: Long, label: String?): String {
        val app = label ?: "This app"
        return if (itemId == CATEGORY_ID) {
            "$app counts as a ${category?.singular ?: "blocked app"}, and you asked Warden+ to block those. " +
                "If that's wrong, correct it under Blocklist → Block categories."
        } else {
            "$app isn't on your allow list. Warden+ is letting only the apps you chose through."
        }
    }
}
