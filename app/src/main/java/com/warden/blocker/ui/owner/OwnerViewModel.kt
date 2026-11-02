package com.warden.blocker.ui.owner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.owner.DeviceOwnerManager
import com.warden.blocker.owner.EmergencyExit
import com.warden.blocker.owner.OwnerLoosening
import com.warden.blocker.owner.OwnerPolicy
import com.warden.blocker.owner.OwnerPrefs
import com.warden.blocker.owner.OwnerSync
import com.warden.blocker.owner.OwnerSyncWorker
import com.warden.blocker.owner.ProtectionHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OwnerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = OwnerPrefs.get(app)

    private val _isOwner = MutableStateFlow(DeviceOwnerManager.isDeviceOwner(app))
    /** Can change from outside (`adb shell dpm …`), so it's re-read on every resume. */
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()

    val policies: StateFlow<Set<OwnerPolicy>> = prefs.policies
    val exitReadyAt: StateFlow<Long> = prefs.exitReadyAt

    private val _message = MutableStateFlow<String?>(null)
    /** Last result worth showing: problems Android reported, or why a change was refused. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        val app = getApplication<Application>()
        _isOwner.value = DeviceOwnerManager.isDeviceOwner(app)
        OwnerSyncWorker.sync(app)
        val now = System.currentTimeMillis()
        val readyAt = prefs.exitReadyAt.value
        EmergencyExit.normalizedReadyAt(readyAt, now).let { if (it != readyAt) prefs.setExitReadyAt(it) }
        if (_isOwner.value) sync()
    }

    fun setPolicy(policy: OwnerPolicy, on: Boolean) {
        setPolicies(if (on) prefs.policies.value + policy else prefs.policies.value - policy)
    }

    fun enableAll() = setPolicies(OwnerPolicy.entries.toSet())

    private fun setPolicies(wanted: Set<OwnerPolicy>) = viewModelScope.launch {
        val loosens = (prefs.policies.value - wanted).isNotEmpty()
        if (loosens && !ProtectionHook.isLooseningAllowed(getApplication(), OwnerLoosening.POLICY_OFF)) {
            _message.value = "Block protection doesn't allow switching this off right now."
            return@launch
        }
        prefs.setPolicies(wanted)
        sync()
    }

    private fun sync() = viewModelScope.launch {
        val problems = withContext(Dispatchers.Default) { OwnerSync.syncNow(getApplication()) }
        _message.value = problems?.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Starts the emergency exit. Removes at once when nothing makes the user wait; otherwise
     * stores when it may happen (24 h at most). [pinBypassed]: asked from the locked Settings.
     */
    fun requestExit(pinBypassed: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val wait = EmergencyExit.waitMillis(
            looseningAllowed = ProtectionHook.isLooseningAllowed(app, OwnerLoosening.REMOVE_DEVICE_OWNER),
            protectionWaitMs = ProtectionHook.waitMillisBefore(app, OwnerLoosening.REMOVE_DEVICE_OWNER, now),
            pinBypassed = pinBypassed,
        )
        if (wait == 0L) removeNow() else prefs.setExitReadyAt(EmergencyExit.readyAt(now, wait))
    }

    /** Taking the request back only makes protection stronger — always allowed. */
    fun cancelExit() = prefs.setExitReadyAt(0L)

    /** Removes device owner, if no wait is pending. */
    fun removeNow() = viewModelScope.launch {
        val app = getApplication<Application>()
        val readyAt = prefs.exitReadyAt.value
        if (readyAt != 0L && readyAt > System.currentTimeMillis()) return@launch
        val problems = withContext(Dispatchers.Default) { DeviceOwnerManager.removeDeviceOwner(app) }
        _isOwner.value = DeviceOwnerManager.isDeviceOwner(app)
        OwnerSyncWorker.sync(app)
        _message.value = when {
            _isOwner.value -> "Couldn't remove device owner:\n" + problems.joinToString("\n")
            problems.isNotEmpty() -> "Device owner removed. Some rules reported problems:\n" + problems.joinToString("\n")
            else -> "Device owner removed. All rules are lifted."
        }
    }

    fun clearMessage() { _message.value = null }
}
