package com.warden.blocker.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warden.blocker.stats.BlockTotals
import com.warden.blocker.stats.PeriodWindow
import com.warden.blocker.stats.StatsPeriod
import com.warden.blocker.stats.StatsRecorder
import com.warden.blocker.stats.UsageAggregator
import com.warden.blocker.stats.UsageEventsSource
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.usage.UsageStatsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** One app in the list: totals, share of the period, trend and its own per-bucket history. */
data class AppRow(
    val packageName: String,
    val label: String,
    val millis: Long,
    val share: Float,
    val changePct: Int?,
    val perBucket: LongArray,
)

/** A donut slice with its display label; [packageName] is null for "Other". */
data class SliceRow(val packageName: String?, val label: String, val millis: Long, val fraction: Float)

data class UsageData(
    val window: PeriodWindow,
    val now: Long,
    val totalMillis: Long,
    val changePct: Int?,
    /** Device unlocks, or null on Android < 9 where the OS does not report them. */
    val unlocks: Int?,
    val unlocksChangePct: Int?,
    val opens: Int,
    val buckets: LongArray,
    /** Index of the first bucket Android still has history for (older ones show as "no data"). */
    val firstBucketWithData: Int,
    val slices: List<SliceRow>,
    val apps: List<AppRow>,
    /** True when Android no longer keeps the start of this period, so totals are partial. */
    val partialHistory: Boolean,
)

sealed interface UsageState {
    data object Loading : UsageState
    data object NoPermission : UsageState
    data class Ready(val data: UsageData) : UsageState
}

data class BlockStats(val current: BlockTotals, val changePct: Int?)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.TODAY,
    val usage: UsageState = UsageState.Loading,
    val blocks: BlockStats? = null,
    val streak: Int = 0,
    val longestStreak: Int = 0,
)

/** Loads the stats dashboard off the main thread; keeps the last result per period while refreshing. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val period = MutableStateFlow(StatsPeriod.TODAY)
    private val refreshTick = MutableStateFlow(0)
    private val cache = HashMap<StatsPeriod, UsageState>()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val usage = combine(period, refreshTick) { p, _ -> p }
        .transformLatest { p ->
            emit(cache[p] ?: UsageState.Loading)
            val fresh = withContext(Dispatchers.IO) { load(p) }
            cache[p] = fresh
            emit(fresh)
        }

    private val blocks = combine(StatsRecorder.get(app).log, period) { log, p ->
        val w = UsageAggregator.windowFor(p, System.currentTimeMillis(), zone)
        val current = log.totals(UsageAggregator.epochDays(w.current, zone))
        val previous = log.totals(UsageAggregator.epochDays(w.previous, zone))
        BlockStats(current, UsageAggregator.percentChange(current.total.toLong(), previous.total.toLong()))
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<StatsUiState> = combine(
        period, usage, blocks,
        app.wardenContainer.settings.currentStreak,
        app.wardenContainer.settings.longestStreak,
    ) { p, u, b, streak, longest -> StatsUiState(p, u, b, streak, longest) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun selectPeriod(p: StatsPeriod) { period.value = p }

    /** Re-read usage (e.g. after returning from the usage-access settings screen). */
    fun refresh() { refreshTick.value++ }

    private fun load(p: StatsPeriod): UsageState {
        val ctx = getApplication<Application>()
        if (!UsageStatsHelper.hasPermission(ctx)) return UsageState.NoPermission
        val now = System.currentTimeMillis()
        val w = UsageAggregator.windowFor(p, now, zone)
        // Start a little early so an app already open at the period start is attributed correctly.
        val result = UsageEventsSource.query(ctx, w.previous.start - PRE_ROLL_MS, now)
        val sessions = UsageAggregator.sessions(result.events, now, UsageEventsSource.excludedPackages(ctx))

        // Android keeps detailed events for roughly 10 days; anything earlier is simply missing.
        val firstAt = result.firstEventAt ?: now
        fun covers(start: Long) = p == StatsPeriod.TODAY || firstAt <= start + DAY_MS
        val prevKnown = covers(w.previous.start)

        val total = UsageAggregator.total(sessions, w.current)
        val prevTotal = if (prevKnown) UsageAggregator.total(sessions, w.previous) else null
        val unlocks = if (UsageEventsSource.supportsUnlocks) UsageAggregator.unlocks(result.events, w.current) else null
        val prevUnlocks = if (unlocks != null && prevKnown) UsageAggregator.unlocks(result.events, w.previous) else null

        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) { AppsHelper.labelFor(ctx, pkg) }

        val currentApps = UsageAggregator.perApp(sessions, w.current)
        val prevByApp = if (prevKnown) UsageAggregator.perApp(sessions, w.previous).associate { it.packageName to it.millis } else emptyMap()
        val byPkg = sessions.groupBy { it.packageName }
        val apps = currentApps.take(MAX_APPS).map { a ->
            AppRow(
                packageName = a.packageName,
                label = label(a.packageName),
                millis = a.millis,
                share = if (total > 0) a.millis.toFloat() / total else 0f,
                changePct = if (prevKnown) UsageAggregator.percentChange(a.millis, prevByApp[a.packageName] ?: 0L) else null,
                perBucket = UsageAggregator.perBucket(byPkg[a.packageName].orEmpty(), w.buckets),
            )
        }
        val slices = UsageAggregator.topSlices(currentApps, TOP_N).map {
            SliceRow(it.packageName, it.packageName?.let(::label) ?: "Other", it.millis, it.fraction)
        }
        val firstBucket = w.buckets.indexOfFirst { it.end > firstAt }.coerceAtLeast(0)

        return UsageState.Ready(
            UsageData(
                window = w,
                now = now,
                totalMillis = total,
                changePct = UsageAggregator.percentChange(total, prevTotal),
                unlocks = unlocks,
                unlocksChangePct = UsageAggregator.percentChange((unlocks ?: 0).toLong(), prevUnlocks?.toLong()),
                opens = UsageAggregator.opens(sessions, w.current),
                buckets = UsageAggregator.perBucket(sessions, w.buckets),
                firstBucketWithData = if (p == StatsPeriod.TODAY) 0 else firstBucket,
                slices = slices,
                apps = apps,
                partialHistory = !covers(w.current.start),
            ),
        )
    }

    private companion object {
        const val PRE_ROLL_MS = 2 * 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val TOP_N = 5
        const val MAX_APPS = 50
    }
}
