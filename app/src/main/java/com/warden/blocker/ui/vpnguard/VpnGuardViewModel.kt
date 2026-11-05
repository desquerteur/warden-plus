package com.warden.blocker.ui.vpnguard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.allow.AllowConfig
import com.warden.blocker.allow.AllowEdit
import com.warden.blocker.allow.AllowPolicy
import com.warden.blocker.allow.AppCategories
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.VpnApps
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.hosts.HostsTemplates
import com.warden.blocker.system.PrivateDns
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State and actions for the "VPN bypass protection" card (Protection page). */
class VpnGuardViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.wardenContainer

    val config: StateFlow<AllowConfig> = c.allowStore.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AllowConfig())

    /** The bypass list, if added (null: not added). */
    val bypassList: StateFlow<HostsSource?> = c.hostsLists.sources
        .map { list -> list.firstOrNull { it.url == HostsTemplates.VPN_BYPASS_URL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val browserMode: StateFlow<Boolean> = c.settings.browserMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val switchEvents: StateFlow<List<Long>> = c.settings.vpnSwitchEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Installed VPN apps with their names (package → label), loaded off the main thread. */
    private val _vpnApps = MutableStateFlow<Map<String, String>>(emptyMap())
    val vpnApps: StateFlow<Map<String, String>> = _vpnApps.asStateFlow()

    private val _privateDns = MutableStateFlow<String?>(null)
    /** The Private DNS provider that goes around the filter, or null when all is fine. */
    val privateDns: StateFlow<String?> = _privateDns.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Re-read the phone's state (on every resume: the user may come back from Settings). */
    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        _privateDns.value = PrivateDns.currentBypass(app)
        _vpnApps.value = withContext(Dispatchers.Default) {
            c.installedApps.invalidate(null)
            c.installedApps.vpnPackages().associateWith { AppsHelper.labelFor(app, it) }
        }
    }

    fun setVpnAppsBlocked(blocked: Boolean) = change(AllowEdit.SetCategoryBlocked(AppCategory.VPN, blocked))

    /** Trusted VPN: out of the "VPN apps" category (a loosening — Block protection decides). */
    fun setTrusted(packageName: String, trusted: Boolean) = change(
        AllowEdit.SetOverride(packageName, _vpnApps.value[packageName] ?: packageName, if (trusted) AppCategories.NONE else null),
    )

    fun setBypassList(on: Boolean) = viewModelScope.launch {
        val list = bypassList.value
        when {
            on && list == null -> {
                _downloading.value = true
                c.hostsLists.addUrl(HostsTemplates.VPN_BYPASS_URL, HostsTemplates.VPN_BYPASS_NAME)
                    .onFailure { _message.value = "Couldn't download the list: ${it.message}" }
                _downloading.value = false
            }
            on -> c.hostsLists.setEnabled(list!!.id, true)
            list != null && list.enabled -> c.protection.request(ProtectedChange.DisableHostsList(list.id, list.name))
        }
    }

    fun setBrowserMode(on: Boolean) = viewModelScope.launch {
        if (on) c.settings.setBrowserMode(true)
        else c.protection.request(ProtectedChange.SettingOff(ProtectedChange.Setting.BROWSER_MODE))
    }

    fun clearMessage() { _message.value = null }

    /** Same rule as the categories screen: tightening at once, loosening through Block protection. */
    private fun change(edit: AllowEdit) = viewModelScope.launch {
        var loosens = false
        c.allowStore.update { current ->
            val updated = edit.applyTo(current)
            loosens = AllowPolicy.loosenings(current, updated).isNotEmpty()
            if (loosens) current else updated
        }
        if (loosens) c.protection.request(ProtectedChange.LoosenAllow(edit))
    }
}
