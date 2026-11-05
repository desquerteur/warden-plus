package com.warden.blocker.system

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android's "Private DNS" with a provider name (e.g. dns.google) sends DNS encrypted (DNS over
 * TLS) straight to that provider — past Warden's filter, so websites aren't blocked. "Automatic"
 * (opportunistic) and "Off" are fine: Android then uses the DNS servers it is given, which is
 * Warden's filter while it runs.
 */
object PrivateDns {
    const val MODE_SETTING = "private_dns_mode"
    const val SPECIFIER_SETTING = "private_dns_specifier"
    const val MODE_HOSTNAME = "hostname"

    /** Whether [mode] (the `private_dns_mode` global setting) gets around the filter. */
    fun bypasses(mode: String?): Boolean = mode?.trim()?.lowercase() == MODE_HOSTNAME

    /** The provider set, if Private DNS bypasses the filter (null otherwise). */
    fun bypassProvider(mode: String?, specifier: String?): String? =
        if (bypasses(mode)) specifier?.trim()?.takeIf { it.isNotEmpty() } ?: "a custom provider" else null

    /** Readable by any app (Settings.Global); null when Android doesn't say. */
    fun currentBypass(context: Context): String? = runCatching {
        val r = context.contentResolver
        bypassProvider(Settings.Global.getString(r, MODE_SETTING), Settings.Global.getString(r, SPECIFIER_SETTING))
    }.getOrNull()

    /**
     * Where Private DNS is switched: Network & internet → Private DNS. There's no public intent
     * for that page; the wireless settings ("Network & internet" / Samsung "Connections") are the
     * closest that always works.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
}
