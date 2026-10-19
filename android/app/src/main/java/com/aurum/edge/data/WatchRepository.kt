package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.MarketHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WatchState(
    val quotes: Map<String, Map<String, Quote>> = emptyMap(),
    val sourceHealth: Map<String, SourceHealth> = emptyMap(),
    val refreshing: Boolean = false,
    val lastAttemptAt: Long? = null,
    val error: String? = null,
)

/** A completed source updates only its own rows; a failed fetch retains the old
 * observation and original timestamp, never a made-up fresh quote. */
internal data class WatchQuoteUpdate(val symbolId: String, val unit: String, val quote: Quote)

internal fun mergeWatchSource(state: WatchState, sourceId: String,
                              updates: List<WatchQuoteUpdate>, health: SourceHealth): WatchState {
    val updated = state.quotes.mapValues { it.value.toMutableMap() }.toMutableMap()
    for (row in updates) {
        val normalized = row.quote.copy(unit = row.unit)
        val quotes = updated.getOrPut(row.symbolId) { mutableMapOf() }
        if (normalized.price != null && normalized.error == null) {
            quotes[sourceId] = normalized
        } else {
            val old = quotes[sourceId]
            quotes[sourceId] = old?.copy(stale = true, error = normalized.error) ?: normalized
        }
    }
    return state.copy(quotes = updated, sourceHealth = state.sourceHealth + (sourceId to health))
}

/** Watch-only snapshots. Never feeds the chart, signal engine, journal settlement or order API. */
class WatchRepository(
    private val fetcher: SourceFetcher,
    private val history: QuoteHistoryStore,
    private val preferences: WatchSettingsStore,
    private val chartSettings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var attemptedAtElapsed = 0L
    private var cryptoWatchAttemptAtElapsed = 0L
    private var bboWatchAttemptAtElapsed = 0L
    private var loaded = false
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun loadCached() {
        scope.launch { mutex.withLock { ensureLoaded() } }
        startAutoRefresh()
    }

    private var autoRefresh: kotlinx.coroutines.Job? = null

    /**
     * Keep the watchlist live on the app-wide base cadence (60s): watch cards are
     * display snapshots on an M5 rhythm, so there is no reason to re-poll every
     * provider faster than the base sweep. The per-source throttles below (crypto /
     * BBO lanes) run on the same 60s grid.
     */
    fun startAutoRefresh(intervalMs: Long = com.aurum.edge.core.MonitorCadence.BASE_SWEEP_MS) {
        if (autoRefresh?.isActive == true) return
        autoRefresh = scope.launch {
            while (true) {
                runCatching { refresh() }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() { autoRefresh?.cancel(); autoRefresh = null }

    fun refreshNow() {
        scope.launch { refresh() }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val restored = mutableMapOf<String, Map<String, Quote>>()
        WatchCatalog.symbols.forEach { symbol ->
            val cached = symbol.providerCodes.keys.mapNotNull { sourceId ->
                history.latest(symbol.id, sourceId)?.let { sourceId to it }
            }.toMap()
            if (cached.isNotEmpty()) restored[symbol.id] = cached
        }
        _state.value = _state.value.copy(quotes = restored)
        loaded = true
    }

    private data class Target(val symbol: WatchSymbol, val source: SourceDef, val providerCode: String, val key: String)
    private data class GroupResult(
        val source: SourceDef,
        val snapshot: SourceSnapshot,
        val pairs: List<Pair<Target, Quote>>,
    )

    private suspend fun refresh() = mutex.withLock {
        ensureLoaded()
        val elapsed = SystemClock.elapsedRealtime()
        if (attemptedAtElapsed != 0L && elapsed - attemptedAtElapsed in 0L until com.aurum.edge.core.MonitorCadence.BASE_SWEEP_MS) {
            _state.value = _state.value.copy(error = "دیده‌بان با ریت پایهٔ ۶۰ ثانیه به‌روز می‌شود؛ تلاش بعدی در نوبت بعدیِ ریت انجام می‌شود")
            return@withLock
        }
        attemptedAtElapsed = elapsed
        _state.value = _state.value.copy(refreshing = true, error = null,
            lastAttemptAt = System.currentTimeMillis()) // attempt time, not quote freshness
        try {
            val selected = preferences.selections.value
            // Watch rows are low-priority snapshots; open-position WebSocket/REST recovery
            // runs independently even if a watch refresh stalls.
            val cryptoDue = cryptoWatchAttemptAtElapsed == 0L ||
                elapsed - cryptoWatchAttemptAtElapsed >= 60_000L
            val bboDue = bboWatchAttemptAtElapsed == 0L ||
                elapsed - bboWatchAttemptAtElapsed >= 60_000L
            val targets = WatchCatalog.symbols.flatMap { symbol ->
                selected[symbol.id]?.enabledSources.orEmpty().mapNotNull { sourceId ->
                    val source = SourceCatalog.find(sourceId) ?: return@mapNotNull null
                    if (source.kind == SourceKind.CRYPTO_SPOT_TRADE && !cryptoDue) return@mapNotNull null
                    if (source.kind == SourceKind.SPOT_BBO_QUOTE && !bboDue) return@mapNotNull null
                    val code = symbol.providerCodes[sourceId] ?: return@mapNotNull null
                    Target(symbol, source, code, if (source.requiresKey) preferences.apiKeyFor(symbol.id, chartSettings.read().apiKey) else "")
                }
            }
            if (targets.any { it.source.kind == SourceKind.CRYPTO_SPOT_TRADE })
                cryptoWatchAttemptAtElapsed = elapsed
            if (targets.any { it.source.kind == SourceKind.SPOT_BBO_QUOTE })
                bboWatchAttemptAtElapsed = elapsed
            // Per-source responses publish as soon as they arrive. A stalled car/FX
            // publisher cannot hold completed crypto/other watch rows behind awaitAll.
            // Keep the per-provider batch and the crypto 60s throttle; do not fake ticks.
            withContext(Dispatchers.IO) {
                targets.groupBy { it.source.id to it.key }.values.map { group -> async {
                    val source = group.first().source
                    val snapshot = try {
                        fetcher.fetchAll(source,
                            group.map { SymbolDef(it.providerCode, it.symbol.label) }, group.first().key)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        SourceSnapshot(source, group.map { target -> Quote(target.providerCode,
                            target.symbol.label, error = (error.message ?: "خطای فید").take(100),
                            sourceId = source.id) }, System.currentTimeMillis(), false,
                            error = "دریافت منبع انجام نشد")
                    }
                    val result = GroupResult(source, snapshot, group.zip(snapshot.quotes))
                    val fresh = snapshot.quotes.count { it.price != null && it.error == null && !it.stale }
                    val health = SourceHealth(source.id, source.title, when {
                        snapshot.error?.contains("کلید") == true -> "NO_KEY"
                        fresh == snapshot.quotes.size && fresh > 0 -> "HEALTHY"
                        fresh > 0 -> "DEGRADED"
                        else -> "OFFLINE"
                    }, snapshot.fetchedAt, snapshot.latencyMs, snapshot.quotes.size, fresh, snapshot.error)
                    // Archive only genuine observations, with the original provider time.
                    result.pairs.forEach { (target, incoming) ->
                        if (incoming.price != null && incoming.error == null)
                            history.append(target.symbol.id, incoming.copy(unit = target.symbol.unit))
                    }
                    _state.update { previous ->
                        mergeWatchSource(previous, source.id,
                            result.pairs.map { (target, incoming) ->
                                WatchQuoteUpdate(target.symbol.id, target.symbol.unit, incoming)
                            }, health)
                    }
                } }.awaitAll()
            }
            _state.value = _state.value.copy(lastAttemptAt = System.currentTimeMillis())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "به‌روزرسانی دیده‌بان انجام نشد: ${e.message ?: "خطای داده"}")
        } finally {
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    suspend fun page(symbolId: String, sourceId: String, before: Long? = null): List<Quote> {
        val symbol = WatchCatalog.find(symbolId) ?: return emptyList()
        if (sourceId !in symbol.providerCodes) return emptyList()
        return history.page(symbolId, sourceId, before)
    }

    suspend fun count(symbolId: String, sourceId: String): Long {
        if (WatchCatalog.find(symbolId)?.providerCodes?.containsKey(sourceId) != true) return 0L
        return history.count(symbolId, sourceId)
    }

    suspend fun clearHistory() = mutex.withLock {
        val ids = WatchCatalog.symbols.map { it.id }
        history.clear(ids)
        _state.value = _state.value.copy(quotes = _state.value.quotes.mapValues { (symbolId, quotes) ->
            if (symbolId in ids) quotes.filterValues { !it.stale } else quotes
        })
    }
}
