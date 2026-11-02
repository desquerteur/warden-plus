package com.warden.blocker.allow

/**
 * Apps "Allow only" never blocks, on top of [com.warden.blocker.util.CriticalApps] (Settings,
 * launcher, Warden+ …) and what [InstalledAppsInfo] finds at runtime (default phone app,
 * keyboards, default SMS/camera app when switched on). Blocking these would cut off calls,
 * emergency calls or alarms.
 */
object AlwaysAllowed {

    val PACKAGES: Set<String> = setOf(
        // Phone and in-call screens (Samsung, Google, AOSP).
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.android.dialer",
        // Emergency / SOS.
        "com.samsung.android.emergency",
        "com.android.emergency",
        // Clock: a ringing alarm must never be hidden behind a block screen.
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.deskclock",
    )

    /** Shown on top of the "Allowed apps" picker when installed — the usual essentials. */
    val SUGGESTIONS: List<String> = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.samsung.android.app.contacts",
        "com.google.android.contacts",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.google.android.calculator",
        "com.sec.android.app.popupcalculator",
        "com.samsung.android.app.notes",
        "com.google.android.keep",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
    )
}
