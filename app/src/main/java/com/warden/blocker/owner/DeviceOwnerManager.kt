package com.warden.blocker.owner

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.warden.blocker.system.AdminManager

/**
 * The only place that talks to Android's device-owner APIs. Warden+ becomes device owner by
 * `adb shell dpm set-device-owner` (see docs/DEVICE_OWNER.md); this applies the rules from an
 * [OwnerPlan] and removes them all again, see [removeDeviceOwner].
 */
object DeviceOwnerManager {
    private const val TAG = "WardenOwner"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** The `dpm` command that makes Warden+ device owner (receiver as declared in the manifest). */
    const val SET_OWNER_COMMAND = "adb shell dpm set-device-owner com.warden.blocker/.system.WardenDeviceAdminReceiver"

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /**
     * Applies [plan]. Each step runs on its own, so one refusal (e.g. Android won't suspend an app)
     * doesn't stop the others. Returns short, readable problems (empty when all went through).
     */
    fun apply(context: Context, plan: OwnerPlan): List<String> {
        if (!isDeviceOwner(context)) return listOf("Warden+ is not the device owner.")
        val dpm = dpm(context)
        val admin = AdminManager.component(context)
        val prefs = OwnerPrefs.get(context)
        val problems = mutableListOf<String>()

        fun step(what: String, block: () -> Unit) {
            runCatching(block).onFailure {
                Log.e(TAG, "$what failed", it)
                problems += "$what: ${it.message ?: it.javaClass.simpleName}"
            }
        }

        if (plan.unsuspend.isNotEmpty()) step("Un-pausing apps") {
            // Failures stay tracked and are retried next time (an uninstalled app just keeps failing).
            val failed = dpm.setPackagesSuspended(admin, plan.unsuspend.toTypedArray(), false).toSet()
            prefs.suspendedByUs = prefs.suspendedByUs - (plan.unsuspend - failed)
        }
        if (plan.suspend.isNotEmpty()) step("Pausing apps") {
            // Android refuses some apps by itself (launcher, keyboard, dialer, …) and returns them.
            val failed = dpm.setPackagesSuspended(admin, plan.suspend.toTypedArray(), true).toSet()
            prefs.suspendedByUs = prefs.suspendedByUs + (plan.suspend - failed)
            if (failed.isNotEmpty()) Log.w(TAG, "Android refused to suspend $failed")
        }
        plan.clearRestrictions.forEach { r -> step("Lifting $r") { dpm.clearUserRestriction(admin, r) } }
        plan.addRestrictions.forEach { r -> step("Setting $r") { dpm.addUserRestriction(admin, r) } }
        if (OwnerPolicy.LOCK_DATE_TIME.restriction in plan.addRestrictions) {
            // A locked clock should also be the right one: network time and time zone on (on
            // Android 11+ they are left on afterwards, Android's own default; the user may switch
            // them off again once the rule is lifted).
            if (Build.VERSION.SDK_INT >= 30) {
                step("Automatic time") { dpm.setAutoTimeEnabled(admin, true) }
                step("Automatic time zone") { dpm.setAutoTimeZoneEnabled(admin, true) }
            } else {
                @Suppress("DEPRECATION")
                step("Automatic time") { dpm.setAutoTimeRequired(admin, true) }
            }
        }
        if (OwnerPolicy.LOCK_DATE_TIME.restriction in plan.clearRestrictions && Build.VERSION.SDK_INT < 30) {
            // Android 8–10: "automatic time required" is a rule of its own — the exit lifts it too.
            @Suppress("DEPRECATION")
            step("Automatic time") { dpm.setAutoTimeRequired(admin, false) }
        }
        if (plan.privateDnsAutomatic && Build.VERSION.SDK_INT >= 29) step("Private DNS") {
            // A named provider (DNS over TLS) goes around the filter; "Automatic" doesn't. Lifting
            // the rule later leaves it on Automatic, Android's own default.
            if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            }
        }
        step("Always-on VPN") {
            val ours = dpm.getAlwaysOnVpnPackage(admin) == context.packageName
            when (OwnerPolicyPlanner.alwaysOnVpnAction(plan.alwaysOnVpn, ours, prefs.alwaysOnVpnSet)) {
                // No lockdown: without the VPN the internet still works (and Warden's updater too).
                AlwaysOnVpnAction.SET -> { dpm.setAlwaysOnVpnPackage(admin, context.packageName, false); prefs.alwaysOnVpnSet = true }
                AlwaysOnVpnAction.CLEAR -> { dpm.setAlwaysOnVpnPackage(admin, null, false); prefs.alwaysOnVpnSet = false }
                AlwaysOnVpnAction.NONE -> Unit
            }
        }
        step("Uninstall block") {
            if (dpm.isUninstallBlocked(admin, context.packageName) != plan.uninstallBlocked) {
                dpm.setUninstallBlocked(admin, context.packageName, plan.uninstallBlocked)
            }
        }
        return problems
    }

    /**
     * The emergency exit: lifts every rule, then stops being device owner. Android keeps nothing
     * of it afterwards (Warden+ is then an ordinary app again and can be uninstalled). Returns
     * the problems; empty = done.
     */
    fun removeDeviceOwner(context: Context): List<String> {
        if (!isDeviceOwner(context)) return emptyList()
        val prefs = OwnerPrefs.get(context)
        val problems = apply(context, OwnerPolicyPlanner.releaseAll(prefs.suspendedByUs)).toMutableList()
        runCatching {
            @Suppress("DEPRECATION") // still the way for a device owner to step down itself
            dpm(context).clearDeviceOwnerApp(context.packageName)
        }.onFailure {
            Log.e(TAG, "clearDeviceOwnerApp failed", it)
            problems += "Removing device owner: ${it.message ?: it.javaClass.simpleName}"
        }
        if (!isDeviceOwner(context)) prefs.clear()
        return problems
    }
}
