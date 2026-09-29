package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.MarketHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var loaded = false
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun loadCached() {
        scope.launch { mutex.withLock { ensureLoaded() } }
    }

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
        if (MarketHours.forexWeekendClosed()) {
            _state.value = _state.value.copy(refreshing = false,
                error = "تعطیلی معمول فارکس؛ درخواست قیمت جدید ارسال نشد")
            return@withLock
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (attemptedAtElapsed != 0L && elapsed - attemptedAtElapsed in 0L until 180_000L) {
            _state.value = _state.value.copy(error = "برای سهمیهٔ منابع، حداقل سه دقیقه بین دریافت‌های دیده‌بان صبر کنید")
            return@withLock
        }
        attemptedAtElapsed = elapsed
        _state.value = _state.value.copy(refreshing = true, error = null,
            lastAttemptAt = System.currentTimeMillis()) // attempt time, not quote freshness
        try {
            val selected = preferences.selections.value
            val targets = WatchCatalog.symbols.flatMap { symbol ->
                selected[symbol.id]?.enabledSources.orEmpty().mapNotNull { sourceId ->
                    val source = SourceCatalog.find(sourceId) ?: return@mapNotNull null
                    val code = symbol.providerCodes[sourceId] ?: return@mapNotNull null
                    Target(symbol, source, code, if (source.requiresKey) preferences.apiKeyFor(symbol.id, chartSettings.read().apiKey) else "")
                }
            }
            // Batch only requests that share both a provider AND a read-only key. A key is
            // never sent to another provider; public Yahoo symbols never receive a Twelve key.
            val results = withContext(Dispatchers.IO) {
                targets.groupBy { it.source.id to it.key }.values.map { group ->
                    async {
                        val source = group.first().source
                        val snapshot = fetcher.fetchAll(
                            source,
                            group.map { SymbolDef(it.providerCode, it.symbol.label) },
                            group.first().key,
                        )
                        GroupResult(source, snapshot, group.zip(snapshot.quotes))
                    }
                }.awaitAll()
            }
            val health = results.associate { result ->
                val snapshot = result.snapshot
                val fresh = snapshot.quotes.count { it.price != null && it.error == null && !it.stale }
                val state = when {
                    snapshot.error?.contains("کلید") == true -> "NO_KEY"
                    fresh == snapshot.quotes.size && fresh > 0 -> "HEALTHY"
                    fresh > 0 -> "DEGRADED"
                    else -> "OFFLINE"
                }
                result.source.id to SourceHealth(
                    sourceId = result.source.id,
                    provider = result.source.title,
                    state = state,
                    fetchedAt = snapshot.fetchedAt,
                    latencyMs = snapshot.latencyMs,
                    quoteCount = snapshot.quotes.size,
                    freshQuoteCount = fresh,
                    detail = snapshot.error,
                )
            }
            val updated = _state.value.quotes.mapValues { it.value.toMutableMap() }.toMutableMap()
            for (result in results) for ((target, incoming) in result.pairs) {
                // providerCodes guarantees the mapped provider code is the same instrument and quote
                // unit, so the symbol's unit is the true label (Twelve Data quotes USD/JPY in JPY).
                val normalized = incoming.copy(unit = target.symbol.unit)
                val quotes = updated.getOrPut(target.symbol.id) { mutableMapOf() }
                if (normalized.price != null && normalized.error == null) {
                    quotes[target.source.id] = normalized
                    history.append(target.symbol.id, normalized)
                } else {
                    // Keep old genuine observation and its ORIGINAL timestamp, but explicitly
                    // mark it cached/failed. Never make yesterday's price look fresh again.
                    val old = quotes[target.source.id]
                    quotes[target.source.id] = old?.copy(stale = true, error = normalized.error)
                        ?: normalized
                }
            }
            _state.value = _state.value.copy(
                quotes = updated,
                sourceHealth = health,
                lastAttemptAt = System.currentTimeMillis(),
            )
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
