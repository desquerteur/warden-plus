package com.warden.blocker.ui.tour

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * Runs one tour at a time and saves what happened (seen, skipped, how far). Plain class so the
 * unit tests can drive it; [TourViewModel] keeps it alive across folding and rotating.
 */
class TourController(private val store: TourStore, private val scope: CoroutineScope, private val tours: (String) -> Tour? = Tours::byId) {

    private val writer = SerialWriter(scope)

    private fun write(block: suspend () -> Unit) = writer.write(block)

    private val _run = MutableStateFlow<TourRun?>(null)
    val run: StateFlow<TourRun?> = _run.asStateFlow()

    /** Set when a tour ended without anything to point at (e.g. Settings locked by the PIN). */
    private val _emptyNotice = MutableStateFlow<String?>(null)
    val emptyNotice: StateFlow<String?> = _emptyNotice.asStateFlow()

    /** null until loaded, so nothing auto-starts on a guess. */
    val records: StateFlow<Map<String, TourRecord>?> = store.records.stateIn(scope, SharingStarted.Eagerly, null)
    val pagesSeen: StateFlow<Set<String>?> = store.pagesSeen.stateIn(scope, SharingStarted.Eagerly, null)

    fun tour(): Tour? = _run.value?.let { tours(it.tourId) }

    fun status(tourId: String): TourStatus = records.value?.get(tourId)?.status ?: TourStatus.NEW

    /** Starts [tourId]; [resume] continues a tour left half-way. */
    fun start(tourId: String, resume: Boolean = false) {
        val tour = tours(tourId) ?: return
        if (tour.steps.isEmpty()) return
        write {
            val record = store.records.first()[tourId] ?: TourRecord()
            begin(tour, TourLogic.startIndex(record, tour.steps.size, resume))
        }
    }

    private suspend fun begin(tour: Tour, index: Int) {
        _emptyNotice.value = null
        _run.value = TourRun(tour.id, index)
        store.save(tour.id, TourRecord(TourStatus.IN_PROGRESS, index))
    }

    fun next() {
        val run = _run.value ?: return
        val tour = tours(run.tourId) ?: return
        val next = TourLogic.next(run, tour.steps.size)
        if (next == null) finish(TourStatus.COMPLETED) else move(next)
    }

    fun back() {
        val run = _run.value ?: return
        TourLogic.back(run)?.let(::move)
    }

    /** The step on screen has no target: move on, or end the tour if nothing is left. */
    fun targetMissing() {
        val run = _run.value ?: return
        val tour = tours(run.tourId) ?: return
        val next = TourLogic.skipMissing(run, tour.steps.size)
        if (next != null) { move(next); return }
        val shown = tour.steps.size - (run.missing + run.index).size
        finish(TourStatus.COMPLETED)
        if (shown == 0) {
            _emptyNotice.value = "Couldn't find these parts on screen right now. If Settings is locked, " +
                "unlock it with your PIN and start “${tour.title}” again."
        }
    }

    /** Steps found missing before reaching them (see [TourLogic.missingAhead]). */
    fun markMissing(indices: Set<Int>) {
        val run = _run.value ?: return
        if (!run.missing.containsAll(indices)) _run.value = run.copy(missing = run.missing + indices)
    }

    fun skip() = finish(TourStatus.SKIPPED)

    /** Declined without starting it (e.g. "Finish" in the setup wizard): page tips take over. */
    fun markSkippedIfNew(tourId: String) = write {
        if ((store.records.first()[tourId]?.status ?: TourStatus.NEW) == TourStatus.NEW) {
            store.save(tourId, TourRecord(TourStatus.SKIPPED))
        }
    }

    fun dismissEmptyNotice() { _emptyNotice.value = null }

    fun markPageSeen(route: String) = write { store.markPageSeen(route) }

    /** Every guide as new, with page tips offered again (they follow a skipped "Getting started"). */
    fun resetAll() {
        _run.value = null
        write {
            store.resetAll()
            store.save(Tours.GETTING_STARTED, TourRecord(TourStatus.SKIPPED))
        }
    }

    private fun move(next: TourRun) {
        _run.value = next
        write { store.save(next.tourId, TourRecord(TourStatus.IN_PROGRESS, next.index)) }
    }

    private fun finish(status: TourStatus) {
        val run = _run.value ?: return
        _run.value = null
        write { store.save(run.tourId, TourRecord(status, run.index)) }
    }
}

class TourViewModel(app: Application) : AndroidViewModel(app) {
    val controller = TourController(TourStore(app), viewModelScope)

    /** Route the current step's path animation started from, for "Show me again". */
    var pathOrigin: String? = null
}
