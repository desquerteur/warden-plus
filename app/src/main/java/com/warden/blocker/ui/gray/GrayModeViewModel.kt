package com.warden.blocker.ui.gray

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.gray.GrayMode
import com.warden.blocker.gray.GrayModeConfig
import com.warden.blocker.gray.GrayModePrefs
import com.warden.blocker.gray.GrayStatus
import com.warden.blocker.gray.GrayWhen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings › Gray mode. Stricter changes are instant; weaker ones go through Block protection. */
class GrayModeViewModel(app: Application) : AndroidViewModel(app) {
    val config: StateFlow<GrayModeConfig> = GrayModePrefs.get(app).config
    val status: StateFlow<GrayStatus> = GrayMode.status

    /** Re-checks the permission (it is granted from outside, over ADB) and the screen. */
    fun refresh() = viewModelScope.launch { GrayMode.sync(getApplication()) }

    fun setEnabled(on: Boolean) = change(config.value.copy(enabled = on))
    fun setWhen(whenMode: GrayWhen) = change(config.value.copy(whenMode = whenMode))
    fun setStart(minute: Int) = change(config.value.copy(startMinute = minute))
    fun setEnd(minute: Int) = change(config.value.copy(endMinute = minute))

    private fun change(new: GrayModeConfig) = viewModelScope.launch { GrayMode.requestConfig(getApplication(), new) }
}
