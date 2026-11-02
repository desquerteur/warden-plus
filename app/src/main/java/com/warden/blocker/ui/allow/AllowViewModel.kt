package com.warden.blocker.ui.allow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.allow.AllowConfig
import com.warden.blocker.allow.AllowPolicy
import com.warden.blocker.allow.AllowScope
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.CategorizedApp
import com.warden.blocker.allow.ListMode
import com.warden.blocker.allow.ProtectionHook
import com.warden.blocker.data.DomainInput
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
    private val protection = ProtectionHook(container.strictGuard)

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

    fun setAppMode(mode: ListMode) = change { it.copy(appMode = mode) }
    fun setWebMode(mode: ListMode) = change { it.copy(webMode = mode) }
    fun setScope(scope: AllowScope) = change { it.copy(scope = scope) }
    fun setAllowCamera(on: Boolean) = change { it.copy(allowCamera = on) }
    fun setAllowSms(on: Boolean) = change { it.copy(allowSms = on) }

    fun setAppAllowed(packageName: String, allowed: Boolean) = change {
        it.copy(allowedApps = if (allowed) it.allowedApps + packageName else it.allowedApps - packageName)
    }

    /** Returns false (and changes nothing) when [raw] isn't a usable website name. */
    fun addDomain(raw: String): Boolean {
        val clean = DomainInput.normalize(raw) ?: return false
        change { it.copy(allowedDomains = it.allowedDomains + clean) }
        return true
    }

    fun removeDomain(domain: String) = change { it.copy(allowedDomains = it.allowedDomains - domain) }

    fun setCategoryBlocked(category: AppCategory, blocked: Boolean) = change {
        it.copy(blockedCategories = if (blocked) it.blockedCategories + category else it.blockedCategories - category)
    }

    /** [value] is a category name, [com.warden.blocker.allow.AppCategories.NONE], or null to undo the correction. */
    fun setOverride(packageName: String, value: String?) = change {
        it.copy(categoryOverrides = if (value == null) it.categoryOverrides - packageName else it.categoryOverrides + (packageName to value))
    }

    /** Tightening applies at once; a loosening only when Block protection lets it through. */
    private fun change(transform: (AllowConfig) -> AllowConfig) = viewModelScope.launch {
        val old = container.allowStore.current()
        val updated = transform(old)
        if (updated == old) return@launch
        if (!protection.isLooseningAllowed(AllowPolicy.loosenings(old, updated))) return@launch
        container.allowStore.update(transform)
    }
}
