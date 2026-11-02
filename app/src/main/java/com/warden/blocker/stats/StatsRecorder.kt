package com.warden.blocker.stats

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

private val Context.statsStore by preferencesDataStore(name = "warden_stats")

/**
 * Counts blocks per day for the stats dashboard (kept [BlockLog.KEEP_DAYS] days). Lives in its
 * own DataStore so it never touches the Room schema. Both record calls are cheap and safe to
 * call from any thread: writes happen on [Dispatchers.IO], and DNS blocks — which can arrive
 * in bursts — are batched in memory and flushed every [DOMAIN_FLUSH_MS].
 */
class StatsRecorder private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pendingDomains = HashMap<Long, Int>()
    private var flushScheduled = false

    /** The persisted counters (does not include DNS blocks still waiting to be flushed). */
    val log: Flow<BlockLog> = context.statsStore.data.map { BlockLog.decode(it[KEY_LOG]) }

    /** An app was intercepted (block, limit, pause or in-app feature screen shown). */
    fun recordAppBlocked(packageName: String) {
        val day = today()
        scope.launch { update { it.withApp(day, packageName) } }
    }

    /** A DNS lookup for a blocked domain was sinkholed. */
    fun recordDomainBlocked() {
        val day = today()
        synchronized(lock) {
            pendingDomains[day] = (pendingDomains[day] ?: 0) + 1
            if (flushScheduled) return
            flushScheduled = true
        }
        scope.launch {
            delay(DOMAIN_FLUSH_MS)
            flushDomains()
        }
    }

    /** Writes batched DNS blocks now (e.g. when the VPN stops). */
    suspend fun flushDomains() {
        val batch = synchronized(lock) {
            val copy = HashMap(pendingDomains)
            pendingDomains.clear()
            flushScheduled = false
            copy
        }
        if (batch.isEmpty()) return
        update { log -> batch.entries.fold(log) { acc, (day, n) -> acc.withDomains(day, n) } }
    }

    private suspend fun update(change: (BlockLog) -> BlockLog) {
        context.statsStore.edit { prefs ->
            prefs[KEY_LOG] = change(BlockLog.decode(prefs[KEY_LOG])).pruned(today()).encode()
        }
    }

    private fun today(): Long = LocalDate.now().toEpochDay()

    companion object {
        private val KEY_LOG = stringPreferencesKey("block_log")
        private const val DOMAIN_FLUSH_MS = 15_000L

        @Volatile private var instance: StatsRecorder? = null

        fun get(context: Context): StatsRecorder =
            instance ?: synchronized(this) {
                instance ?: StatsRecorder(context.applicationContext).also { instance = it }
            }
    }
}
