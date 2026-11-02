package com.warden.blocker.ui.tour

import com.warden.blocker.ui.tour.TourRoutes.BLOCKLIST
import com.warden.blocker.ui.tour.TourRoutes.HOME
import com.warden.blocker.ui.tour.TourRoutes.SCHEDULES
import com.warden.blocker.ui.tour.TourRoutes.SETTINGS
import com.warden.blocker.ui.tour.TourRoutes.STATS

/**
 * Ids for `Modifier.tourTarget(...)`. Some belong to screens that don't exist yet (Block
 * protection in 0.2.4, maximum protection in 0.3.0); their steps are skipped until a screen
 * uses the id. See docs/TOURS.md.
 */
object TourIds {
    const val HOME_SETUP = "home.setup"
    const val HOME_MASTER = "home.master"
    const val HOME_FOCUS = "home.focus"

    const val BLOCKLIST_ADD_SITE = "blocklist.add_site"
    const val BLOCKLIST_ADD_APPS = "blocklist.add_apps"
    const val BLOCKLIST_FEEDS = "blocklist.feeds"
    const val BLOCKLIST_HOSTS = "blocklist.hosts"

    const val SCHEDULES_NEW = "schedules.new"
    const val STATS_HEADER = "stats.header"

    const val SETTINGS_ALWAYS_ON = "settings.always_on"
    const val SETTINGS_STRICT = "settings.strict"
    const val SETTINGS_UNINSTALL = "settings.uninstall"
    const val SETTINGS_PIN = "settings.pin"
    const val SETTINGS_UPDATES = "settings.updates"
    const val SETTINGS_SETUP = "settings.setup"
    const val SETTINGS_GUIDES = "settings.guides"

    // 0.2.4 "Block protection" and 0.3.0 "Maximum protection" (not built yet).
    const val SETTINGS_BLOCK_PROTECTION = "settings.block_protection"
    const val SETTINGS_PROTECTION_CHECKLIST = "settings.protection_checklist"
    const val SETTINGS_GUARD_SYSTEM = "settings.guard_system_settings"
    const val SETTINGS_DEVICE_OWNER = "settings.device_owner"
}

object Tours {
    const val GETTING_STARTED = "getting_started"
    const val PROTECTION = "protection"

    private val T = TourIds

    val gettingStarted = Tour(
        GETTING_STARTED, "Getting started",
        listOf(
            step(null, HOME, "Welcome to Warden+", "A one-minute tour of the main parts. You can skip it and restart it later in Settings."),
            step(T.HOME_SETUP, HOME, "Finish setup first", "Warden+ needs Accessibility to block apps. Tap this card — the setup wizard shows each Android step."),
            step(T.HOME_MASTER, HOME, "The main switch", "Turns blocking on or off. The first time, Android asks to allow a VPN: tap OK. It stays on your phone."),
            step(T.HOME_FOCUS, HOME, "Focus session", "Forces blocking on for 25 or 50 minutes. Tick Strict and it can't be ended early."),
            step(T.BLOCKLIST_ADD_SITE, BLOCKLIST, "Block websites", "Type a site like instagram.com and tap Add."),
            step(T.BLOCKLIST_ADD_APPS, BLOCKLIST, "Block apps", "Pick apps to block — fully, or with a pause before they open."),
            step(T.BLOCKLIST_HOSTS, BLOCKLIST, "Big blocklists", "Import ready-made lists (hosts files) to block thousands of sites at once."),
            step(T.SCHEDULES_NEW, SCHEDULES, "Schedules", "Block only at set times, like work hours. Then turn off “Always on” in Settings."),
            step(T.STATS_HEADER, STATS, "Usage", "Your screen time, app opens and how often Warden+ stepped in."),
            step(T.SETTINGS_UPDATES, SETTINGS, "Settings & updates", "Strict mode, PIN and app updates live in Settings. “Restart guides” below shows these tours again."),
        ),
    )

    val protection = Tour(
        PROTECTION, "Protection",
        listOf(
            step(T.SETTINGS_BLOCK_PROTECTION, SETTINGS, "Block protection", "Choose how hard it is to loosen blocking: a PIN, a waiting time, or locked until a date."),
            step(T.SETTINGS_STRICT, SETTINGS, "Strict mode", "Locks the main switch while blocking is on. Needs a PIN."),
            step(T.SETTINGS_PIN, SETTINGS, "PIN lock", "Guards Settings and switching off. Tip: let a friend pick it."),
            step(T.SETTINGS_UNINSTALL, SETTINGS, "Uninstall protection", "Device admin stops Warden+ from being uninstalled while you need it.", SettingsGuide.DEVICE_ADMIN),
            step(T.SETTINGS_PROTECTION_CHECKLIST, SETTINGS, "Protection checklist", "Device admin, battery, Always-on VPN and Accessibility. All green = hard to switch off."),
            step(T.SETTINGS_GUARD_SYSTEM, SETTINGS, "Guard system settings", "Blocks the Android pages that could switch Warden+ off, like Force stop or VPN."),
            step(T.SETTINGS_DEVICE_OWNER, SETTINGS, "Maximum protection", "Expert mode, set up once with a computer. Android itself then enforces the blocks."),
            step(T.HOME_FOCUS, HOME, "Strict focus", "Tick Strict before you start a focus session — then it can't be ended early, not even with the PIN."),
        ),
    )

    /** Short tours offered on the first visit of a page (see [TourLogic.shouldOfferPageTips]). */
    private val pageTours: Map<String, Tour> = mapOf(
        BLOCKLIST to Tour(
            "page_blocklist", "Blocklist tips",
            listOf(
                step(T.BLOCKLIST_ADD_SITE, BLOCKLIST, "Block websites", "Type a site like instagram.com and tap Add."),
                step(T.BLOCKLIST_ADD_APPS, BLOCKLIST, "Block apps", "Pick apps to block — fully, or with a pause before they open."),
                step(T.BLOCKLIST_FEEDS, BLOCKLIST, "Feeds only", "Keep an app but hide Reels, Shorts and similar feeds."),
                step(T.BLOCKLIST_HOSTS, BLOCKLIST, "Big blocklists", "Import ready-made lists to block thousands of sites at once."),
            ),
        ),
        SCHEDULES to Tour(
            "page_schedules", "Schedule tips",
            listOf(step(T.SCHEDULES_NEW, SCHEDULES, "Schedules", "Block only at set times. Then turn off “Always on” in Settings.")),
        ),
        STATS to Tour(
            "page_stats", "Usage tips",
            listOf(step(T.STATS_HEADER, STATS, "Pick a period", "Switch between today, the week and the month.")),
        ),
        SETTINGS to Tour(
            "page_settings", "Settings tips",
            listOf(
                step(T.SETTINGS_ALWAYS_ON, SETTINGS, "Always on", "Off = blocking only during your schedules."),
                step(T.SETTINGS_STRICT, SETTINGS, "Strict mode", "Locks the main switch while blocking is on. Needs a PIN."),
                step(T.SETTINGS_GUIDES, SETTINGS, "Guides", "Restart any guide here."),
            ),
        ),
    )

    val all: List<Tour> = listOf(gettingStarted, protection) + pageTours.values

    fun byId(id: String): Tour? = all.firstOrNull { it.id == id }

    fun forPage(route: String?): Tour? = pageTours[route]
}
