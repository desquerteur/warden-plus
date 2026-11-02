package com.warden.blocker.hosts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.warden.blocker.wardenContainer
import java.util.concurrent.TimeUnit

/** Re-downloads every URL blocklist once a week, only when the phone is online. */
class HostsRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ok = applicationContext.wardenContainer.hostsLists.refreshAllUrls()
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE = "hosts_lists_refresh"

        /** Keep the weekly job scheduled exactly while at least one URL list exists. */
        fun sync(context: Context, hasUrlSources: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (!hasUrlSources) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<HostsRefreshWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
