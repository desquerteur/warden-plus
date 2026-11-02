package com.warden.blocker.block

import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom times under Block protection: which edits loosen, and that delayed ones keep their time fields. */
class TimesProtectionTest {

    private val h = 3_600_000L
    private val now = 1_700_000_000_000L

    private val item = BlockedItem(id = 7, type = BlockType.APP, value = "com.example", label = "Example")

    @Test fun shorterItemSpanLoosens() {
        val timed = item.copy(activeFrom = now, activeUntil = now + 10 * h)
        assertTrue(BlockProtection.itemEditLoosens(timed, timed.copy(activeUntil = now + 5 * h)))
        assertTrue(BlockProtection.itemEditLoosens(timed, timed.copy(activeFrom = now + h)))
        assertTrue(BlockProtection.itemEditLoosens(item, item.copy(activeUntil = now + h))) // "forever" → until
        assertFalse(BlockProtection.itemEditLoosens(timed, timed.copy(activeUntil = now + 20 * h)))
        assertFalse(BlockProtection.itemEditLoosens(timed, timed.copy(activeFrom = null, activeUntil = null)))
    }

    private val once = Schedule(id = 3, name = "Exam", daysMask = 0, startMinute = 0, endMinute = 0, onceStartAt = now, onceEndAt = now + 10 * h)
    private val weekly = Schedule(id = 4, name = "Work", daysMask = 31, startMinute = 540, endMinute = 1020, validFromDay = 100, validUntilDay = 200)

    @Test fun oneTimeBlocks() {
        assertTrue(BlockProtection.scheduleEditLoosens(once, once.copy(onceEndAt = now + 5 * h), now))
        assertTrue(BlockProtection.scheduleEditLoosens(once, once.copy(onceStartAt = now + h), now))
        assertTrue(BlockProtection.scheduleEditLoosens(once, once.copy(enabled = false), now))
        // Extending is free, even when the unused clock fields change with it.
        assertFalse(BlockProtection.scheduleEditLoosens(once, once.copy(onceEndAt = now + 20 * h, startMinute = 60, endMinute = 120), now))
        // An old, finished one-time block can be edited freely.
        assertFalse(BlockProtection.scheduleEditLoosens(once, once.copy(onceEndAt = now + h), now + 11 * h))
    }

    @Test fun weeklyDateRanges() {
        assertTrue(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(validUntilDay = 150), now))
        assertTrue(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(validFromDay = 120), now))
        assertFalse(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(validUntilDay = null), now))
        // Turning a weekly schedule into a one-time block can block less.
        assertTrue(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(onceStartAt = now, onceEndAt = now + h), now))
        // The 0.2.4 rules still apply.
        assertTrue(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(daysMask = 15), now))
        assertTrue(BlockProtection.scheduleEditLoosens(weekly, weekly.copy(endMinute = 960), now))
    }

    @Test fun delayedChangesKeepTheirTimes() {
        val changes = listOf(
            ProtectedChange.UpdateItem(item.copy(activeFrom = now, activeUntil = now + h), disables = false),
            ProtectedChange.UpdateSchedule(once.copy(onceEndAt = now + 2 * h), disables = false),
            ProtectedChange.UpdateSchedule(weekly, disables = false),
            ProtectedChange.ShortenBlockUntil(now + h),
            ProtectedChange.SetListSpan("abc", "StevenBlack", null, now + h),
            ProtectedChange.SetListSpan("abc", "StevenBlack", now, null),
        )
        val queue = changes.mapIndexed { i, c -> PendingChange(i.toLong(), now + i, c) }
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
        changes.drop(3).forEach { assertEquals(Loosening.SHORTEN_TIMES, it.kind) }
    }

    @Test fun changesQueuedBy024StillRead() {
        // No time fields at all: an entry/schedule without spans, as before.
        val json = """[{"id":1,"due":5,"change":{"type":"UpdateSchedule","disables":false,
            "schedule":{"id":4,"name":"Work","enabled":true,"days":31,"start":540,"end":1020}}}]"""
        val decoded = PendingChange.decode(json).single().change as ProtectedChange.UpdateSchedule
        assertEquals(Schedule(id = 4, name = "Work", daysMask = 31, startMinute = 540, endMinute = 1020), decoded.schedule)
    }

    @Test fun describesForTheUser() {
        assertEquals("The block will end sooner", ProtectedChange.ShortenBlockUntil(now).describe())
        assertEquals("List “Ads” will be blocked for less time", ProtectedChange.SetListSpan("x", "Ads", null, now).describe())
    }
}
