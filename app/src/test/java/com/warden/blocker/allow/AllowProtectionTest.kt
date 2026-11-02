package com.warden.blocker.allow

import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.Loosening
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.block.ProtectionState
import com.warden.blocker.block.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Allow only" and category changes under Block protection: what loosens, and that it survives the Delay queue. */
class AllowProtectionTest {

    private val strict = AllowConfig(
        appMode = ListMode.ALLOW_ONLY,
        webMode = ListMode.ALLOW_ONLY,
        scope = AllowScope.WHENEVER_BLOCKING,
        allowedApps = setOf("com.whatsapp"),
        allowedDomains = setOf("wikipedia.org"),
        blockedCategories = setOf(AppCategory.GAME, AppCategory.SOCIAL),
        categoryOverrides = mapOf("com.chess" to AppCategory.GAME.name),
        allowCamera = false,
        allowSms = false,
    )

    private val loose = AllowConfig()

    private fun loosens(edit: AllowEdit, from: AllowConfig) = AllowPolicy.loosenings(from, edit.applyTo(from)).isNotEmpty()

    private val loosenings = listOf(
        AllowEdit.SetAppMode(ListMode.BLOCK_LIST),
        AllowEdit.SetWebMode(ListMode.BLOCK_LIST),
        AllowEdit.SetScope(AllowScope.FOCUS_ONLY),
        AllowEdit.SetAllowCamera(true),
        AllowEdit.SetAllowSms(true),
        AllowEdit.SetAppAllowed("com.instagram.android", "Instagram", true),
        AllowEdit.SetDomainAllowed("youtube.com", true),
        AllowEdit.SetCategoryBlocked(AppCategory.GAME, false),
        AllowEdit.SetOverride("com.chess", "Chess", AppCategories.NONE),
        AllowEdit.SetOverride("com.chess", "Chess", null),
    )

    private val tightenings = listOf(
        AllowEdit.SetAppMode(ListMode.ALLOW_ONLY),
        AllowEdit.SetWebMode(ListMode.ALLOW_ONLY),
        AllowEdit.SetScope(AllowScope.WHENEVER_BLOCKING),
        AllowEdit.SetAllowCamera(false),
        AllowEdit.SetAllowSms(false),
        AllowEdit.SetAppAllowed("com.whatsapp", "WhatsApp", false),
        AllowEdit.SetDomainAllowed("wikipedia.org", false),
        AllowEdit.SetCategoryBlocked(AppCategory.VIDEO, true),
        AllowEdit.SetOverride("com.puzzle", "Puzzle", AppCategory.GAME.name),
    )

    @Test fun everyLooseningIsSeenAsOne() {
        loosenings.forEach { assertTrue("$it", loosens(it, strict)) }
    }

    @Test fun tighteningPassesStraightThrough() {
        tightenings.forEach { assertFalse("$it", loosens(it, strict)) }
        // From the loose default, everything that adds blocking is free too.
        listOf(
            AllowEdit.SetAppMode(ListMode.ALLOW_ONLY),
            AllowEdit.SetCategoryBlocked(AppCategory.GAME, true),
            AllowEdit.SetAllowCamera(false),
        ).forEach { assertFalse("$it", loosens(it, loose)) }
    }

    @Test fun editsApplyOnTopOfLaterChanges() {
        // Queued "allow Instagram" must not undo an app allowed in the meantime.
        val queued = AllowEdit.SetAppAllowed("com.instagram.android", "Instagram", true)
        val meanwhile = strict.copy(allowedApps = strict.allowedApps + "org.signal")
        assertEquals(setOf("com.whatsapp", "org.signal", "com.instagram.android"), queued.applyTo(meanwhile).allowedApps)
        assertEquals(
            setOf(AppCategory.SOCIAL),
            AllowEdit.SetCategoryBlocked(AppCategory.GAME, false).applyTo(strict).blockedCategories,
        )
        assertEquals(emptyMap<String, String>(), AllowEdit.SetOverride("com.chess", "Chess", null).applyTo(strict).categoryOverrides)
    }

    @Test fun everyEditSurvivesTheDelayQueue() {
        val queue = (loosenings + tightenings).mapIndexed { i, e -> PendingChange(i.toLong(), 1000L + i, ProtectedChange.LoosenAllow(e)) }
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
    }

    @Test fun anUnknownEditIsDroppedNotCrashing() {
        val json = """[{"id":1,"due":5,"change":{"type":"LoosenAllow","edit":{"op":"FromTheFuture"}}}]"""
        assertEquals(emptyList<PendingChange>(), PendingChange.decode(json))
    }

    @Test fun describesForTheUser() {
        assertEquals("Apps will switch back to the block list", AllowEdit.SetAppMode(ListMode.BLOCK_LIST).describe())
        assertEquals("Instagram will be allowed", loosenings[5].describe())
        assertEquals("youtube.com will be allowed", loosenings[6].describe())
        assertEquals("Games will no longer be blocked", loosenings[7].describe())
        assertEquals("Chess will not count as any category", loosenings[8].describe())
        assertEquals("“Allow only” will apply only during focus sessions", loosenings[2].describe())
        assertEquals(Loosening.LOOSEN_ALLOW, ProtectedChange.LoosenAllow(loosenings[0]).kind)
    }

    // --- the same rule as every other weakening ---

    private val now = 1_700_000_000_000L
    private val change = ProtectedChange.LoosenAllow(AllowEdit.SetAppMode(ListMode.BLOCK_LIST))

    private fun verdict(mode: ProtectionMode, hasPin: Boolean = true, lockedUntil: Long = 0L, focusStrict: Boolean = false) =
        BlockProtection.check(
            change.kind,
            ProtectionState(ProtectionSettings(mode, 30, lockedUntil), hasPin, focusEndsAt = now + 600_000L, focusStrict = focusStrict),
            now,
        )

    @Test fun pinModeAsksForThePin() = assertEquals(Verdict.NeedsPin, verdict(ProtectionMode.PIN))

    @Test fun delayModeWaits() = assertEquals(Verdict.Delayed(now + 30 * 60_000L), verdict(ProtectionMode.DELAY))

    @Test fun lockedModeRefuses() {
        assertEquals(Verdict.Locked(now + 3_600_000L, byFocus = false), verdict(ProtectionMode.LOCKED, lockedUntil = now + 3_600_000L))
        // A lock that ran out counts as off.
        assertEquals(Verdict.Allow, verdict(ProtectionMode.LOCKED, lockedUntil = now - 1))
    }

    @Test fun strictFocusRefusesUntilItEnds() =
        assertEquals(Verdict.Locked(now + 600_000L, byFocus = true), verdict(ProtectionMode.OFF, focusStrict = true))

    @Test fun offIsImmediateEvenWithAPin() = assertEquals(Verdict.Allow, verdict(ProtectionMode.OFF))
}
