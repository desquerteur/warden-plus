package com.warden.blocker.allow

/** One service as PackageManager lists it for the `android.net.VpnService` action. */
data class ServiceEntry(val packageName: String, val permission: String?)

/**
 * Which installed apps are VPN apps — pure, so it is unit-tested with made-up PackageManager
 * data. Android only lets a VPN app run its tunnel from a service that is bound with
 * `BIND_VPN_SERVICE`, so that permission on a `VpnService` service is the reliable sign; apps
 * can't hide it without stopping to work as a VPN.
 */
object VpnApps {
    const val SERVICE_ACTION = "android.net.VpnService"
    const val BIND_PERMISSION = "android.permission.BIND_VPN_SERVICE"

    /** Tailscale: the VPN most people want to keep (Trusted VPN suggestion). */
    const val TAILSCALE = "com.tailscale.ipn"

    /** VPN apps among [services]; [ownPackage] (Warden+, whose filter is a VPN too) is never one. */
    fun detect(services: List<ServiceEntry>, ownPackage: String): Set<String> =
        services.filter { it.permission == BIND_PERMISSION && it.packageName != ownPackage }
            .mapTo(LinkedHashSet()) { it.packageName }

    /**
     * Trusted VPN apps: the ones the user took out of the "VPN apps" category ("Trust" — a
     * loosening that goes through Block protection). They may run, but while one holds Android's
     * only VPN slot Warden's own filter can't; Browser mode then blocks in browsers instead.
     */
    fun trusted(vpnApps: Set<String>, config: AllowConfig): Set<String> =
        vpnApps.filterTo(LinkedHashSet()) { config.categoryOverrides[it] == AppCategories.NONE }

    /**
     * VPN apps that "Switch off VPN apps" (device owner) suspends: all detected ones except the
     * trusted ones and [protected] packages. Only while blocking is enforced ([enforced]).
     */
    fun suspendTargets(vpnApps: Set<String>, config: AllowConfig, protected: Set<String>, enforced: Boolean): Set<String> =
        if (!enforced) emptySet() else vpnApps - trusted(vpnApps, config) - protected

    /** Android's "Connection request" dialog that every VPN app needs once before it may connect. */
    const val CONSENT_DIALOG_PACKAGE = "com.android.vpndialogs"

    /**
     * Which of [blockedLabels] (package → app name) the VPN consent dialog is asking for: the
     * dialog names the app ("FreeVPN wants to set up a VPN connection …"). The longest matching
     * name wins, so "VPN" doesn't match "VPN Pro". Null when none of them.
     */
    fun consentRequester(dialogText: String, blockedLabels: Map<String, String>): String? =
        blockedLabels.entries.filter { (_, label) -> label.isNotBlank() && dialogText.contains(label, ignoreCase = true) }
            .maxByOrNull { it.value.length }?.key

    /**
     * The app most likely behind a VPN that just took over, or null when Warden+ can't tell
     * (Android doesn't say who owns another app's VPN). Its "Always-on VPN" setting names it for
     * sure; otherwise a VPN app the user had in front a moment ago ([recentForeground]) did it.
     */
    fun takeoverSuspect(alwaysOnPackage: String?, recentForeground: String?, vpnApps: Set<String>, ownPackage: String): String? =
        alwaysOnPackage?.takeIf { it.isNotBlank() && it != ownPackage }
            ?: recentForeground?.takeIf { it in vpnApps }
}
