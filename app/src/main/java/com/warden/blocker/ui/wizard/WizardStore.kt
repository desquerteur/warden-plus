package com.warden.blocker.ui.wizard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wizardDataStore by preferencesDataStore(name = "warden_setup")

/** Where the setup wizard stands. Its own file, so it never touches the settings store. */
class WizardStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.wizardDataStore)

    val state: Flow<WizardState> = store.data.map { it.toState() }

    suspend fun update(change: (WizardState) -> WizardState) {
        store.edit { p ->
            val new = change(p.toState())
            p[STATUS] = new.status.name
            p[STEP] = new.step.name
            p[SKIPPED] = new.skipped.map { it.name }.toSet()
            p[PROTECTION_CHOSEN] = new.protectionChosen
        }
    }

    private fun Preferences.toState() = WizardState(
        status = WizardStatus.entries.firstOrNull { it.name == this[STATUS] } ?: WizardStatus.NEW,
        step = WizardStep.entries.firstOrNull { it.name == this[STEP] } ?: WizardStep.WELCOME,
        skipped = this[SKIPPED].orEmpty().mapNotNull { name -> WizardStep.entries.firstOrNull { it.name == name } }.toSet(),
        protectionChosen = this[PROTECTION_CHOSEN] ?: false,
    )

    private companion object {
        val STATUS = stringPreferencesKey("status")
        val STEP = stringPreferencesKey("step")
        val SKIPPED = stringSetPreferencesKey("skipped")
        val PROTECTION_CHOSEN = booleanPreferencesKey("protection_chosen")
    }
}
