package com.warden.blocker.gray

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.warden.blocker.MainActivity
import com.warden.blocker.block.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Gray mode": switches the gray-mode setting on at once; switching it off
 * goes through Block protection like the card (with a PIN the app opens for it). Without the
 * one-time permission it opens the app, where Settings › Gray mode explains the setup.
 */
class GrayModeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            GrayMode.sync(applicationContext)
            refreshTile()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val config = GrayModePrefs.get(applicationContext).config.value
            if (!GrayMode.hasPermission(applicationContext)) {
                // Saving the wish is fine (it's stricter); the app shows how to finish the setup.
                if (!config.enabled) GrayMode.requestConfig(applicationContext, config.copy(enabled = true))
                openApp()
                return@launch
            }
            val verdict = GrayMode.requestConfig(applicationContext, config.copy(enabled = !config.enabled))
            if (verdict == Verdict.NeedsPin) openApp() // the app shows the PIN dialog for this request
            refreshTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val config = GrayModePrefs.get(applicationContext).config.value
        val status = GrayMode.status.value
        qsTile?.apply {
            state = if (config.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Gray mode"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = when {
                    !config.enabled -> "Off"
                    !status.permission -> "Setup needed"
                    status.grayNow -> "Gray now"
                    else -> config.whenMode.label
                }
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
