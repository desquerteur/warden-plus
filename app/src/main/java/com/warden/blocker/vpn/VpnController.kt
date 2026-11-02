package com.warden.blocker.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin facade the UI uses to start/stop the filtering tunnel. */
object VpnController {

    private val _refused = MutableStateFlow(false)

    /**
     * True after Android refused Warden's VPN: the consent dialog closed without "OK", or the
     * tunnel couldn't be established. Almost always another VPN app (e.g. Tailscale) is on or
     * set to "Always-on VPN" — Android allows only one VPN, and with always-on it closes our
     * consent dialog right away, so the user saw nothing at all. Cleared once the tunnel is up.
     */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    private val _revoked = MutableStateFlow(false)

    /**
     * True after Android took the tunnel away (onRevoke): disconnected in VPN settings, another
     * VPN took over, or "Always-on" was cleared when device owner was removed. Not by itself a
     * sign of another VPN — Home checks for one ([otherVpnActive]) before saying so (D6).
     */
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    fun reportRefused() { _refused.value = true }
    fun reportRevoked() { _revoked.value = true }
    fun clearRefused() { _refused.value = false; _revoked.value = false }

    /**
     * Returns an intent to show the system VPN-consent dialog, or null if already granted.
     * The caller must launch it and call [start] once the result is OK.
     */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context) {
        val intent = Intent(context, WardenVpnService::class.java)
            .setAction(WardenVpnService.ACTION_START)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        clearRefused()
        val intent = Intent(context, WardenVpnService::class.java)
            .setAction(WardenVpnService.ACTION_STOP)
        context.startService(intent)
    }

    /**
     * Whether another app's VPN is in the way: it carries this app's traffic now (ours never
     * does: it excludes Warden itself), or another app is set as Android's "Always-on VPN".
     */
    fun otherVpnActive(context: Context): Boolean = otherVpnCarriesTraffic(context) || otherAlwaysOnVpn(context)

    /** Android's always-on VPN app, if it's another app (null when unknown or none). */
    private fun otherAlwaysOnVpn(context: Context): Boolean = runCatching {
        val pkg = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
        !pkg.isNullOrBlank() && pkg != context.packageName
    }.getOrDefault(false)

    private fun otherVpnCarriesTraffic(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /** Android's VPN list, where another app's VPN / "Always-on VPN" can be switched off. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)
}
