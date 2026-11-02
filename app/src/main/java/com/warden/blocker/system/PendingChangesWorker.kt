package com.warden.blocker.system

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.warden.blocker.wardenContainer
import java.util.concurrent.TimeUnit

/**
 * Applies weakenings queued by Block protection's "Delay" mode once they are due — also with
 * the app closed. One unique job, always aimed at the earliest due change.
 */
class PendingChangesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.wardenContainer.protection.processDue()
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "pending_changes"

        fun schedule(context: Context, at: Long) {
            val request = OneTimeWorkRequestBuilder<PendingChangesWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        /** Aims the job at [nextAt], or cancels it when nothing is queued. */
        fun reschedule(context: Context, nextAt: Long?) {
            if (nextAt == null) WorkManager.getInstance(context).cancelUniqueWork(UNIQUE) else schedule(context, nextAt)
        }
    }
}
