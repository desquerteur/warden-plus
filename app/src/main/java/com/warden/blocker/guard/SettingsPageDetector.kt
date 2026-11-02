package com.warden.blocker.guard

/**
 * Who "we" are on screen: Warden+'s package and the labels Android shows for it. The labels
 * come from the app's resources at runtime ([WARDEN] is the shipped English set for tests).
 */
data class SelfIdentity(
    val packageName: String,
    /** App name, e.g. "Warden+" (App info, uninstall dialog, VPN, device admin). */
    val appLabel: String,
    /** Accessibility service name, e.g. "Warden+ App Blocker". */
    val serviceLabel: String,
    /** Start of the accessibility description — only shown on the service's own page. */
    val serviceDescription: String,
    /** Start of the device-admin description — only shown on the admin activate/deactivate page. */
    val adminDescription: String,
) {
    internal val app = ScreenSnapshot.normalize(appLabel)
    internal val service = ScreenSnapshot.normalize(serviceLabel)
    internal val serviceDesc = ScreenSnapshot.normalize(serviceDescription).take(DESCRIPTION_PREFIX)
    internal val adminDesc = ScreenSnapshot.normalize(adminDescription).take(DESCRIPTION_PREFIX)

    /** Switch / stop / shortcut wording that names the service itself (AOSP and One UI, en/de). */
    internal val serviceControls: List<Phrase> = SERVICE_TEMPLATES.map { Phrase(it.format(service)) }

    companion object {
        private const val DESCRIPTION_PREFIX = 48

        private val SERVICE_TEMPLATES = listOf(
            "use %s", "%s verwenden", "%s nutzen",
            "%s shortcut", "%s-verknüpfung", "%s verknüpfung", "verknüpfung für %s", "kurzbefehl für %s",
            "stop %s", "%s beenden", "%s stoppen", "%s deaktivieren",
            "turn off %s", "%s ausschalten",
        )

        val WARDEN = SelfIdentity(
            packageName = "com.warden.blocker",
            appLabel = "Warden+",
            serviceLabel = "Warden+ App Blocker",
            serviceDescription = "Warden+ uses accessibility to detect when a blocked app or website is opened",
            adminDescription = "Prevents Warden+ from being uninstalled while blocking is active.",
        )
    }
}

/**
 * Decides whether a screen is one of the few system pages "Settings guard" warns about
 * ([GuardTarget]). Pure logic, unit-tested with real wording from AOSP (Pixel) and Samsung
 * One UI in English and German.
 *
 * Ground rules:
 *  - Only pages that name Warden+ (App info, uninstall, accessibility, device admin, Device
 *    care) or that change VPN (and date & time, if ticked) count. Never the Settings app as
 *    such: lists, search, Wi-Fi, Bluetooth, Display, emergency calls and so on are never a hit.
 *  - Doing something *for* protection is never a hit: activating device admin, Warden+'s own
 *    VPN request, adding Warden+ to "Never sleeping apps", installing an update.
 *  - When in doubt, no hit.
 */
class SettingsPageDetector(private val self: SelfIdentity) {

    /** @param watchDateTime the user ticked "Date & time" (off by default). */
    fun detect(screen: ScreenSnapshot, watchDateTime: Boolean = false): GuardTarget? {
        val pkg = screen.packageName
        if (pkg == self.packageName) return null
        if (pkg !in WATCHED_PACKAGES) return null
        if (isAlwaysOpen(screen)) return null
        return when (pkg) {
            SYSTEM_UI -> activeApps(screen)
            in INSTALLER_PACKAGES -> uninstallDialog(screen)
            VPN_DIALOGS -> vpnDialog(screen)
            in DEVICE_CARE_PACKAGES -> deviceCare(screen)
            else -> settingsPage(screen, watchDateTime) // a Settings package
        }
    }

    private fun selfVisible(s: ScreenSnapshot): Boolean =
        s.hasContaining(self.app) || s.hasContaining(self.packageName)

    /** Pages that are never a hit, whatever else is on them. */
    private fun isAlwaysOpen(s: ScreenSnapshot): Boolean =
        ALWAYS_OPEN_CLASSES.any { s.classLower.contains(it) } || s.hasAny(ALWAYS_OPEN_TEXTS)

    private fun settingsPage(s: ScreenSnapshot, watchDateTime: Boolean): GuardTarget? {
        val shown = selfVisible(s)
        // Our admin page: only deactivating counts (its text mentions "uninstalled", too).
        if (self.adminDesc.isNotEmpty() && s.hasContaining(self.adminDesc)) {
            return if (s.hasAny(DEACTIVATE)) GuardTarget.DEVICE_ADMIN else null
        }
        return when {
            isDeviceAdminDeactivate(s, shown) -> GuardTarget.DEVICE_ADMIN
            isAccessibilityPage(s) -> GuardTarget.ACCESSIBILITY
            isDateTime(s) -> if (watchDateTime) GuardTarget.DATE_TIME else null
            isVpnSettings(s) -> GuardTarget.VPN
            shown && s.hasAny(SLEEPING_APPS) && !s.hasAny(NEVER_SLEEPING) -> GuardTarget.SAMSUNG_DEVICE_CARE
            shown && isAppInfo(s) -> GuardTarget.APP_INFO
            else -> null
        }
    }

    // --- Warden+ App info ---
    private fun isAppInfo(s: ScreenSnapshot): Boolean =
        s.hasAny(FORCE_STOP) || s.hasAny(CLEAR_STORAGE) || s.hasAny(UNINSTALL) || s.hasAny(BACKGROUND_LIMITS)

    // --- Uninstall dialog (system package installer) ---
    private fun uninstallDialog(s: ScreenSnapshot): GuardTarget? =
        // Only uninstalling: installing or updating Warden+ uses the same app and stays open.
        if (selfVisible(s) && s.hasAny(UNINSTALL)) GuardTarget.UNINSTALL_DIALOG else null

    // --- Accessibility ---
    private fun isAccessibilityPage(s: ScreenSnapshot): Boolean {
        if (s.packageName !in ACCESSIBILITY_PACKAGES) return false
        // The service's own description is only shown on its own page (not in the list).
        if (self.serviceDesc.isNotEmpty() && s.hasContaining(self.serviceDesc)) return true
        // "Use Warden+ App Blocker", "Stop Warden+ App Blocker?", "Warden+ App Blocker shortcut" ...
        return s.hasAny(self.serviceControls)
    }

    // --- Device admin ---
    private fun isDeviceAdminDeactivate(s: ScreenSnapshot, shown: Boolean): Boolean {
        if (!s.hasAny(DEACTIVATE)) return false // activating is never a hit
        val adminContext = s.classLower.contains("deviceadmin") || s.hasAny(DEVICE_ADMIN_WORDS)
        return shown && adminContext
    }

    // --- VPN ---
    private fun isVpnSettings(s: ScreenSnapshot): Boolean =
        s.classLower.contains("vpn") || s.hasAny(VPN_PAGE)

    private fun vpnDialog(s: ScreenSnapshot): GuardTarget? {
        val shown = selfVisible(s)
        // "Warden+ wants to set up a VPN connection" is our own request: always allowed.
        if (s.hasAny(VPN_REQUEST)) return if (shown) null else GuardTarget.VPN
        // Tapping the VPN notification: "Disconnect" Warden+.
        if (shown && s.hasAny(VPN_DISCONNECT)) return GuardTarget.VPN
        return null
    }

    // --- Date & time ---
    private fun isDateTime(s: ScreenSnapshot): Boolean {
        val c = s.classLower
        if (c.contains("search")) return false // search results only list it
        if (DATE_TIME_CLASSES.any { c.contains(it) }) return true
        // The page itself shows several of these; the menu that leads there shows one at most.
        return s.countTextsMatching(DATE_TIME_PAGE) >= 2
    }

    // --- Samsung Device care (com.samsung.android.lool / sm) ---
    private fun deviceCare(s: ScreenSnapshot): GuardTarget? {
        if (!selfVisible(s)) return null
        if (s.hasAny(NEVER_SLEEPING)) return null // adding Warden+ there protects it
        return if (s.hasAny(SLEEPING_APPS) || s.hasAny(FORCE_STOP) || s.hasAny(STOP_BUTTON)) {
            GuardTarget.SAMSUNG_DEVICE_CARE
        } else {
            null
        }
    }

    // --- "Active apps" panel in the notification shade ---
    private fun activeApps(s: ScreenSnapshot): GuardTarget? {
        // Only the dialog, never the shade itself (Wi-Fi, Bluetooth, flashlight live there).
        if (!s.classLower.contains("dialog")) return null
        if (!selfVisible(s) || !s.hasAny(STOP_BUTTON)) return null
        val panel = s.hasAny(ACTIVE_APPS_TITLE) || s.hasViewIdContaining("fgs_manager")
        return if (panel) GuardTarget.ACTIVE_APPS else null
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val VPN_DIALOGS = "com.android.vpndialogs"

        val SETTINGS_PACKAGES = setOf("com.android.settings", "com.samsung.android.settings")

        /** One UI keeps Accessibility in its own app. */
        val ACCESSIBILITY_PACKAGES = SETTINGS_PACKAGES + "com.samsung.accessibility"

        val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )

        val DEVICE_CARE_PACKAGES = setOf("com.samsung.android.lool", "com.samsung.android.sm", "com.samsung.android.sm_cn")

        /** Only events from these packages are looked at all; everything else costs nothing. */
        val WATCHED_PACKAGES: Set<String> =
            ACCESSIBILITY_PACKAGES + INSTALLER_PACKAGES + DEVICE_CARE_PACKAGES + SYSTEM_UI + VPN_DIALOGS

        fun isWatchedPackage(pkg: String): Boolean = pkg in WATCHED_PACKAGES

        private val ALWAYS_OPEN_CLASSES = listOf(
            "wifi", "bluetooth", "display", "sound", "emergency", "sos", "hotspot", "tether",
            "mobilenetwork", "simsettings", "notificationshade",
        )
        private val ALWAYS_OPEN_TEXTS = containing(
            "emergency call", "emergency sos", "notruf", "notfall-sos", "notfallanruf",
        )

        // App info buttons and dialogs.
        private val FORCE_STOP = containing(
            "force stop", "beenden erzwingen", "stopp erzwingen", "stoppen erzwingen",
            "erzwungener stopp", "zwangsweise beenden",
        )
        private val CLEAR_STORAGE = containing(
            "clear storage", "clear data", "clear all data", "delete app data",
            "speicher löschen", "speicherinhalt löschen", "speicher leeren", "daten löschen", "alle daten löschen",
            "app-daten löschen",
        )
        private val UNINSTALL = containing("uninstall", "deinstallier")

        /** App info → Battery: "Optimized" / "Restricted" lets Android stop Warden+ in the background. */
        private val BACKGROUND_LIMITS = exactly(
            "unrestricted", "optimized", "restricted", "uneingeschränkt", "optimiert", "eingeschränkt",
        ) + containing(
            "allow background activity", "allow background usage", "hintergrundaktivität zulassen",
            "hintergrundnutzung zulassen", "put app to sleep", "app in den standby",
        )

        private val SLEEPING_APPS = containing(
            "sleeping apps", "deep sleeping", "put to sleep", "schlafende apps", "tief schlafend",
            "in den standby versetzen", "apps im standby", "standby-apps",
        )
        private val NEVER_SLEEPING = containing(
            "never sleeping", "never auto sleeping", "nie schlafend", "niemals schlafend", "nie im standby",
            "nie in den standby", "apps, die nie",
        )
        private val STOP_BUTTON = exactly("stop", "stopp", "stoppen", "beenden", "app beenden")

        private val DEACTIVATE = containing("deactivate", "deaktivieren")
        private val DEVICE_ADMIN_WORDS = containing(
            "device admin", "admin app", "geräteadministrator", "geräte-administrator", "geräteadmin", "administrator",
        )

        private val VPN_PAGE = containing(
            "add vpn", "vpn hinzufügen", "vpn-profil hinzufügen", "always-on vpn", "always on vpn",
            "durchgehend aktives vpn", "immer aktives vpn", "immer aktiviertes vpn", "dauerhaft aktives vpn",
            "block connections without vpn", "verbindungen ohne vpn blockieren", "forget vpn", "vpn entfernen",
            "vpn vergessen", "vpn-profil entfernen",
        )
        private val VPN_REQUEST = containing(
            "connection request", "verbindungsanfrage", "wants to set up a vpn", "vpn-verbindung herstellen",
            "vpn-verbindung einrichten", "möchte eine vpn-verbindung",
        )
        private val VPN_DISCONNECT = containing("disconnect", "trennen", "verbindung beenden")

        private val DATE_TIME_CLASSES = listOf("datetime", "zonepicker", "timezone", "date_time")
        private val DATE_TIME_PAGE = containing(
            // AOSP (Android 12-15)
            "set time automatically", "set time zone automatically", "use network-provided time",
            "use locale default", "uhrzeit automatisch einstellen", "zeitzone automatisch einstellen",
            "netzwerkzeit verwenden",
            // Samsung One UI
            "automatic date and time", "automatic time zone", "select time zone", "set date", "set time",
            "datum und uhrzeit automatisch", "automatisches datum", "automatische zeitzone", "zeitzone auswählen",
            "datum einstellen", "datum festlegen", "uhrzeit einstellen", "uhrzeit festlegen",
            // Both
            "use 24-hour format", "24-stunden-format",
        )

        private val ACTIVE_APPS_TITLE = exactly("active apps", "aktive apps")
    }
}
