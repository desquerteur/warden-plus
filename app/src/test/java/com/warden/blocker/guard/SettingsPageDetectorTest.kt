package com.warden.blocker.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPageDetectorTest {

    private val detector = SettingsPageDetector(SelfIdentity.WARDEN)

    private fun settings(vararg texts: String, cls: String = "com.android.settings.SubSettings", title: String? = null) =
        ScreenSnapshot("com.android.settings", cls, title, texts.toList())

    private fun detect(s: ScreenSnapshot, dateTime: Boolean = false) = detector.detect(s, dateTime)

    // --- App info ---
    @Test fun appInfoAosp() = assertEquals(
        GuardTarget.APP_INFO,
        detect(settings("App info", "Warden+", "Installed", "Open", "Uninstall", "Force stop", "Notifications", "Permissions",
            cls = "com.android.settings.applications.InstalledAppDetailsTop")),
    )

    @Test fun appInfoSamsungGerman() = assertEquals(
        GuardTarget.APP_INFO,
        detect(settings("App-Info", "Warden+", "Version 0.2.4", "Öffnen", "Deinstallieren", "Beenden erzwingen")),
    )

    @Test fun appInfoStorageSubpage() = assertEquals(
        GuardTarget.APP_INFO,
        detect(settings("Speicher", "Warden+", "App 12 MB", "Daten löschen", "Cache leeren")),
    )

    @Test fun appInfoBatterySubpage() = assertEquals(
        GuardTarget.APP_INFO,
        detect(settings("Battery", "Warden+", "Unrestricted", "Optimized", "Restricted")),
    )

    @Test fun otherAppsInfoIsNoHit() =
        assertNull(detect(settings("App info", "Chrome", "Open", "Uninstall", "Force stop")))

    @Test fun appsListWithWardenIsNoHit() =
        assertNull(detect(settings("Apps", "Calculator", "Chrome", "Warden+", "YouTube", "12 MB")))

    @Test fun batteryOptimizationListNotOptimizedIsNoHit() =
        assertNull(detect(settings("Optimize battery usage", "Warden+", "Not optimized", "Chrome")))

    // --- Uninstall dialog ---
    @Test fun uninstallDialogEnglish() = assertEquals(
        GuardTarget.UNINSTALL_DIALOG,
        detector.detect(ScreenSnapshot(
            "com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity", null,
            listOf("Warden+", "Do you want to uninstall this app?", "Cancel", "OK"),
        )),
    )

    @Test fun uninstallDialogGerman() = assertEquals(
        GuardTarget.UNINSTALL_DIALOG,
        detector.detect(ScreenSnapshot(
            "com.android.packageinstaller", null, null,
            listOf("Warden+", "Möchtest du diese App deinstallieren?", "Abbrechen", "OK"),
        )),
    )

    @Test fun updateDialogIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot(
            "com.google.android.packageinstaller", null, null,
            listOf("Warden+", "Do you want to update this app?", "Cancel", "Update"),
        )),
    )

    @Test fun uninstallOtherAppIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot(
            "com.google.android.packageinstaller", null, null,
            listOf("Chrome", "Do you want to uninstall this app?", "Cancel", "OK"),
        )),
    )

    // --- Accessibility ---
    @Test fun accessibilityDetailAosp() = assertEquals(
        GuardTarget.ACCESSIBILITY,
        detect(settings("Warden+ App Blocker", "Use Warden+ App Blocker", "Warden+ App Blocker shortcut", "Settings")),
    )

    @Test fun accessibilityDetailSamsungByDescription() = assertEquals(
        GuardTarget.ACCESSIBILITY,
        detector.detect(ScreenSnapshot(
            "com.samsung.accessibility", null, null,
            listOf(
                "Warden+ App Blocker", "Ein",
                "Warden+ uses accessibility to detect when a blocked app or website is opened so it can show the block screen.",
            ),
        )),
    )

    @Test fun accessibilityStopDialogGerman() = assertEquals(
        GuardTarget.ACCESSIBILITY,
        detect(settings("Warden+ App Blocker beenden?", "Abbrechen", "Beenden")),
    )

    @Test fun accessibilityMainPageIsNoHit() = assertNull(
        detect(settings(
            "Accessibility", "Accessibility shortcuts", "Downloaded apps", "Warden+ App Blocker", "On",
            "TalkBack", "Off", "Select to Speak shortcut",
        )),
    )

    @Test fun samsungInstalledAppsListIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot("com.samsung.accessibility", null, null, listOf("Installierte Apps", "Warden+ App Blocker", "Ein"))),
    )

    // --- Device admin ---
    @Test fun deactivateDeviceAdmin() = assertEquals(
        GuardTarget.DEVICE_ADMIN,
        detect(settings(
            "Device admin app", "Warden+", "Prevents Warden+ from being uninstalled while blocking is active.",
            "Deactivate this device admin app", "Cancel", cls = "com.android.settings.DeviceAdminAdd",
        )),
    )

    @Test fun deactivateDeviceAdminGerman() = assertEquals(
        GuardTarget.DEVICE_ADMIN,
        detect(settings("Geräteadministrator-App", "Warden+", "Diese Geräteadministrator-App deaktivieren")),
    )

    @Test fun activatingDeviceAdminIsNoHit() = assertNull(
        detect(settings(
            "Device admin app", "Warden+", "Prevents Warden+ from being uninstalled while blocking is active.",
            "Activate this device admin app", "Cancel", cls = "com.android.settings.DeviceAdminAdd",
        )),
    )

    // --- VPN ---
    @Test fun vpnSettingsPage() = assertEquals(
        GuardTarget.VPN,
        detect(settings("VPN", "Warden+", "Connected", "Add VPN profile")),
    )

    @Test fun vpnAppDetailGerman() = assertEquals(
        GuardTarget.VPN,
        detect(settings("Warden+", "Durchgehend aktives VPN", "Verbindungen ohne VPN blockieren", "VPN entfernen")),
    )

    @Test fun connectionsMenuWithVpnItemIsNoHit() = assertNull(
        detect(settings("More connection settings", "Nearby device scanning", "Printing", "VPN", "Private DNS", "Ethernet")),
    )

    @Test fun ownVpnRequestIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot(
            SettingsPageDetector.VPN_DIALOGS, "com.android.vpndialogs.ConfirmDialog", null,
            listOf("Connection request", "Warden+ wants to set up a VPN connection that allows it to monitor network traffic.", "Cancel", "OK"),
        )),
    )

    @Test fun otherAppsVpnRequest() = assertEquals(
        GuardTarget.VPN,
        detector.detect(ScreenSnapshot(
            SettingsPageDetector.VPN_DIALOGS, "com.android.vpndialogs.ConfirmDialog", null,
            listOf("Verbindungsanfrage", "Other VPN möchte eine VPN-Verbindung herstellen.", "Abbrechen", "OK"),
        )),
    )

    @Test fun disconnectWardenVpn() = assertEquals(
        GuardTarget.VPN,
        detector.detect(ScreenSnapshot(SettingsPageDetector.VPN_DIALOGS, null, null, listOf("Warden+", "Connected", "Disconnect", "Cancel"))),
    )

    // --- Date & time (opt-in) ---
    private val samsungDateTime = settings(
        "Date and time", "Automatic date and time", "Set date", "Set time", "Automatic time zone", "Use 24-hour format",
    )

    @Test fun dateTimeOnlyWhenTicked() {
        assertNull(detect(samsungDateTime))
        assertEquals(GuardTarget.DATE_TIME, detect(samsungDateTime, dateTime = true))
    }

    @Test fun dateTimeAospGermanByClass() = assertEquals(
        GuardTarget.DATE_TIME,
        detect(settings("Datum & Uhrzeit", cls = "com.android.settings.Settings\$DateTimeSettingsActivity"), dateTime = true),
    )

    @Test fun generalManagementMenuIsNoHit() = assertNull(
        detect(settings("General management", "Language", "Keyboard", "Date and time", "Reset"), dateTime = true),
    )

    @Test fun oneTextCountsOnce() =
        assertNull(detect(settings("System", "Set time automatically"), dateTime = true))

    // --- Samsung Device care ---
    @Test fun deepSleepingPickerWithWarden() = assertEquals(
        GuardTarget.SAMSUNG_DEVICE_CARE,
        detector.detect(ScreenSnapshot("com.samsung.android.lool", null, null, listOf("Add to Deep sleeping apps", "Chrome", "Warden+"))),
    )

    @Test fun neverSleepingAppsIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot("com.samsung.android.lool", null, null, listOf("Never sleeping apps", "Warden+", "Add"))),
    )

    @Test fun deviceCareHomeIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot("com.samsung.android.lool", null, null, listOf("Device care", "Battery", "Storage", "Memory"))),
    )

    // --- Active apps ---
    @Test fun activeAppsDialog() = assertEquals(
        GuardTarget.ACTIVE_APPS,
        detector.detect(ScreenSnapshot(
            "com.android.systemui", "com.android.systemui.statusbar.phone.SystemUIDialog", null,
            listOf("Active apps", "Warden+", "Stop", "Done"),
        )),
    )

    @Test fun notificationShadeIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot(
            "com.android.systemui", "android.widget.FrameLayout", null,
            listOf("Wi-Fi", "Bluetooth", "Warden+ is protecting your focus", "Stop", "2 active apps"),
        )),
    )

    // --- Never a hit ---
    @Test fun wifiPageIsNeverAHit() = assertNull(
        detect(settings("Wi-Fi", "Warden+", "Force stop", cls = "com.android.settings.wifi.WifiSettings")),
    )

    @Test fun emergencyTextIsNeverAHit() = assertNull(detect(settings("Emergency SOS", "Warden+", "Force stop")))

    @Test fun ownAppIsNeverAHit() = assertNull(
        detector.detect(ScreenSnapshot("com.warden.blocker", null, null, listOf("Warden+", "Force stop"))),
    )

    @Test fun unwatchedPackageIsNoHit() = assertNull(
        detector.detect(ScreenSnapshot("com.sec.android.app.launcher", null, null, listOf("Warden+", "Uninstall"))),
    )
}
