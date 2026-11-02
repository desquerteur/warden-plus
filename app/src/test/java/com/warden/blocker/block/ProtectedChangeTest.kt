package com.warden.blocker.block

import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule
import com.warden.blocker.system.ProtectionWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectedChangeTest {

    private val all = listOf(
        ProtectedChange.TurnOffBlocking,
        ProtectedChange.EndFocus,
        ProtectedChange.RemoveItem(7, "Instagram"),
        ProtectedChange.UpdateItem(
            BlockedItem(
                id = 3, type = BlockType.APP, value = "com.instagram.android", label = "Instagram", enabled = false,
                createdAt = 42, interceptMode = InterceptMode.PAUSE, pauseSeconds = 20, customPrompt = null,
                openLimitPerDay = 2, dailyLimitMinutes = 30, cooldownMinutes = 5,
            ),
            disables = true,
        ),
        ProtectedChange.UpdateItem(
            BlockedItem(id = 4, type = BlockType.WEBSITE, value = "x.com", label = "x.com", customPrompt = "Why?"),
            disables = false,
        ),
        ProtectedChange.RemoveSchedule(2, "Work"),
        ProtectedChange.UpdateSchedule(Schedule(id = 2, name = "Work", enabled = false, daysMask = 31, startMinute = 540, endMinute = 1020), disables = true),
        ProtectedChange.DisableHostsList("abc", "StevenBlack"),
        ProtectedChange.RemoveHostsList("abc", "StevenBlack"),
        ProtectedChange.DisableFeedBlock("instagram_reels", "Instagram Reels"),
        ProtectedChange.SettingOff(ProtectedChange.Setting.BLOCK_DOH),
        ProtectedChange.SetProtection(ProtectionSettings(ProtectionMode.DELAY, 15, 0L)),
        ProtectedChange.DisableUninstallProtection,
        ProtectedChange.ChangePin("hash", "salt"),
        ProtectedChange.RemovePin,
    )

    @Test fun everyChangeSurvivesTheQueue() {
        val queue = all.mapIndexed { i, c -> PendingChange(i.toLong(), 1000L + i, c) }
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
    }

    @Test fun kindsMatch() {
        assertEquals(Loosening.DISABLE_ITEM, (all[3] as ProtectedChange.UpdateItem).kind)
        assertEquals(Loosening.WEAKEN_ITEM, (all[4] as ProtectedChange.UpdateItem).kind)
        assertEquals(Loosening.DISABLE_SCHEDULE, all[6].kind)
    }

    @Test fun describesForTheUser() {
        assertEquals("Instagram will be unblocked", all[3].describe())
        assertEquals("Blocking will be turned off", ProtectedChange.TurnOffBlocking.describe())
    }

    @Test fun brokenOrUnknownEntriesAreDropped() {
        assertEquals(emptyList<PendingChange>(), PendingChange.decode(null))
        assertEquals(emptyList<PendingChange>(), PendingChange.decode("not json"))
        val mixed = """[{"id":1,"due":5,"change":{"type":"FromTheFuture"}},{"id":2,"due":6,"change":{"type":"EndFocus"}}]"""
        assertEquals(listOf(PendingChange(2, 6, ProtectedChange.EndFocus)), PendingChange.decode(mixed))
    }

    // --- ProtectionWatch: was protection switched off from outside? ---

    private val min = 60_000L
    private val now = 1_700_000_000_000L
    private val bootLongAgo = now - 24 * 60 * min

    @Test fun aRecentHeartbeatIsFine() {
        assertNull(ProtectionWatch.interruption(true, now - 2 * min, 0L, bootLongAgo, now))
    }

    @Test fun aLongGapIsReported() {
        assertEquals(20 * min, ProtectionWatch.interruption(true, now - 20 * min, 0L, bootLongAgo, now))
    }

    @Test fun stoppingOnPurposeIsNotReported() {
        assertNull(ProtectionWatch.interruption(true, now - 20 * min, now - 19 * min, bootLongAgo, now))
        assertNull(ProtectionWatch.interruption(false, now - 20 * min, 0L, bootLongAgo, now))
        assertNull(ProtectionWatch.interruption(true, 0L, 0L, bootLongAgo, now)) // never ran
    }

    @Test fun aRestartOnlyCountsTheTimeSinceBoot() {
        // Phone off for an hour, back up 1 min ago: normal, not a switch-off.
        assertNull(ProtectionWatch.interruption(true, now - 60 * min, 0L, now - min, now))
        // Up for 10 min without the filter: that is one.
        assertEquals(10 * min, ProtectionWatch.interruption(true, now - 60 * min, 0L, now - 10 * min, now))
    }
}
