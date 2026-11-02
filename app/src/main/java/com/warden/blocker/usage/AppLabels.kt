package com.warden.blocker.usage

/**
 * Readable name for a package whose label Android won't give us (hidden by package visibility,
 * uninstalled, or no label at all): "com.instagram.android" -> "Instagram". Never the raw
 * package name, which the stats list would just cut off.
 */
internal fun fallbackAppLabel(packageName: String): String {
    KNOWN[packageName]?.let { return it }
    val parts = packageName.split('.').filter { it.isNotBlank() }
    val name = parts.lastOrNull { it.lowercase() !in GENERIC } ?: parts.lastOrNull() ?: return packageName
    return name.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

/** Common system apps without a launcher icon, whose last segment reads badly. */
private val KNOWN = mapOf(
    "com.google.android.googlequicksearchbox" to "Google",
    "com.google.android.gms" to "Google Play services",
    "com.google.android.gsf" to "Google Services Framework",
    "com.android.vending" to "Play Store",
    "com.android.systemui" to "System UI",
)

/** Trailing segments that say nothing about the app ("com.instagram.android"). */
private val GENERIC = setOf("android", "app", "apps", "mobile", "client", "phone", "free", "pro")
