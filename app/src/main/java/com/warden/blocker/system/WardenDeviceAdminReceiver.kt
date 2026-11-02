package com.warden.blocker.system

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.warden.blocker.owner.OwnerSync
import com.warden.blocker.owner.OwnerSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Being an *active* device admin is what blocks the app from being uninstalled — Android
 * won't let you remove an app with an enabled admin until it's deactivated. Warden uses
 * this for one thing only: uninstall protection during strict mode. It requests no
 * password/wipe/lock policies.
 */
class WardenDeviceAdminReceiver : DeviceAdminReceiver() {
    /**
     * Becoming device owner (`dpm set-device-owner`) enables this admin too: apply the rules
     * chosen in Maximum protection right away, not only at the next change (D3).
     */
    override fun onEnabled(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { OwnerSync.syncNow(context) }
                OwnerSyncWorker.sync(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off removes Warden+'s uninstall protection. If you're trying to quit " +
            "during a block, consider waiting out the urge instead."
}
