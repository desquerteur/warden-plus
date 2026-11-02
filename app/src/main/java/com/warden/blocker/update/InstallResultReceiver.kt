package com.warden.blocker.update

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
        UpdateInstaller.post(
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) InstallStatus.NeedsConfirmation(confirm)
                    else InstallStatus.Failed("Android didn't ask for confirmation. Try again.")
                }
                PackageInstaller.STATUS_SUCCESS -> InstallStatus.Succeeded
                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.Cancelled
                else -> InstallStatus.Failed(
                    UpdateInstaller.failureMessage(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                )
            }
        )
    }
}
