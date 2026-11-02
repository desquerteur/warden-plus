package com.warden.blocker.allow

/**
 * The allow-only and category rules — pure, no I/O, so they're exhaustively unit-tested.
 * [AllowGate] feeds them the live state; [com.warden.blocker.block.AccessController] and the
 * VPN act on the result.
 */
object AllowPolicy {

    /**
     * Whether [mode] is "Allow only" right now. Assumes blocking is enforced (the caller has
     * checked); [scope] narrows it to schedules and/or focus sessions.
     */
    fun allowOnlyNow(mode: ListMode, scope: AllowScope, focusActive: Boolean, scheduleActive: Boolean): Boolean {
        if (mode != ListMode.ALLOW_ONLY) return false
        return when (scope) {
            AllowScope.WHENEVER_BLOCKING -> true
            AllowScope.SCHEDULES_AND_FOCUS -> focusActive || scheduleActive
            AllowScope.FOCUS_ONLY -> focusActive
        }
    }

    /**
     * The allow/category verdict for [packageName] while blocking is enforced.
     *
     * - Always-allowed apps (phone, Settings, launcher, keyboard, clock …) and anything without
     *   a launcher icon are never blocked here — the blocklist alone decides for them.
     * - "Allow only" in force: allowed apps follow the blocklist (so their limits and pauses
     *   still work), everything else is blocked.
     * - Otherwise an app in a blocked category is blocked — unless it has its own enabled
     *   blocklist entry ([hasEnabledItem]), whose settings (e.g. a mindful pause) win.
     */
    fun decideApp(
        packageName: String,
        config: AllowConfig,
        facts: AppFacts,
        allowOnlyNow: Boolean,
        hasEnabledItem: Boolean,
    ): AppVerdict {
        if (facts.alwaysAllowed || !facts.launchable) return AppVerdict.FollowBlocklist
        if (allowOnlyNow) {
            return if (packageName in config.allowedApps) AppVerdict.FollowBlocklist else AppVerdict.NotAllowed
        }
        if (hasEnabledItem) return AppVerdict.FollowBlocklist
        val category = blockedCategoryOf(packageName, facts.detectedCategory, config)
            ?: return AppVerdict.FollowBlocklist
        return AppVerdict.Category(category)
    }

    /** The blocked category [packageName] falls into, if any (overrides applied). */
    fun blockedCategoryOf(packageName: String, detected: AppCategory?, config: AllowConfig): AppCategory? {
        if (config.blockedCategories.isEmpty()) return null
        return AppCategories.effective(packageName, detected, config.categoryOverrides)
            ?.takeIf { it in config.blockedCategories }
    }

    /**
     * Launchable apps "Allow only" blocks — for suspending them all at once (Device Owner,
     * `setPackagesSuspended`). [apps] maps package → facts.
     */
    fun blockedByAllowOnly(apps: Map<String, AppFacts>, config: AllowConfig): Set<String> =
        apps.filter { (pkg, facts) ->
            decideApp(pkg, config, facts, allowOnlyNow = true, hasEnabledItem = false) == AppVerdict.NotAllowed
        }.keys

    /**
     * Packages from [installed] that weren't [known] before and are blocked by a category —
     * the "New game blocked" notice. Null [known] means it's the first look: nothing is new.
     */
    fun newlyBlocked(
        known: Set<String>?,
        installed: Map<String, AppCategory?>,
        config: AllowConfig,
    ): Map<String, AppCategory> {
        if (known == null) return emptyMap()
        val result = LinkedHashMap<String, AppCategory>()
        for ((pkg, detected) in installed) {
            if (pkg in known) continue
            blockedCategoryOf(pkg, detected, config)?.let { result[pkg] = it }
        }
        return result
    }

    /**
     * What makes [updated] block less than [old]. Empty = only tightening (applied at once);
     * otherwise Block protection decides. Adding to an allow list is a loosening too.
     */
    fun loosenings(old: AllowConfig, updated: AllowConfig): Set<AllowLoosening> = buildSet {
        if (old.appMode == ListMode.ALLOW_ONLY && updated.appMode != ListMode.ALLOW_ONLY) add(AllowLoosening.APP_MODE_OFF)
        if (old.webMode == ListMode.ALLOW_ONLY && updated.webMode != ListMode.ALLOW_ONLY) add(AllowLoosening.WEB_MODE_OFF)
        if (updated.scope.ordinal > old.scope.ordinal) add(AllowLoosening.SCOPE_NARROWED)
        if (!old.allowedApps.containsAll(updated.allowedApps)) add(AllowLoosening.ALLOW_APP)
        if (!old.allowedDomains.containsAll(updated.allowedDomains)) add(AllowLoosening.ALLOW_DOMAIN)
        if ((!old.allowCamera && updated.allowCamera) || (!old.allowSms && updated.allowSms)) {
            add(AllowLoosening.ALWAYS_ALLOWED_EXTRA)
        }
        if (!updated.blockedCategories.containsAll(old.blockedCategories)) add(AllowLoosening.CATEGORY_OFF)
        val packages = old.categoryOverrides.keys + updated.categoryOverrides.keys
        if (packages.any { overrideLoosens(old.categoryOverrides[it], updated.categoryOverrides[it], old, updated) }) {
            add(AllowLoosening.CATEGORY_OVERRIDE)
        }
    }

    /**
     * Whether changing one app's override from [before] to [after] can unblock it. Without the
     * app's detected category at hand, "no override" counts as possibly blocked — so marking an
     * app "not a game" is a loosening, and putting it back into a blocked category is not.
     */
    private fun overrideLoosens(before: String?, after: String?, old: AllowConfig, updated: AllowConfig): Boolean {
        if (before == after) return false
        val wasBlocked = before?.let { AppCategories.parse(it) in old.blockedCategories }
        if (after == null) return wasBlocked == true
        val nowBlocked = AppCategories.parse(after)?.let { it in updated.blockedCategories } ?: false
        return (wasBlocked ?: true) && !nowBlocked
    }
}
