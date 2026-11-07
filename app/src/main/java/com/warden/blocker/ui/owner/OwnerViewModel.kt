package com.warden.blocker.ui.owner

import android.app.Application
import com.warden.blocker.block.Loosening
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.Verdict
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.owner.DeviceOwnerManager
import com.warden.blocker.owner.EmergencyExit
import com.warden.blocker.owner.OwnerPolicy
import com.warden.blocker.owner.OwnerPrefs
import com.warden.blocker.owner.OwnerSync
import com.warden.blocker.owner.OwnerSyncWorker
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OwnerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = OwnerPrefs.get(app)
    private val protection = app.wardenContainer.protection

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
        val now = TrustedClock.now()
        val readyAt = prefs.exitReadyAt.value
        EmergencyExit.normalizedReadyAt(readyAt, now).let { if (it != readyAt) prefs.setExitReadyAt(it) }
        if (_isOwner.value) sync()
    }

    /** Switching a rule on is immediate; off goes through Block protection (PIN, a wait or a lock). */
    fun setPolicy(policy: OwnerPolicy, on: Boolean) {
        if (on) { setPolicies(prefs.policies.value + policy); return }
        if (policy !in prefs.policies.value) return
        viewModelScope.launch {
            if (protection.request(ProtectedChange.OwnerPolicyOff(policy)) == Verdict.Allow) sync()
        }
    }

    fun enableAll() = setPolicies(OwnerPolicy.entries.toSet())

    /** Only ever adds rules (see [setPolicy] for switching one off). */
    private fun setPolicies(wanted: Set<OwnerPolicy>) = viewModelScope.launch {
        prefs.setPolicies(prefs.policies.value + wanted)
        sync()
    }

    private fun sync() = viewModelScope.launch {
        val problems = withContext(Dispatchers.Default) { OwnerSync.syncNow(getApplication()) }
        _message.value = problems?.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Starts the emergency exit. Removes at once when Block protection allows it; with "PIN" it
     * asks for the PIN and is then ready; "Delay" / "Locked until" store when it may happen (24 h
     * at most). [pinBypassed]: asked from the locked Settings — always the full 24 h.
     */
    fun requestExit(pinBypassed: Boolean) = viewModelScope.launch {
        val now = TrustedClock.now()
        val wait = EmergencyExit.waitMillis(protection.verdict(Loosening.REMOVE_DEVICE_OWNER), now, pinBypassed)
        if (wait == null) {
            protection.request(ProtectedChange.ConfirmOwnerExit) // PIN dialog; afterwards "Remove device owner now"
            return@launch
        }
        if (wait == 0L) remove() else prefs.setExitReadyAt(EmergencyExit.readyAt(now, wait))
    }

    /** Taking the request back only makes protection stronger — always allowed. */
    fun cancelExit() = prefs.setExitReadyAt(0L)

    /** "Remove device owner now": only once a request is ready (its wait is over, or the PIN was given). */
    fun removeNow() {
        val readyAt = prefs.exitReadyAt.value
        if (readyAt == 0L || readyAt > TrustedClock.now()) return
        remove()
    }

    private fun remove() = viewModelScope.launch {
        val app = getApplication<Application>()
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
