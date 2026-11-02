package com.warden.blocker.allow

/** How a target type (apps or websites) is enforced while blocking is on. */
enum class ListMode {
    /** Only what is on the blocklist is blocked (the classic mode). */
    BLOCK_LIST,
    /** Everything is blocked except what is on the allow list ("block everything except …"). */
    ALLOW_ONLY,
}

/**
 * When "Allow only" is in force. Ordered from strongest to weakest: narrowing it is a
 * loosening (see [AllowPolicy.loosenings]).
 */
enum class AllowScope(val label: String) {
    /** Whenever blocking is on (main switch, schedules, focus sessions). */
    WHENEVER_BLOCKING("Whenever blocking is on"),
    /** Only while a schedule or a focus session runs — e.g. "exam time". */
    SCHEDULES_AND_FOCUS("Only during schedules and focus sessions"),
    /** Only during focus sessions. */
    FOCUS_ONLY("Only during focus sessions"),
}

/**
 * Everything the allow-only mode and the category blocks need. Kept outside Room (its own
 * DataStore, see [AllowStore]) so it never touches the database schema.
 *
 * [categoryOverrides] maps a package to the category the user put it in by hand, or to
 * [AppCategories.NONE] for "don't treat as any category" — it wins over what the app reports.
 */
data class AllowConfig(
    val appMode: ListMode = ListMode.BLOCK_LIST,
    val webMode: ListMode = ListMode.BLOCK_LIST,
    val scope: AllowScope = AllowScope.WHENEVER_BLOCKING,
    val allowedApps: Set<String> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    val blockedCategories: Set<AppCategory> = emptySet(),
    val categoryOverrides: Map<String, String> = emptyMap(),
    /** Keep the default camera app reachable in "Allow only". */
    val allowCamera: Boolean = true,
    /** Keep the default SMS app reachable in "Allow only" (login codes, emergencies). */
    val allowSms: Boolean = true,
) {
    /** Whether anything here can block an app at all (skips all lookups otherwise). */
    val affectsApps: Boolean get() = appMode == ListMode.ALLOW_ONLY || blockedCategories.isNotEmpty()
}

/** What an app is, as far as the allow-only and category rules care. */
data class AppFacts(
    /** What the app reports (before overrides), null when it's in none of [AppCategory]. */
    val detectedCategory: AppCategory?,
    /** Has a launcher icon. Background parts of the system (installer dialogs, share sheets)
     *  have none and are never blocked by "Allow only". */
    val launchable: Boolean,
    /** Phone, Settings, launcher, keyboard, clock, Warden+ … — see [AlwaysAllowed]. */
    val alwaysAllowed: Boolean,
)

/** The allow/category part of an app decision. */
sealed interface AppVerdict {
    /** Not this module's business: the normal blocklist rules decide. */
    data object FollowBlocklist : AppVerdict
    /** "Allow only" is on and this app isn't allowed. */
    data object NotAllowed : AppVerdict
    /** The app is in a blocked category. */
    data class Category(val category: AppCategory) : AppVerdict
}

/** Changes to [AllowConfig] that block less (they go through Block protection). */
enum class AllowLoosening {
    APP_MODE_OFF,
    WEB_MODE_OFF,
    SCOPE_NARROWED,
    ALLOW_APP,
    ALLOW_DOMAIN,
    ALWAYS_ALLOWED_EXTRA,
    CATEGORY_OFF,
    CATEGORY_OVERRIDE,
}
