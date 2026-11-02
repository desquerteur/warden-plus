package com.warden.blocker.ui.times

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.block.TimeChange
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.block.TimesProtectionHook
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.Schedule
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.system.FocusEndWorker
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Custom times: "Block until …", one-time blocks, date ranges and per-entry spans. Every change
 * that blocks less asks [TimesProtectionHook] first; the actual saving of schedules and entries
 * stays with the main view model (passed in as `save`), so its own checks still run.
 */
class TimesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.wardenContainer

    /**
     * Force blocking on until [endsAt] (like a focus session with a fixed end). Moving a running
     * block's end later always works; moving it earlier is a loosening. A strict block stays
     * strict when extended. [onStarted] runs once it is saved (Home then makes sure the VPN runs).
     */
    fun blockUntil(endsAt: Long, strict: Boolean, onStarted: () -> Unit) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (endsAt <= now) return@launch
        val settings = container.settings
        val currentEnd = settings.focusEndsAt.first()
        val running = currentEnd > now
        if (running && endsAt < currentEnd &&
            !TimesProtectionHook.isLooseningAllowed(getApplication(), TimeChange.SHORTEN_BLOCK_UNTIL)
        ) return@launch
        settings.startFocus(endsAt, strict || (running && settings.focusStrict.first()))
        FocusEndWorker.schedule(getApplication(), endsAt - now)
        onStarted()
    }

    fun saveSchedule(old: Schedule?, new: Schedule, save: (Schedule) -> Unit) = viewModelScope.launch {
        if (old != null && TimeWindows.scheduleTimesLoosen(old, new, System.currentTimeMillis())) {
            val change = if (TimeWindows.isOnce(old)) TimeChange.CHANGE_ONE_TIME_BLOCK else TimeChange.NARROW_DATE_RANGE
            if (!TimesProtectionHook.isLooseningAllowed(getApplication(), change)) return@launch
        }
        save(new)
    }

    fun saveItem(old: BlockedItem, new: BlockedItem, save: (BlockedItem) -> Unit) = viewModelScope.launch {
        if (TimeWindows.itemTimesLoosen(old, new) &&
            !TimesProtectionHook.isLooseningAllowed(getApplication(), TimeChange.SHORTEN_ITEM_SPAN)
        ) return@launch
        save(new)
    }

    fun setListSpan(source: HostsSource, from: Long?, until: Long?) = viewModelScope.launch {
        if (TimeWindows.spanLoosens(source.activeFrom, source.activeUntil, from, until) &&
            !TimesProtectionHook.isLooseningAllowed(getApplication(), TimeChange.SHORTEN_LIST_SPAN)
        ) return@launch
        container.hostsLists.setActiveSpan(source.id, from, until)
    }
}
