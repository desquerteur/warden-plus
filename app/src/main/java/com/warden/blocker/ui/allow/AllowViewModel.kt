package com.warden.blocker.ui.allow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.allow.AllowConfig
import com.warden.blocker.allow.AllowEdit
import com.warden.blocker.allow.AllowPolicy
import com.warden.blocker.allow.AllowScope
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.CategorizedApp
import com.warden.blocker.allow.ListMode
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.data.DomainInput
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State and actions for the "Allow only" and "Block categories" screens. */
class AllowViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.wardenContainer

    val config: StateFlow<AllowConfig> =
        container.allowStore.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AllowConfig())

    private val _apps = MutableStateFlow<List<CategorizedApp>?>(null)

    /** Launchable apps with their detected category (null while loading). */
    val apps: StateFlow<List<CategorizedApp>?> = _apps.asStateFlow()

    /** Installed apps "Allow only" never blocks (phone, Settings, launcher, keyboard …). */
    val alwaysAllowed: StateFlow<Set<String>> = combine(_apps, config) { list, cfg ->
        list.orEmpty().filter { container.installedApps.isAlwaysAllowed(it.packageName, cfg) }
            .mapTo(LinkedHashSet()) { it.packageName }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Loads [apps]; the screens that list apps call it (the Blocklist entry buttons don't need them). */
    fun refreshApps() = viewModelScope.launch {
        _apps.value = withContext(Dispatchers.Default) { runCatching { container.installedApps.launchableApps() }.getOrDefault(emptyList()) }
    }

    fun setAppMode(mode: ListMode) = change(AllowEdit.SetAppMode(mode))
    fun setWebMode(mode: ListMode) = change(AllowEdit.SetWebMode(mode))
    fun setScope(scope: AllowScope) = change(AllowEdit.SetScope(scope))
    fun setAllowCamera(on: Boolean) = change(AllowEdit.SetAllowCamera(on))
    fun setAllowSms(on: Boolean) = change(AllowEdit.SetAllowSms(on))

    fun setAppAllowed(packageName: String, allowed: Boolean) =
        change(AllowEdit.SetAppAllowed(packageName, labelOf(packageName), allowed))

    /** Returns false (and changes nothing) when [raw] isn't a usable website name. */
    fun addDomain(raw: String): Boolean {
        val clean = DomainInput.normalize(raw) ?: return false
        change(AllowEdit.SetDomainAllowed(clean, true))
        return true
    }

    fun removeDomain(domain: String) = change(AllowEdit.SetDomainAllowed(domain, false))

    fun setCategoryBlocked(category: AppCategory, blocked: Boolean) = change(AllowEdit.SetCategoryBlocked(category, blocked))

    /** [value] is a category name, [com.warden.blocker.allow.AppCategories.NONE], or null to undo the correction. */
    fun setOverride(packageName: String, value: String?) =
        change(AllowEdit.SetOverride(packageName, labelOf(packageName), value))

    private fun labelOf(packageName: String): String =
        _apps.value?.firstOrNull { it.packageName == packageName }?.label
            ?: AppsHelper.labelFor(getApplication(), packageName)

    /**
     * Tightening applies at once; a loosening goes through Block protection (PIN, a wait with
     * "Pending …" on Home, or refused while locked) like every other weakening.
     */
    private fun change(edit: AllowEdit) = viewModelScope.launch {
        var loosens = false
        // Decided inside the update, so nothing changed in between can turn a loosening into a "tightening".
        container.allowStore.update { current ->
            val updated = edit.applyTo(current)
            loosens = AllowPolicy.loosenings(current, updated).isNotEmpty()
            if (loosens) current else updated
        }
        if (loosens) container.protection.request(ProtectedChange.LoosenAllow(edit))
    }
}
