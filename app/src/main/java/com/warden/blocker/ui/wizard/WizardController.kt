package com.warden.blocker.ui.wizard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.ui.tour.SerialWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Moves through the wizard and saves every move, in order. Plain class so the unit tests can
 * drive it; [WizardViewModel] keeps it alive while the wizard is open.
 */
class WizardController(private val store: WizardStore, scope: CoroutineScope, private val sdk: Int) {

    private val writer = SerialWriter(scope)

    /** null until loaded. */
    val state: StateFlow<WizardState?> = store.state.stateIn(scope, SharingStarted.Eagerly, null)

    private fun update(change: (WizardState) -> WizardState) { writer.write { store.update(change) } }

    /** The wizard was opened. Once finished it opens on the summary, otherwise where it was left. */
    fun opened() = update {
        if (it.status == WizardStatus.FINISHED) it.copy(step = WizardStep.DONE) else it.copy(status = WizardStatus.IN_PROGRESS)
    }

    fun goTo(step: WizardStep) = update { it.copy(step = step) }

    fun next(from: WizardStep) = goTo(WizardLogic.next(from, sdk))

    fun back(from: WizardStep) { WizardLogic.back(from, sdk)?.let(::goTo) }

    /** "Later": remembered, so Home's card and the summary don't nag about it. */
    fun later(step: WizardStep) = update { it.copy(skipped = it.skipped + step, step = WizardLogic.next(step, sdk)) }

    /** "Skip setup" / leaving early: only the Home card reminds from now on. */
    fun dismiss() = update { if (it.status == WizardStatus.FINISHED) it else it.copy(status = WizardStatus.DISMISSED) }

    fun finish() = update { it.copy(status = WizardStatus.FINISHED, step = WizardStep.DONE) }

    fun chooseProtection() = update { it.copy(protectionChosen = true) }

    /** ✕ on the Home card: the open tasks count as "later". */
    fun skipAll(steps: Collection<WizardStep>) = update { it.copy(skipped = it.skipped + steps) }
}

class WizardViewModel(app: Application) : AndroidViewModel(app) {
    val controller = WizardController(WizardStore(app), viewModelScope, android.os.Build.VERSION.SDK_INT)
}
