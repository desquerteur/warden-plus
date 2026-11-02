package com.warden.blocker.ui.wizard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.ui.WardenViewModel
import com.warden.blocker.util.PermissionsHelper

/** What Android itself says is set up, for the wizard pages that can be checked. */
object WizardChecks {

    fun systemDone(context: Context): Set<WizardStep> = buildSet {
        if (PermissionsHelper.hasNotifications(context)) add(WizardStep.NOTIFICATIONS)
        if (PermissionsHelper.isAccessibilityEnabled(context)) add(WizardStep.APP_BLOCKING)
        // null = the VPN is already allowed.
        if (runCatching { VpnService.prepare(context) == null }.getOrDefault(false)) add(WizardStep.WEBSITE_BLOCKING)
        if (PermissionsHelper.hasUsageAccess(context)) add(WizardStep.USAGE)
        if (ignoresBatteryOptimizations(context)) add(WizardStep.BATTERY)
    }

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's "Let app always run in background?" dialog. */
    @SuppressLint("BatteryLife") // a blocker that stops in the background doesn't block
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    /** First start after installing (not after an update): only then the wizard opens by itself. */
    fun freshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)
}

/** Done pages, re-checked whenever the app comes back to the front (e.g. from Android's settings). */
@Composable
fun rememberWizardDone(vm: WardenViewModel, state: WizardState?): Set<WizardStep> {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val system = remember(resumes) { WizardChecks.systemDone(context) }
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val hostsLists by vm.hostsLists.collectAsStateWithLifecycle()
    return buildSet {
        addAll(system)
        if (hasPin || state?.protectionChosen == true) add(WizardStep.PROTECTION)
        if (items.isNotEmpty() || hostsLists.isNotEmpty()) add(WizardStep.FIRST_BLOCKS)
    }
}
