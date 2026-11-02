package com.warden.blocker.system

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import com.warden.blocker.AppContainer
import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.Loosening
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.block.ProtectionState
import com.warden.blocker.block.Verdict
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.whenLabel
import com.warden.blocker.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * The one gate every weakening goes through ([request]): it asks [BlockProtection] and then
 * applies the change, asks for the PIN, queues it ("Delay") or refuses it ("Locked"), with a
 * short message saying which. Queued changes are applied by [PendingChangesWorker].
 */
class ProtectionGuard(private val context: Context, private val c: AppContainer) {

    private val _pinRequest = MutableStateFlow<ProtectedChange?>(null)

    /** A change waiting for the PIN; the app's main screen shows the PIN dialog for it. */
    val pinRequest: StateFlow<ProtectedChange?> = _pinRequest.asStateFlow()

    /** After the PIN was entered, further weakenings pass without asking again for a short while. */
    @Volatile private var pinGraceUntil = 0L
    private val pendingLock = Mutex()

    suspend fun state(): ProtectionState = ProtectionState(
        protection = c.settings.protection.first(),
        hasPin = c.settings.hasPin.first(),
        focusEndsAt = c.settings.focusEndsAt.first(),
        focusStrict = c.settings.focusStrict.first(),
    )

    suspend fun verdict(kind: Loosening): Verdict = BlockProtection.check(kind, state(), System.currentTimeMillis())

    /**
     * Applies [change] if Block protection allows it now. With [canAskPin] false (the Quick
     * Settings tile) a PIN verdict is only returned, so the caller can open the app instead.
     */
    suspend fun request(change: ProtectedChange, canAskPin: Boolean = true): Verdict {
        val now = System.currentTimeMillis()
        val verdict = BlockProtection.check(change.kind, state(), now)
        when (verdict) {
            Verdict.Allow -> apply(change)
            Verdict.NeedsPin -> when {
                now < pinGraceUntil -> { apply(change); return Verdict.Allow }
                canAskPin -> _pinRequest.value = change
            }
            is Verdict.Delayed -> {
                val at = enqueue(change, verdict.at)
                // Short: Android cuts toasts off after two lines.
                toast("${change.describe()} at ${whenText(at)} – cancel on Home.")
            }
            is Verdict.Locked -> toast(lockedMessage(verdict))
        }
        return verdict
    }

    /** Call after any correct PIN entry (unlocking Settings counts too). */
    fun pinVerified() { pinGraceUntil = System.currentTimeMillis() + PIN_GRACE_MS }

    /** The PIN dialog for [pinRequest] was answered correctly. */
    suspend fun confirmPinRequest() {
        val change = _pinRequest.value ?: return
        _pinRequest.value = null
        pinVerified()
        apply(change)
    }

    fun cancelPinRequest() { _pinRequest.value = null }

    fun lockedMessage(v: Verdict.Locked): String =
        if (v.byFocus) "Strict focus session – ends at ${whenText(v.until)}"
        else "Block protection: locked until ${whenText(v.until)}"

    /** "15:40" today, otherwise "Fri 18:00" (12/24 h as set on the phone). */
    fun whenText(at: Long): String = whenLabel(
        Calendar.getInstance().apply { timeInMillis = at }, Calendar.getInstance(), DateFormat.is24HourFormat(context),
    )

    // --- "Delay" queue ---

    private suspend fun enqueue(change: ProtectedChange, at: Long): Long = pendingLock.withLock {
        // Tapping "remove" twice doesn't queue it twice (nor push its time back).
        var dueAt = at
        val list = c.settings.updatePendingChanges { list ->
            val same = list.find { it.change == change }
            if (same != null) { dueAt = same.dueAt; list } else list + PendingChange(System.nanoTime(), at, change)
        }
        PendingChangesWorker.schedule(context, list.minOf { it.dueAt })
        dueAt
    }

    suspend fun cancelPending(id: Long) = pendingLock.withLock {
        val list = c.settings.updatePendingChanges { list -> list.filterNot { it.id == id } }
        PendingChangesWorker.reschedule(context, list.minOfOrNull { it.dueAt })
    }

    /**
     * Applies every queued change that is due (from the worker, and when the app opens). One
     * that a lock now forbids waits until the lock ends.
     */
    suspend fun processDue() = pendingLock.withLock {
        val now = System.currentTimeMillis()
        val state = state()
        val ready = mutableListOf<ProtectedChange>()
        val list = c.settings.updatePendingChanges { list ->
            list.mapNotNull { p ->
                if (p.dueAt > now) return@mapNotNull p
                when (val v = BlockProtection.check(p.change.kind, state, now)) {
                    is Verdict.Locked -> p.copy(dueAt = v.until)
                    else -> { ready += p.change; null }
                }
            }
        }
        ready.forEach { runCatching { apply(it) } }
        PendingChangesWorker.reschedule(context, list.minOfOrNull { it.dueAt })
    }

    /** Carries out [change] right away — only after [request] or the queue allowed it. */
    suspend fun apply(change: ProtectedChange) {
        val s = c.settings
        when (change) {
            ProtectedChange.TurnOffBlocking -> {
                VpnController.stop(context)
                s.setMasterEnabled(false)
            }
            ProtectedChange.EndFocus -> {
                s.clearFocus()
                FocusEndWorker.cancel(context)
                if (!s.masterEnabled.first()) VpnController.stop(context)
            }
            is ProtectedChange.RemoveItem -> c.repository.item(change.itemId)?.let { c.repository.removeItem(it) }
            is ProtectedChange.UpdateItem -> if (c.repository.item(change.item.id) != null) c.repository.updateItem(change.item)
            is ProtectedChange.RemoveSchedule ->
                c.repository.removeSchedule(Schedule(id = change.scheduleId, name = "", daysMask = 0, startMinute = 0, endMinute = 0))
            is ProtectedChange.UpdateSchedule -> c.repository.addSchedule(change.schedule)
            is ProtectedChange.DisableHostsList -> c.hostsLists.setEnabled(change.listId, false)
            is ProtectedChange.RemoveHostsList -> c.hostsLists.delete(change.listId)
            is ProtectedChange.DisableFeedBlock -> s.setFeatureEnabled(change.featureKey, false)
            is ProtectedChange.SettingOff -> when (change.setting) {
                ProtectedChange.Setting.ALWAYS_ON -> s.setAlwaysOn(false)
                ProtectedChange.Setting.BLOCK_DOH -> s.setBlockDoh(false)
                ProtectedChange.Setting.BLOCK_NOTIFICATIONS -> s.setBlockNotifications(false)
            }
            is ProtectedChange.LoosenAllow -> c.allowStore.update(change.edit::applyTo)
            is ProtectedChange.ShortenBlockUntil -> {
                val now = System.currentTimeMillis()
                // Only while that block still runs; queued in "Delay", it may have ended (or been replaced) by now.
                val endsAt = s.focusEndsAt.first()
                if (endsAt > now && change.endsAt < endsAt) {
                    if (change.endsAt <= now) apply(ProtectedChange.EndFocus)
                    else {
                        s.startFocus(change.endsAt, s.focusStrict.first())
                        FocusEndWorker.schedule(context, change.endsAt - now)
                    }
                }
            }
            is ProtectedChange.SetListSpan -> c.hostsLists.setActiveSpan(change.listId, change.from, change.until)
            is ProtectedChange.SetProtection -> s.setProtection(change.settings)
            ProtectedChange.DisableUninstallProtection -> AdminManager.disable(context)
            is ProtectedChange.ChangePin -> s.setPin(change.hash, change.salt)
            ProtectedChange.RemovePin -> {
                s.clearPin()
                // PIN mode means nothing without a PIN.
                val p = s.protection.first()
                if (p.mode == ProtectionMode.PIN) s.setProtection(ProtectionSettings(ProtectionMode.OFF, p.delayMinutes, 0L))
            }
        }
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val PIN_GRACE_MS = 2 * 60_000L
    }
}
