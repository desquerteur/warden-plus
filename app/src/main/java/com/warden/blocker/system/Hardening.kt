package com.warden.blocker.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The Android settings that make Warden+ harder to switch off from outside (see the checklist
 * under Settings → Block protection). Each can only be checked and opened — the user decides.
 */
object Hardening {

    /** Not "optimized": battery savers don't put Warden+ to sleep. */
    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own "Let app always run in background?" question for Warden+. */
    @SuppressLint("BatteryLife") // a blocker that gets put to sleep stops blocking
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** App info for Warden+ (Battery → Unrestricted on Samsung and many others). */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Samsung adds its own "sleeping apps" on top of Android's battery optimization. */
    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
