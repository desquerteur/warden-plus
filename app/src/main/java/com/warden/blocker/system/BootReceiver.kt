package com.warden.blocker.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms the DNS filter after a reboot or an update of Warden itself (an update kills the
 * running VPN), if the user had blocking enabled. After an update it also checks whether
 * Android switched the accessibility service off, see [AccessibilityAlert].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val c = context.wardenContainer
                // Re-arm whenever the filter should run (main switch on or a focus session) —
                // also outside a schedule, so it switches on by itself when one starts.
                // Consent must have been granted previously.
                FilterKeeper.ensureRunning(context, notifyOnFailure = true)
                FilterKeeper.scheduleNext(context)
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    val warn = AccessibilityAlert.shouldWarn(
                        accessibilityOn = PermissionsHelper.isAccessibilityEnabled(context),
                        usedBefore = c.settings.accessibilityUsed.first(),
                        blockingOn = c.settings.masterEnabled.first(),
                        hasBlockedApps = c.repository.enabledApps().isNotEmpty(),
                    )
                    if (warn) AccessibilityAlert.notifyTurnedOff(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
