package com.warden.blocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.DomainInput
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule
import com.warden.blocker.feature.FeatureCatalog
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.security.PinHasher
import com.warden.blocker.security.PinResult
import com.warden.blocker.system.FocusEndWorker
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WardenViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.wardenContainer
    private val protectionGuard = container.protection

    val items: StateFlow<List<BlockedItem>> =
        container.repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val schedules: StateFlow<List<Schedule>> =
        container.repository.schedules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Imported hosts lists, for the count on Home. */
    val hostsLists: StateFlow<List<HostsSource>> = container.hostsLists.sources

    init {
        viewModelScope.launch { container.hostsLists.ensureLoaded() }
        // Catch up on "Delay" changes that fell due while the app was closed.
        viewModelScope.launch { protectionGuard.processDue() }
    }

    val masterEnabled: StateFlow<Boolean> =
        container.settings.masterEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val accessibilityUsed: StateFlow<Boolean> =
        container.settings.accessibilityUsed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val alwaysOn: StateFlow<Boolean> =
        container.settings.alwaysOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAlwaysOn(value: Boolean) = viewModelScope.launch {
        if (value) container.settings.setAlwaysOn(true)
        else protectionGuard.request(ProtectedChange.SettingOff(ProtectedChange.Setting.ALWAYS_ON))
    }

    val blockDoh: StateFlow<Boolean> =
        container.settings.blockDoh.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBlockDoh(value: Boolean) = viewModelScope.launch {
        if (value) container.settings.setBlockDoh(true)
        else protectionGuard.request(ProtectedChange.SettingOff(ProtectedChange.Setting.BLOCK_DOH))
    }

    val blockNotifications: StateFlow<Boolean> =
        container.settings.blockNotifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBlockNotifications(value: Boolean) = viewModelScope.launch {
        if (value) container.settings.setBlockNotifications(true)
        else protectionGuard.request(ProtectedChange.SettingOff(ProtectedChange.Setting.BLOCK_NOTIFICATIONS))
    }
    val hasPin: StateFlow<Boolean> =
        container.settings.hasPin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val currentStreak: StateFlow<Int> =
        container.settings.currentStreak.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val longestStreak: StateFlow<Int> =
        container.settings.longestStreak.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val focusSessionsDone: StateFlow<Int> =
        container.settings.focusSessionsDone.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val enabledFeatureKeys: StateFlow<Set<String>> =
        container.settings.enabledFeatureKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val focusEndsAt: StateFlow<Long> =
        container.settings.focusEndsAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val focusStrict: StateFlow<Boolean> =
        container.settings.focusStrict.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Block protection ---
    val protection: StateFlow<ProtectionSettings> =
        container.settings.protection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProtectionSettings())
    val pendingChanges: StateFlow<List<PendingChange>> =
        container.settings.pendingChanges.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val protectionOffEvents: StateFlow<List<Long>> =
        container.settings.protectionOffEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A weakening waiting for the PIN (shown as a PIN dialog by the main screen). */
    val pinRequest: StateFlow<ProtectedChange?> = protectionGuard.pinRequest

    fun confirmPinRequest() = viewModelScope.launch { protectionGuard.confirmPinRequest() }
    fun cancelPinRequest() = protectionGuard.cancelPinRequest()
    fun cancelPending(id: Long) = viewModelScope.launch { protectionGuard.cancelPending(id) }

    /** "15:40" or "Fri 18:00". */
    fun whenText(at: Long): String = protectionGuard.whenText(at)

    /** Tightening applies at once; weakening goes through Block protection itself. */
    fun setProtection(new: ProtectionSettings) = viewModelScope.launch { changeProtection(new) }

    private suspend fun changeProtection(new: ProtectionSettings) {
        val now = System.currentTimeMillis()
        val capped = new.copy(lockedUntil = new.lockedUntil.coerceAtMost(BlockProtection.maxLockedUntil(now)))
        if (BlockProtection.protectionChangeLoosens(container.settings.protection.first(), capped, now)) {
            protectionGuard.request(ProtectedChange.SetProtection(capped))
        } else {
            container.settings.setProtection(capped)
        }
    }

    /** Sets a first PIN (only called when there is none), then switches to PIN mode. */
    fun setPinAndProtection(pin: String, new: ProtectionSettings) = viewModelScope.launch {
        val salt = PinHasher.newSalt()
        container.settings.setPin(PinHasher.hash(pin, salt), salt)
        changeProtection(new)
    }

    /** Begin a focus session that forces blocking on for [minutes]. */
    fun startFocus(minutes: Int, strict: Boolean) = viewModelScope.launch {
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        container.settings.startFocus(endsAt, strict)
        FocusEndWorker.schedule(getApplication(), minutes * 60_000L)
    }

    /** Ending early is a weakening (a strict session can't be ended at all). */
    fun cancelFocus() = viewModelScope.launch { protectionGuard.request(ProtectedChange.EndFocus) }

    /** Switching blocking off; on goes through the VPN consent in MainActivity. */
    fun turnOffBlocking() = viewModelScope.launch { protectionGuard.request(ProtectedChange.TurnOffBlocking) }

    fun setFeatureEnabled(key: String, enabled: Boolean) = viewModelScope.launch {
        if (enabled) container.settings.setFeatureEnabled(key, true)
        else protectionGuard.request(
            ProtectedChange.DisableFeedBlock(key, FeatureCatalog.ALL.find { it.key == key }?.label ?: key),
        )
    }

    /** Record a protected day for the focus streak, once per open while blocking is on. */
    fun onAppOpened() = viewModelScope.launch {
        if (container.settings.masterEnabled.first()) container.settings.recordActiveDay()
    }

    private val _justAdded = MutableStateFlow<Long?>(null)

    /** Id of the item the user just added (or typed in again), so Blocklist can scroll to it and
     *  highlight it — the list is sorted by name, so a new entry may land off-screen. */
    val justAdded: StateFlow<Long?> = _justAdded.asStateFlow()

    fun consumeJustAdded(id: Long) { _justAdded.compareAndSet(id, null) }

    /** Returns false (and adds nothing) when [domain] isn't a usable website name. */
    fun addWebsite(domain: String): Boolean {
        val clean = DomainInput.normalize(domain) ?: return false
        viewModelScope.launch {
            // Typing a site that is already listed points at the existing entry instead of
            // adding a second copy.
            _justAdded.value = container.repository.websiteItem(clean)?.id
                ?: container.repository.addItem(
                    BlockedItem(type = BlockType.WEBSITE, value = clean, label = clean, createdAt = System.currentTimeMillis()),
                )
        }
        return true
    }

    /** New apps default to a mindful PAUSE (ScreenZen-style), not a hard block. */
    fun addApp(packageName: String, label: String) = viewModelScope.launch {
        if (container.repository.appItem(packageName) != null) return@launch
        _justAdded.value = container.repository.addItem(
            BlockedItem(
                type = BlockType.APP,
                value = packageName,
                label = label,
                createdAt = System.currentTimeMillis(),
                interceptMode = InterceptMode.PAUSE,
            ),
        )
    }

    fun toggleItem(item: BlockedItem) = saveItem(item.copy(enabled = !item.enabled))

    /** Persist edits from the per-item config editor (and the on/off switch). */
    fun saveItem(item: BlockedItem) = viewModelScope.launch {
        val old = container.repository.item(item.id) ?: return@launch
        if (BlockProtection.itemEditLoosens(old, item)) {
            protectionGuard.request(ProtectedChange.UpdateItem(item, disables = old.enabled && !item.enabled))
        } else {
            container.repository.updateItem(item)
        }
    }

    fun removeItem(item: BlockedItem) = viewModelScope.launch {
        protectionGuard.request(ProtectedChange.RemoveItem(item.id, item.label))
    }

    /** Adds a new schedule or saves an edited (or switched on/off) one. */
    fun addSchedule(schedule: Schedule) = viewModelScope.launch {
        val old = if (schedule.id == 0L) null else schedules.value.find { it.id == schedule.id }
        if (old != null && BlockProtection.scheduleEditLoosens(old, schedule)) {
            protectionGuard.request(ProtectedChange.UpdateSchedule(schedule, disables = old.enabled && !schedule.enabled))
        } else {
            container.repository.addSchedule(schedule)
        }
    }

    fun removeSchedule(schedule: Schedule) = viewModelScope.launch {
        protectionGuard.request(ProtectedChange.RemoveSchedule(schedule.id, schedule.name))
    }

    fun setMasterEnabled(enabled: Boolean) = viewModelScope.launch { container.settings.setMasterEnabled(enabled) }

    fun disableUninstallProtection() = viewModelScope.launch {
        protectionGuard.request(ProtectedChange.DisableUninstallProtection)
    }

    // --- PIN ---
    /** A first PIN applies at once; changing an existing one is protected. */
    fun setPin(pin: String) = viewModelScope.launch {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash(pin, salt)
        if (container.settings.hasPin.first()) protectionGuard.request(ProtectedChange.ChangePin(hash, salt))
        else container.settings.setPin(hash, salt)
    }

    /** PIN mode goes with it (see ProtectionGuard.apply). */
    fun clearPin() = viewModelScope.launch { protectionGuard.request(ProtectedChange.RemovePin) }

    /** Verifies [pin], enforcing a compounding lockout after repeated failures. */
    suspend fun verifyPin(pin: String): PinResult {
        val now = System.currentTimeMillis()
        val lockUntil = container.settings.pinLockUntil()
        if (lockUntil > now) return PinResult.Locked(((lockUntil - now) / 1000).toInt() + 1)

        val (hash, salt) = container.settings.pinCredentials() ?: return PinResult.Wrong
        return if (PinHasher.verify(pin, salt, hash)) {
            container.settings.recordPinSuccess()
            protectionGuard.pinVerified()
            PinResult.Ok
        } else {
            val newLock = container.settings.recordPinFailure()
            if (newLock > now) PinResult.Locked(((newLock - now) / 1000).toInt() + 1) else PinResult.Wrong
        }
    }
}
