package com.warden.blocker.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.warden.blocker.R
import java.util.concurrent.TimeUnit

/** Once a day: looks for a new GitHub release and posts a notification for it (once per version). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = UpdatePrefs.get(applicationContext)
        if (!prefs.autoCheck.value) return Result.success()
        return when (val result = UpdateChecker.check()) {
            is CheckResult.UpdateAvailable -> {
                val version = result.release.version.toString()
                if (prefs.lastNotifiedVersion != version && notify(version)) {
                    prefs.lastNotifiedVersion = version
                }
                Result.success()
            }
            is CheckResult.Failed -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private fun notify(version: String): Boolean {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_tile)
            .setContentTitle("Warden+ $version is available")
            .setContentText("Open Settings → Updates to install it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        private const val UNIQUE = "update_check"
        private const val CHANNEL_ID = "warden_updates"
        private const val NOTIFICATION_ID = 4201

        /** Starts or stops the daily check to match [UpdatePrefs.autoCheck]. Safe to call often. */
        fun sync(context: Context) {
            val work = WorkManager.getInstance(context)
            if (UpdatePrefs.get(context).autoCheck.value) {
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()
                work.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                work.cancelUniqueWork(UNIQUE)
            }
        }
    }
}
