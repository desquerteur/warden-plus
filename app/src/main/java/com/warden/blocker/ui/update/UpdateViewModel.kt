package com.warden.blocker.ui.update

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.update.CheckResult
import com.warden.blocker.update.InstallStatus
import com.warden.blocker.update.ReleaseInfo
import com.warden.blocker.update.UpdateCheckWorker
import com.warden.blocker.update.UpdateChecker
import com.warden.blocker.update.UpdateException
import com.warden.blocker.update.UpdateInstaller
import com.warden.blocker.update.UpdatePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latestVersion: String) : UpdateUiState
    data object NoReleases : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    /** "Install unknown apps" is off for Warden; the user has to allow it first. */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateUiState
    /** Handed to the system installer; waiting for the user's confirmation / the result. */
    data class Installing(val release: ReleaseInfo) : UpdateUiState
    data class Error(val message: String, val release: ReleaseInfo? = null, val needsUninstall: Boolean = false) : UpdateUiState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UpdatePrefs.get(app)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val autoCheck: StateFlow<Boolean> = prefs.autoCheck

    /** System confirmation dialog to launch from the visible activity, if one is pending. */
    val pendingConfirmation: StateFlow<Intent?> = UpdateInstaller.status
        .map { (it as? InstallStatus.NeedsConfirmation)?.intent }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var release: ReleaseInfo? = null
    private var job: Job? = null

    init {
        UpdateCheckWorker.sync(app)
        viewModelScope.launch {
            UpdateInstaller.status.collect { status ->
                val current = release ?: return@collect
                when (status) {
                    InstallStatus.Cancelled -> _state.value = UpdateUiState.Available(current)
                    is InstallStatus.Failed -> _state.value = UpdateUiState.Error(status.message, current)
                    // On success Android restarts Warden as the new version; nothing to show.
                    InstallStatus.Succeeded, is InstallStatus.NeedsConfirmation, null -> Unit
                }
            }
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = when (val result = UpdateChecker.check()) {
                is CheckResult.UpdateAvailable -> {
                    release = result.release
                    UpdateUiState.Available(result.release)
                }
                is CheckResult.UpToDate -> UpdateUiState.UpToDate(result.latest.version.toString())
                CheckResult.NoReleases -> UpdateUiState.NoReleases
                is CheckResult.Failed -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun downloadAndInstall() {
        val target = release ?: return
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        val asset = target.apk
        if (asset == null) {
            _state.value = UpdateUiState.Error("This release has no app file (APK) attached yet. Try again later.", target)
            return
        }
        if (!UpdateInstaller.canInstallPackages(app)) {
            _state.value = UpdateUiState.NeedsPermission(target)
            return
        }
        job = viewModelScope.launch {
            try {
                _state.value = UpdateUiState.Downloading(target, null)
                val apk = UpdateInstaller.download(app, asset) { progress ->
                    _state.value = UpdateUiState.Downloading(target, progress)
                }
                UpdateInstaller.verify(app, apk)
                _state.value = UpdateUiState.Installing(target)
                UpdateInstaller.install(app, apk)
            } catch (e: CancellationException) {
                _state.value = UpdateUiState.Available(target)
                throw e
            } catch (e: UpdateException) {
                _state.value = UpdateUiState.Error(e.message.orEmpty(), target, e.needsUninstall)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    /** Called when the screen is shown again, e.g. after the "Install unknown apps" settings. */
    fun onResume() {
        if (_state.value is UpdateUiState.NeedsPermission && UpdateInstaller.canInstallPackages(getApplication())) {
            downloadAndInstall()
        }
    }

    fun onConfirmationLaunched() = UpdateInstaller.clearStatus()

    fun onConfirmationFailed() {
        UpdateInstaller.clearStatus()
        _state.value = UpdateUiState.Error("Couldn't open the system installer.", release)
    }

    fun setAutoCheck(enabled: Boolean) {
        prefs.setAutoCheck(enabled)
        UpdateCheckWorker.sync(getApplication())
    }
}
