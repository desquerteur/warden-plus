package com.warden.blocker.data

import com.warden.blocker.block.TimeWindows
import com.warden.blocker.time.TrustedClock
import kotlinx.coroutines.flow.Flow

/** Single access point for blocklist + schedule + grant persistence. */
class BlockRepository(private val dao: BlockDao) {

    val items: Flow<List<BlockedItem>> = dao.observeItems()
    val schedules: Flow<List<Schedule>> = dao.observeSchedules()

    /** Domains to block right now: enabled, and inside their "from … / until …" span if set. */
    suspend fun enabledDomains(now: Long = TrustedClock.now()): List<String> =
        dao.enabledItemsOfType(BlockType.WEBSITE)
            .filter { TimeWindows.inEffect(it, now) }
            .map { it.value.lowercase().removePrefix("www.") }

    suspend fun enabledApps(): List<BlockedItem> = dao.enabledItemsOfType(BlockType.APP)

    /**
     * The blocklist entry for [packageName] if it blocks at [now] — null outside its
     * "from … / until …" span, so blocking decisions let the app through. Use [findApp] to
     * look an entry up regardless of time.
     */
    suspend fun appItem(packageName: String, now: Long = TrustedClock.now()): BlockedItem? =
        dao.itemByValue(BlockType.APP, packageName)?.takeIf { TimeWindows.inEffect(it, now) }

    suspend fun findApp(packageName: String): BlockedItem? = dao.itemByValue(BlockType.APP, packageName)

    /**
     * Ids of the enabled entries with a time limit that block at [now]. Changes exactly when
     * such an entry starts or stops blocking, so the VPN knows to rebuild its list.
     */
    suspend fun timedItemsInEffect(now: Long): List<Long> =
        dao.enabledTimedItems().filter { TimeWindows.inEffect(it, now) }.map { it.id }

    suspend fun websiteItem(domain: String): BlockedItem? =
        dao.itemByValue(BlockType.WEBSITE, domain)

    suspend fun item(id: Long): BlockedItem? = dao.itemById(id)

    /** Returns the new row's id. */
    suspend fun addItem(item: BlockedItem): Long = dao.upsertItem(item)
    suspend fun updateItem(item: BlockedItem) = dao.updateItem(item)
    suspend fun removeItem(item: BlockedItem) = dao.deleteItem(item)

    suspend fun enabledSchedules(): List<Schedule> = dao.enabledSchedules()
    suspend fun addSchedule(schedule: Schedule) = dao.upsertSchedule(schedule)
    suspend fun removeSchedule(schedule: Schedule) = dao.deleteSchedule(schedule)

    // --- Grants ---
    suspend fun activeGrant(itemId: Long, now: Long): AccessGrant? = dao.activeGrant(itemId, now)

    suspend fun opensToday(itemId: Long): Int = dao.grantCountSince(itemId, startOfToday())

    suspend fun lastGrantAt(itemId: Long): Long? = dao.lastGrantAt(itemId)

    suspend fun grantAccess(item: BlockedItem, durationMinutes: Int): AccessGrant {
        val now = TrustedClock.now()
        val grant = AccessGrant(
            itemId = item.id,
            packageName = item.value,
            grantedAt = now,
            expiresAt = now + durationMinutes * 60_000L,
        )
        dao.insertGrant(grant)
        dao.pruneGrantsBefore(now - 30L * 24 * 60 * 60 * 1000) // keep ~30 days
        return grant
    }

    private fun startOfToday(): Long = TrustedClock.startOfToday()
}
