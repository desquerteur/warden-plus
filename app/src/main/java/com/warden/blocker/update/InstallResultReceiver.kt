package com.warden.blocker.update

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Receives the result of the PackageInstaller session committed by [UpdateInstaller.install].
 * Not exported: only the PendingIntent created by Warden can reach it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            when {
                confirm == null -> UpdateInstaller.post(InstallStatus.Failed("Android didn't ask for confirmation. Try again."))
                launchNow(context, confirm) -> UpdateInstaller.clearStatus()
                // In the background Android blocks the start; the settings screen shows it later.
                else -> UpdateInstaller.post(InstallStatus.NeedsConfirmation(confirm))
            }
            return
        }
        UpdateInstaller.post(
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> InstallStatus.Succeeded
                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.Cancelled
                else -> InstallStatus.Failed(
                    UpdateInstaller.failureMessage(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                )
            }
        )
    }

    /** Opens Android's install confirmation right away while Warden is on screen (on any tab). */
    private fun launchNow(context: Context, confirm: Intent): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        if (!canStartActivityNow(info.importance)) return false
        return try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) { // ActivityNotFoundException, SecurityException
            false
        }
    }

    companion object {
        /** Only a visible foreground app may start an activity from a receiver. */
        internal fun canStartActivityNow(importance: Int): Boolean =
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
