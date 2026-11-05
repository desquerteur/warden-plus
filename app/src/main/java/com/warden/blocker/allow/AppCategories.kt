package com.warden.blocker.allow

import android.content.pm.ApplicationInfo

/** The app categories that can be blocked as a whole. */
enum class AppCategory(val label: String, val singular: String) {
    GAME("Games", "game"),
    SOCIAL("Social", "social app"),
    VIDEO("Video", "video app"),
    AUDIO("Music & audio", "music app"),
    NEWS("News & magazines", "news app"),
    IMAGE("Photos & images", "photo app"),
    /** Apps that bring their own VPN (Tailscale, free VPNs …): switching one on pushes Warden's
     *  website filter out, since Android allows one VPN at a time. Detected by [VpnApps]. */
    VPN("VPN apps", "VPN app"),
}

/**
 * Reads an app's category offline from what the app itself declares — `android:appCategory`
 * (Android 8+) and the older `android:isGame` flag. No Play Store lookups: those are fragile
 * and against its terms. Apps that declare nothing (or wrongly) are corrected by hand through
 * [AllowConfig.categoryOverrides].
 */
object AppCategories {

    /** Override value: "don't treat this app as any category". */
    const val NONE = "NONE"

    /**
     * [category] is `ApplicationInfo.category`, [flags] is `ApplicationInfo.flags`. [isVpn]: the
     * app has a VPN service ([VpnApps]) — that wins, the VPN slot is what matters for blocking.
     */
    fun detect(category: Int, flags: Int, isVpn: Boolean = false): AppCategory? {
        if (isVpn) return AppCategory.VPN
        @Suppress("DEPRECATION")
        if (category == ApplicationInfo.CATEGORY_GAME || flags and ApplicationInfo.FLAG_IS_GAME != 0) {
            return AppCategory.GAME
        }
        return when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
            ApplicationInfo.CATEGORY_AUDIO -> AppCategory.AUDIO
            ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS
            ApplicationInfo.CATEGORY_IMAGE -> AppCategory.IMAGE
            else -> null // maps, productivity, accessibility, undefined: nothing to block
        }
    }

    /** The category that counts for [packageName]: the user's override, else what the app reports. */
    fun effective(packageName: String, detected: AppCategory?, overrides: Map<String, String>): AppCategory? {
        val override = overrides[packageName] ?: return detected
        if (override == NONE) return null
        return parse(override) ?: detected
    }

    fun parse(name: String): AppCategory? = AppCategory.entries.firstOrNull { it.name == name }

    // --- storage: overrides live in a string set as "package=CATEGORY" ---

    fun encodeOverrides(overrides: Map<String, String>): Set<String> =
        overrides.mapTo(HashSet()) { (pkg, value) -> "$pkg=$value" }

    fun decodeOverrides(stored: Set<String>): Map<String, String> = stored.mapNotNull { entry ->
        val pkg = entry.substringBefore('=', "")
        val value = entry.substringAfter('=', "")
        if (pkg.isBlank() || (value != NONE && parse(value) == null)) null else pkg to value
    }.toMap()
}
