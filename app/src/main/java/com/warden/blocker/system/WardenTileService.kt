package com.warden.blocker.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.warden.blocker.MainActivity
import com.warden.blocker.R
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap to toggle Warden blocking from the notification shade.
 *
 * - Turning ON needs the VPN to be consented. If it isn't yet, we send the user into the
 *   app to grant it once; afterwards the tile toggles directly.
 * - Turning OFF while a PIN is set is routed through the app so the PIN gate still applies —
 *   the tile can't be used to bypass strict mode.
 */
class WardenTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refreshTile() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val enabled = applicationContext.wardenContainer.settings.masterEnabled.first()
            if (enabled) turnOff() else turnOn()
            refreshTile()
        }
    }

    private suspend fun turnOn() {
        if (VpnController.consentIntent(this) != null) {
            // Consent not granted yet: the app asks for it and then turns blocking on by itself
            // (it used to just open, so nothing seemed to happen).
            openApp(enableBlocking = true)
            return
        }
        VpnController.start(this)
        applicationContext.wardenContainer.settings.setMasterEnabled(true)
    }

    private suspend fun turnOff() {
        if (applicationContext.wardenContainer.settings.hasPin.first()) {
            openApp() // let the in-app PIN gate handle disabling
            return
        }
        VpnController.stop(this)
        applicationContext.wardenContainer.settings.setMasterEnabled(false)
    }

    private fun openApp(enableBlocking: Boolean = false) {
        val intent = if (enableBlocking) MainActivity.requestIntent(this, enableBlocking = true)
        else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Own request code per variant (PendingIntents that differ only in extras are shared);
            // UPDATE_CURRENT carries this tap's request id.
            val pi = PendingIntent.getActivity(
                this, if (enableBlocking) 1 else 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun refreshTile() {
        val enabled = applicationContext.wardenContainer.settings.masterEnabled.first()
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (enabled) "Blocking on" else "Off"
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
