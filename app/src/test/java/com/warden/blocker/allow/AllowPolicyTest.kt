package com.warden.blocker.allow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowPolicyTest {

    private val app = AppFacts(detectedCategory = null, launchable = true, alwaysAllowed = false)
    private val game = app.copy(detectedCategory = AppCategory.GAME)
    private val allowOnly = AllowConfig(appMode = ListMode.ALLOW_ONLY, allowedApps = setOf("com.maps"))

    private fun decide(
        pkg: String,
        config: AllowConfig,
        facts: AppFacts = app,
        allowOnlyNow: Boolean = config.appMode == ListMode.ALLOW_ONLY,
        hasItem: Boolean = false,
    ) = AllowPolicy.decideApp(pkg, config, facts, allowOnlyNow, hasItem)

    // --- when "Allow only" is in force ---

    @Test fun scopeDecidesWhenAllowOnlyApplies() {
        val p = AllowPolicy
        assertFalse(p.allowOnlyNow(ListMode.BLOCK_LIST, AllowScope.WHENEVER_BLOCKING, true, true))
        assertTrue(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.WHENEVER_BLOCKING, false, false))
        assertTrue(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.SCHEDULES_AND_FOCUS, false, true))
        assertTrue(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.SCHEDULES_AND_FOCUS, true, false))
        assertFalse(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.SCHEDULES_AND_FOCUS, false, false))
        assertTrue(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.FOCUS_ONLY, true, false))
        assertFalse(p.allowOnlyNow(ListMode.ALLOW_ONLY, AllowScope.FOCUS_ONLY, false, true))
    }

    // --- app decisions ---

    @Test fun allowOnlyBlocksEverythingExceptAllowedApps() {
        assertEquals(AppVerdict.NotAllowed, decide("com.instagram", allowOnly))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.maps", allowOnly))
    }

    @Test fun allowOnlyNeverBlocksCriticalOrNonLaunchableApps() {
        assertEquals(AppVerdict.FollowBlocklist, decide("com.dialer", allowOnly, app.copy(alwaysAllowed = true)))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.installer", allowOnly, app.copy(launchable = false)))
    }

    @Test fun allowOnlyOutsideItsScopeFallsBackToTheBlocklist() {
        assertEquals(AppVerdict.FollowBlocklist, decide("com.instagram", allowOnly, allowOnlyNow = false))
    }

    @Test fun allowOnlyBlocksEvenAppsWithABlocklistEntry() {
        // A paused app on the blocklist that isn't allowed is still blocked outright.
        assertEquals(AppVerdict.NotAllowed, decide("com.instagram", allowOnly, hasItem = true))
    }

    @Test fun blockedCategoryBlocksDetectedApps() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        assertEquals(AppVerdict.Category(AppCategory.GAME), decide("com.game", config, game))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.notes", config, app))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.video", config, app.copy(detectedCategory = AppCategory.VIDEO)))
    }

    @Test fun categoryIsIgnoredWhenSwitchedOff() {
        assertEquals(AppVerdict.FollowBlocklist, decide("com.game", AllowConfig(), game))
    }

    @Test fun ownBlocklistEntryWinsOverCategory() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.game", config, game, hasItem = true))
    }

    @Test fun manualCorrectionsWin() {
        val config = AllowConfig(
            blockedCategories = setOf(AppCategory.GAME),
            categoryOverrides = mapOf("com.chess" to AppCategories.NONE, "com.undeclared" to "GAME"),
        )
        assertEquals(AppVerdict.FollowBlocklist, decide("com.chess", config, game))
        assertEquals(AppVerdict.Category(AppCategory.GAME), decide("com.undeclared", config, app))
    }

    @Test fun criticalAppsNeverBlockedByCategory() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        assertEquals(AppVerdict.FollowBlocklist, decide("com.clock", config, game.copy(alwaysAllowed = true)))
    }

    @Test fun suspendListHasOnlyNotAllowedLaunchableApps() {
        val apps = mapOf(
            "com.maps" to app,
            "com.instagram" to app,
            "com.dialer" to app.copy(alwaysAllowed = true),
            "com.service" to app.copy(launchable = false),
        )
        assertEquals(setOf("com.instagram"), AllowPolicy.blockedByAllowOnly(apps, allowOnly))
    }

    // --- new installs ---

    @Test fun newInstallOfBlockedCategoryIsReported() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        val installed = mapOf("old.game" to AppCategory.GAME, "new.game" to AppCategory.GAME, "new.notes" to null)
        assertEquals(
            mapOf("new.game" to AppCategory.GAME),
            AllowPolicy.newlyBlocked(known = setOf("old.game"), installed = installed, config = config),
        )
    }

    @Test fun firstLookReportsNothing() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        assertTrue(AllowPolicy.newlyBlocked(null, mapOf("a.game" to AppCategory.GAME), config).isEmpty())
    }

    @Test fun newInstallRespectsOverridesAndSwitches() {
        val config = AllowConfig(
            blockedCategories = setOf(AppCategory.SOCIAL),
            categoryOverrides = mapOf("new.chat" to AppCategories.NONE),
        )
        val installed = mapOf("new.chat" to AppCategory.SOCIAL, "new.game" to AppCategory.GAME)
        assertTrue(AllowPolicy.newlyBlocked(emptySet(), installed, config).isEmpty())
    }

    // --- loosening vs. tightening ---

    private fun loosens(old: AllowConfig, new: AllowConfig) = AllowPolicy.loosenings(old, new)

    @Test fun switchingBackToBlockListLoosens() {
        val base = AllowConfig(appMode = ListMode.ALLOW_ONLY, webMode = ListMode.ALLOW_ONLY)
        assertEquals(setOf(AllowLoosening.APP_MODE_OFF), loosens(base, base.copy(appMode = ListMode.BLOCK_LIST)))
        assertEquals(setOf(AllowLoosening.WEB_MODE_OFF), loosens(base, base.copy(webMode = ListMode.BLOCK_LIST)))
        assertTrue(loosens(AllowConfig(), base).isEmpty()) // switching on is tightening
    }

    @Test fun addingToAllowListLoosensRemovingTightens() {
        val base = AllowConfig(allowedApps = setOf("a"), allowedDomains = setOf("wikipedia.org"))
        assertEquals(setOf(AllowLoosening.ALLOW_APP), loosens(base, base.copy(allowedApps = setOf("a", "b"))))
        assertEquals(setOf(AllowLoosening.ALLOW_DOMAIN), loosens(base, base.copy(allowedDomains = setOf("wikipedia.org", "x.com"))))
        assertTrue(loosens(base, base.copy(allowedApps = emptySet(), allowedDomains = emptySet())).isEmpty())
    }

    @Test fun narrowingScopeLoosens() {
        val base = AllowConfig(scope = AllowScope.SCHEDULES_AND_FOCUS)
        assertEquals(setOf(AllowLoosening.SCOPE_NARROWED), loosens(base, base.copy(scope = AllowScope.FOCUS_ONLY)))
        assertTrue(loosens(base, base.copy(scope = AllowScope.WHENEVER_BLOCKING)).isEmpty())
    }

    @Test fun cameraAndSmsExtrasLoosenWhenSwitchedOn() {
        val base = AllowConfig(allowCamera = false, allowSms = false)
        assertEquals(setOf(AllowLoosening.ALWAYS_ALLOWED_EXTRA), loosens(base, base.copy(allowSms = true)))
        assertTrue(loosens(base.copy(allowCamera = true), base).isEmpty())
    }

    @Test fun categoryOffLoosensOnTightens() {
        val base = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        assertEquals(setOf(AllowLoosening.CATEGORY_OFF), loosens(base, base.copy(blockedCategories = emptySet())))
        assertTrue(loosens(base, base.copy(blockedCategories = setOf(AppCategory.GAME, AppCategory.VIDEO))).isEmpty())
    }

    @Test fun overrideThatCouldUnblockLoosens() {
        val base = AllowConfig(blockedCategories = setOf(AppCategory.GAME))
        fun with(vararg o: Pair<String, String>) = base.copy(categoryOverrides = mapOf(*o))
        val override = setOf(AllowLoosening.CATEGORY_OVERRIDE)
        // "Not a game" → loosening.
        assertEquals(override, loosens(base, with("chess" to AppCategories.NONE)))
        // Undoing "not a game" → tightening.
        assertTrue(loosens(with("chess" to AppCategories.NONE), base).isEmpty())
        // "Also block as game" → tightening; taking it back → loosening.
        assertTrue(loosens(base, with("undeclared" to "GAME")).isEmpty())
        assertEquals(override, loosens(with("undeclared" to "GAME"), base))
        // Moving an app into a category that isn't blocked → loosening.
        assertEquals(override, loosens(base, with("x" to "SOCIAL")))
        // Unchanged overrides → nothing.
        assertTrue(loosens(with("x" to "GAME"), with("x" to "GAME")).isEmpty())
    }
}
