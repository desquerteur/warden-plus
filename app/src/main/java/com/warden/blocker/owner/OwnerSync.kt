package com.warden.blocker.owner

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.warden.blocker.AppContainer
import com.warden.blocker.block.AccessDecision
import com.warden.blocker.block.BlockStateClock
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps the device-owner rules in step with the blocking state: on every blocklist / schedule /
 * switch / focus change, at every minute boundary (schedules start and end on their own) and,
 * as a safety net when the process was gone, every 15 minutes via WorkManager. Does nothing
 * while Warden+ isn't device owner.
 *
 * Wiring: one call, `OwnerSync.start(this)` in `WardenApp.onCreate()`.
 */
object OwnerSync {
    private const val TAG = "WardenOwner"
    private val lock = Mutex()
    @Volatile private var started = false

    fun start(app: Application) {
        if (started) return
        started = true
        OwnerSyncWorker.sync(app)
        val c = app.wardenContainer
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            merge(
                c.repository.items.unit(),
                c.repository.schedules.unit(),
                c.settings.masterEnabled.unit(),
                c.settings.alwaysOn.unit(),
                c.settings.focusEndsAt.unit(),
                c.allowStore.config.unit(),
                OwnerPrefs.get(app).policies.unit(),
                minuteTicks(app),
            ).conflate().collect {
                runCatching { syncNow(app) }.onFailure { Log.e(TAG, "Sync failed", it) }
            }
        }
    }

    /**
     * Brings the rules to the wanted state now. Returns the problems (empty = fine), or null
     * when Warden+ isn't device owner.
     */
    suspend fun syncNow(context: Context): List<String>? = lock.withLock {
        if (!DeviceOwnerManager.isDeviceOwner(context)) return null
        val c = context.wardenContainer
        val prefs = OwnerPrefs.get(context)
        val enabled = prefs.policies.value
        val decisions: Map<String, AccessDecision> =
            if (OwnerPolicy.SUSPEND_BLOCKED_APPS in enabled) decisions(c) else emptyMap()
        val suspendedByUs = prefs.suspendedByUs
        val protected = ProtectedPackages.collect(context, decisions.keys + suspendedByUs)
        val want = OwnerPolicyPlanner.suspendTargets(decisions, protected)
        DeviceOwnerManager.apply(context, OwnerPolicyPlanner.plan(enabled, want, suspendedByUs, protected))
    }

    /**
     * The same decision the accessibility service makes when an app is opened, for every app that
     * can be blocked right now: blocklist entries (within their "from … / until …" span) and, with
     * "Allow only" or blocked categories, every launchable app.
     */
    private suspend fun decisions(c: AppContainer): Map<String, AccessDecision> {
        val now = System.currentTimeMillis()
        val items = c.repository.enabledApps().filter { TimeWindows.inEffect(it, now) }.associateBy { it.value }
        val byAllow = if (c.blockEngine.isBlockingActiveNow()) {
            val candidates = if (c.allowStore.current().affectsApps) c.installedApps.launchablePackages() + items.keys else items.keys
            c.allowGate.blockedAmong(candidates, items)
        } else {
            emptySet()
        }
        // Apps stopped by "Allow only" or a category have no entry of their own; a stand-in is enough here.
        return byAllow.associateWith { AccessDecision.HardBlock(BlockedItem(type = BlockType.APP, value = it, label = it)) } +
            (items.keys - byAllow).associateWith { c.accessController.decideForPackage(it) }
    }

    private fun <T> Flow<T>.unit(): Flow<Unit> = map { }

    /** Ticks at every minute boundary (and a focus end); also drives gray mode, see GrayMode. */
    internal fun minuteTicks(context: Context): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val focusEndsAt = context.wardenContainer.settings.focusEndsAt.first()
            delay(BlockStateClock.delayUntilNextCheck(System.currentTimeMillis(), focusEndsAt))
        }
    }
}

/** Safety net: re-applies the rules every 15 minutes (also after the process was killed). */
class OwnerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        OwnerSync.syncNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "owner_sync"

        /** Scheduled only while Warden+ is device owner. */
        fun sync(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (DeviceOwnerManager.isDeviceOwner(context)) {
                val request = PeriodicWorkRequestBuilder<OwnerSyncWorker>(15, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(UNIQUE)
            }
        }
    }
}
