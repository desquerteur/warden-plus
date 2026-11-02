package com.warden.blocker.ui.tour

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.warden.blocker.system.AdminManager
import com.warden.blocker.vpn.VpnController

/**
 * One drawn mini screen of an Android settings walk-through. The finger taps row [tap]
 * ([TAP_MENU] = the ⋮ button, then [menu]'s first entry; [TAP_NONE] = just look). A row in
 * [toggle] has a switch that flips to [toggleTo] when tapped. [dialog] draws the rows as a
 * pop-up with the last row as its button.
 */
data class MiniFrame(
    val title: String,
    val rows: List<String>,
    val tap: Int,
    val toggle: Int? = null,
    val toggleTo: Boolean = true,
    val menu: String? = null,
    val dialog: Boolean = false,
) {
    companion object {
        const val TAP_MENU = -1
        const val TAP_NONE = -2
    }
}

/**
 * Steps that happen outside Warden+. Self-drawn and plain, with Samsung's names (the user's
 * phone) and the stock Android name in [note].
 */
enum class SettingsGuide(
    val frames: List<MiniFrame>,
    val note: String?,
    /** Label of the button that opens the real page; null = nothing to open. */
    val openLabel: String?,
) {
    ACCESSIBILITY(
        listOf(
            MiniFrame("Settings", listOf("Connections", "Display", "Battery", "Apps", "Accessibility"), tap = 4),
            MiniFrame("Accessibility", listOf("Vision enhancements", "Hearing enhancements", "Interaction", "Installed apps"), tap = 3),
            MiniFrame("Installed apps", listOf("Warden+ App Blocker"), tap = 0),
            MiniFrame("Warden+ App Blocker", listOf("On", "Shortcut"), tap = 0, toggle = 0),
            MiniFrame("Allow full control?", listOf("Warden+ App Blocker can see the screen to block apps.", "Allow"), tap = 1, dialog = true),
        ),
        note = "Samsung: “Installed apps” · Pixel: “Downloaded apps”",
        openLabel = "Open Accessibility",
    ),
    RESTRICTED(
        listOf(
            MiniFrame("Settings", listOf("Connections", "Display", "Battery", "Apps", "Accessibility"), tap = 3),
            MiniFrame("Apps", listOf("Chrome", "Tailscale", "Warden+", "YouTube"), tap = 2),
            MiniFrame("App info", listOf("Warden+", "Notifications", "Permissions", "Battery"), tap = MiniFrame.TAP_MENU, menu = "Allow restricted settings"),
            MiniFrame("Accessibility", listOf("Installed apps", "Warden+ App Blocker"), tap = 1),
        ),
        note = "Then confirm with your PIN or fingerprint and turn Accessibility on.",
        openLabel = "Open app info",
    ),
    VPN_CONSENT(
        listOf(
            MiniFrame("Warden+", listOf("Blocking is OFF"), tap = 0, toggle = 0),
            MiniFrame("Connection request", listOf("Warden+ wants to set up a VPN connection.", "OK"), tap = 1, dialog = true),
            MiniFrame("Warden+", listOf("Blocking is ON"), tap = MiniFrame.TAP_NONE, toggle = 0, toggleTo = false),
        ),
        note = "The dialog closes by itself if another VPN is set to Always-on.",
        openLabel = null,
    ),
    OTHER_VPN(
        listOf(
            MiniFrame("Settings", listOf("Connections", "Sounds", "Display", "Battery", "Apps"), tap = 0),
            MiniFrame("Connections", listOf("Wi-Fi", "Bluetooth", "Data usage", "More connection settings"), tap = 3),
            MiniFrame("More connection settings", listOf("Nearby device scanning", "Printing", "VPN", "Private DNS"), tap = 2),
            MiniFrame("VPN", listOf("Tailscale  ⚙", "Warden+  ⚙"), tap = 0),
            MiniFrame("Tailscale", listOf("Always-on VPN", "Block connections without VPN"), tap = 0, toggle = 0, toggleTo = false),
        ),
        note = "Pixel: Settings › Network & internet › VPN. Also disconnect in the Tailscale app.",
        openLabel = "Open VPN settings",
    ),
    BATTERY(
        listOf(
            MiniFrame("Settings", listOf("Connections", "Display", "Battery", "Apps", "Accessibility"), tap = 2),
            MiniFrame("Battery", listOf("Power saving", "Background usage limits", "More battery settings"), tap = 1),
            MiniFrame("Background usage limits", listOf("Sleeping apps", "Deep sleeping apps", "Never sleeping apps"), tap = 2),
            MiniFrame("Never sleeping apps", listOf("+  Add apps"), tap = 0),
            MiniFrame("Add apps", listOf("Warden+", "Add"), tap = 0, toggle = 0),
        ),
        note = "Newer Samsung: “Never auto sleeping apps” · Pixel: App info › Battery › Unrestricted",
        openLabel = "Open battery settings",
    ),
    DEVICE_ADMIN(
        listOf(
            MiniFrame("Protection", listOf("Block protection", "PIN lock", "Uninstall protection", "Settings guard"), tap = 2),
            MiniFrame("Uninstall protection?", listOf("Android will ask to make Warden+ a device admin.", "Continue"), tap = 1, dialog = true),
            MiniFrame("Device admin app", listOf("Warden+ can't be uninstalled while this is on.", "Activate"), tap = 1, dialog = true),
        ),
        note = "Turn it off again under Settings › Protection when you want to uninstall.",
        openLabel = "Turn on now",
    ),
    ;

    /** "Settings › Accessibility › …", the short form of the way. */
    val path: String get() = frames.map { it.title }.distinct().joinToString(" › ")

    /** The animation as numbered sentences: read aloud by TalkBack and shown when animations are off. */
    fun instructions(): List<String> = frames.mapIndexed { i, f ->
        val text = when {
            f.tap == MiniFrame.TAP_NONE -> "Done: “${f.rows.first()}”."
            f.tap == MiniFrame.TAP_MENU -> "In “${f.title}”, tap ⋮ (top right), then “${f.menu}”."
            f.dialog -> "“${f.title}”: tap “${f.rows[f.tap]}”."
            f.toggle == f.tap -> "In “${f.title}”, switch “${f.rows[f.tap]}” ${if (f.toggleTo) "on" else "off"}."
            else -> "In “${f.title}”, tap “${f.rows[f.tap]}”."
        }
        "${i + 1}. $text"
    }
}

/** Opens the real page for [guide], with fallbacks for phones that lack the exact screen. */
fun openSettingsGuide(context: Context, guide: SettingsGuide): Boolean {
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val candidates = when (guide) {
        SettingsGuide.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        SettingsGuide.RESTRICTED -> listOf(appInfo)
        SettingsGuide.OTHER_VPN -> listOf(VpnController.settingsIntent(), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        SettingsGuide.BATTERY -> listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appInfo,
        )
        SettingsGuide.DEVICE_ADMIN -> listOf(AdminManager.enableIntent(context))
        SettingsGuide.VPN_CONSENT -> emptyList()
    }
    return candidates.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
}
