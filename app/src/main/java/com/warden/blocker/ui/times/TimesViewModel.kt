package com.warden.blocker.ui.times

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.system.FocusEndWorker
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Custom times: "Block until …" and hosts-list spans. Shortening goes through Block protection
 * (PIN, a wait or a lock) like every other weakening; extending applies at once. Entries and
 * schedules are saved by the main view model, whose Block protection rules include their
 * time fields ([com.warden.blocker.block.BlockProtection.itemEditLoosens] / `scheduleEditLoosens`).
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
        if (running && endsAt < currentEnd) {
            container.protection.request(ProtectedChange.ShortenBlockUntil(endsAt))
            return@launch
        }
        settings.startFocus(endsAt, strict || (running && settings.focusStrict.first()))
        FocusEndWorker.schedule(getApplication(), endsAt - now)
        onStarted()
    }

    fun setListSpan(source: HostsSource, from: Long?, until: Long?) = viewModelScope.launch {
        if (TimeWindows.spanLoosens(source.activeFrom, source.activeUntil, from, until)) {
            container.protection.request(ProtectedChange.SetListSpan(source.id, source.name, from, until))
        } else {
            container.hostsLists.setActiveSpan(source.id, from, until)
        }
    }
}
