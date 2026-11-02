package com.warden.blocker.ui.hosts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.block.Loosening
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.hosts.HostsTemplate
import com.warden.blocker.hosts.ImportProgress
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Drives [HostsListsScreen]; all heavy work happens in HostsListRepository on IO. */
class HostsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.wardenContainer.hostsLists
    private val guard = app.wardenContainer.strictGuard

    val sources: StateFlow<List<HostsSource>> = repo.sources
    val progress: StateFlow<ImportProgress?> = repo.progress

    /** One-shot message for the snackbar (success or error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repo.ensureLoaded() }
    }

    fun importFile(uri: Uri) = launchImport { repo.importFile(uri) }

    fun addUrl(url: String) = launchImport { repo.addUrl(url) }

    fun addTemplate(template: HostsTemplate) = launchImport { repo.addUrl(template.url, template.name) }

    fun refresh(source: HostsSource) = launchImport { repo.refresh(source.id) }

    fun setEnabled(source: HostsSource, enabled: Boolean) = viewModelScope.launch {
        if (!enabled && refused(Loosening.DISABLE_HOSTS_LIST)) return@launch
        repo.setEnabled(source.id, enabled)
    }

    fun delete(source: HostsSource) = viewModelScope.launch {
        if (refused(Loosening.REMOVE_HOSTS_LIST)) return@launch
        repo.delete(source.id)
        _message.value = "Removed “${source.name}”"
    }

    fun messageShown() { _message.value = null }

    /** A strict focus session keeps every list on; the snackbar says until when. */
    private suspend fun refused(change: Loosening): Boolean {
        val reason = guard.reason(change) ?: return false
        _message.value = guard.message(reason)
        return true
    }

    /** Runs on the repository's app-wide scope, so leaving the screen doesn't cancel a download. */
    private fun launchImport(block: suspend () -> Result<HostsSource>) = repo.scope.launch {
        block().fold(
            onSuccess = { _message.value = "“${it.name}”: ${formatCount(it.domainCount)} domains blocked" },
            onFailure = { _message.value = it.message ?: "Import failed." },
        )
    }
}
