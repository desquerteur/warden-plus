package com.warden.blocker.ui.tour

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tourDataStore by preferencesDataStore(name = "warden_tours")

/**
 * Which guides were seen, skipped or left half-way, and which pages already offered their
 * tips. Its own file, so it never touches the settings store.
 */
class TourStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.tourDataStore)

    val records: Flow<Map<String, TourRecord>> = store.data.map { p ->
        p.asMap().entries
            .filter { it.key.name.startsWith(TOUR_PREFIX) }
            .associate { it.key.name.removePrefix(TOUR_PREFIX) to TourRecord.decode(it.value as? String) }
    }

    val pagesSeen: Flow<Set<String>> = store.data.map { it[PAGES_SEEN] ?: emptySet() }

    suspend fun save(tourId: String, record: TourRecord) =
        store.edit { it[key(tourId)] = record.encode() }.let { }

    suspend fun markPageSeen(route: String) =
        store.edit { it[PAGES_SEEN] = (it[PAGES_SEEN] ?: emptySet()) + route }.let { }

    /** Every guide and page tip as new again. */
    suspend fun resetAll() = store.edit { it.clear() }.let { }

    private fun key(tourId: String) = stringPreferencesKey(TOUR_PREFIX + tourId)

    private companion object {
        const val TOUR_PREFIX = "tour_"
        val PAGES_SEEN = stringSetPreferencesKey("pages_seen")
    }
}
