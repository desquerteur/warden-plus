package com.warden.blocker.guard

/**
 * The system pages "Settings guard" watches — each one could switch Warden+ off or get around
 * it. The guard only warns and counts; the page stays usable. Everything else in Settings
 * (Wi-Fi, Bluetooth, Display, emergency calls, ...) is never even looked at as a target.
 */
enum class GuardTarget(val label: String) {
    /** Warden+'s App info and its sub-pages: Force stop, Clear storage, Uninstall, Battery. */
    APP_INFO("Warden+ app info"),

    /** The system "Do you want to uninstall this app?" dialog for Warden+. */
    UNINSTALL_DIALOG("Uninstalling Warden+"),

    /** Warden+'s page in Accessibility (where its switch is), and the "Stop Warden+?" dialog. */
    ACCESSIBILITY("Warden+ accessibility"),

    /** "Deactivate this device admin app" for Warden+. */
    DEVICE_ADMIN("Device admin for Warden+"),

    /** VPN settings, disconnecting Warden+'s VPN, or another app taking the VPN over. */
    VPN("VPN settings"),

    /** Date & time (only when the user ticked it): setting the clock by hand bends schedules. */
    DATE_TIME("Date & time"),

    /** Samsung Device care / Battery: putting Warden+ to sleep or stopping it. */
    SAMSUNG_DEVICE_CARE("Device care for Warden+"),

    /** The "Active apps" panel in the notification shade, which can stop Warden+. */
    ACTIVE_APPS("Active apps"),
}
