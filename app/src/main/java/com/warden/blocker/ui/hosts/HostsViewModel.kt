package com.warden.blocker.ui.hosts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.Verdict
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
    private val guard = app.wardenContainer.protection

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

    /** Switching a list off goes through Block protection (PIN, a wait or a lock). */
    fun setEnabled(source: HostsSource, enabled: Boolean) = viewModelScope.launch {
        if (enabled) repo.setEnabled(source.id, true)
        else guard.request(ProtectedChange.DisableHostsList(source.id, source.name))
    }

    fun delete(source: HostsSource) = viewModelScope.launch {
        if (guard.request(ProtectedChange.RemoveHostsList(source.id, source.name)) == Verdict.Allow) {
            _message.value = "Removed “${source.name}”"
        }
    }

    fun messageShown() { _message.value = null }

    /** Runs on the repository's app-wide scope, so leaving the screen doesn't cancel a download. */
    private fun launchImport(block: suspend () -> Result<HostsSource>) = repo.scope.launch {
        block().fold(
            onSuccess = { _message.value = "“${it.name}”: ${formatCount(it.domainCount)} domains blocked" },
            onFailure = { _message.value = it.message ?: "Import failed." },
        )
    }
}
