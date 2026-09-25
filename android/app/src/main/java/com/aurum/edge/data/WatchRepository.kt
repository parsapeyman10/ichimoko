package com.aurum.edge.data

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

    private suspend fun refresh() = mutex.withLock {
        ensureLoaded()
        val activeWorkspace = chartSettings.read().workspaceId
        _state.value = _state.value.copy(refreshing = true, error = null, lastAttemptAt = null)
        try {
            val selected = preferences.selections.value
            val targets = WatchCatalog.forWorkspace(activeWorkspace).flatMap { symbol ->
                selected[symbol.id]?.enabledSources.orEmpty().mapNotNull { sourceId ->
                    val source = SourceCatalog.find(sourceId) ?: return@mapNotNull null
                    val code = symbol.providerCodes[sourceId] ?: return@mapNotNull null
                    Target(symbol, source, code, if (source.requiresKey) preferences.apiKeyFor(symbol.id, chartSettings.read().apiKey) else "")
                }
            }
            // Batch only requests that share both a provider AND a read-only key. A key is
            // never sent to another provider; CoinGecko can fetch BTC/ETH in one request.
            val results = withContext(Dispatchers.IO) {
                targets.groupBy { it.source.id to it.key }.values.map { group ->
                    async {
                        val source = group.first().source
                        val snapshot = fetcher.fetchAll(source, group.map { SymbolDef(it.providerCode, it.symbol.label) }, group.first().key)
                        group.zip(snapshot.quotes)
                    }
                }.awaitAll().flatten()
            }
            // A request that started in a space being left must not persist observations as
            // though the newly selected space had made them. The old HTTP call may still finish.
            if (chartSettings.read().workspaceId != activeWorkspace) return@withLock
            val updated = _state.value.quotes.mapValues { it.value.toMutableMap() }.toMutableMap()
            for ((target, incoming) in results) {
                val quotes = updated.getOrPut(target.symbol.id) { mutableMapOf() }
                if (incoming.price != null && incoming.error == null) {
                    quotes[target.source.id] = incoming
                    history.append(target.symbol.id, incoming)
                } else {
                    // Keep old genuine observation and its ORIGINAL timestamp, but explicitly
                    // mark it cached/failed. Never make yesterday's price look fresh again.
                    val old = quotes[target.source.id]
                    quotes[target.source.id] = old?.copy(stale = true, error = incoming.error)
                        ?: incoming
                }
            }
            _state.value = _state.value.copy(quotes = updated, lastAttemptAt = System.currentTimeMillis())
        } catch (e: Exception) {
            if (chartSettings.read().workspaceId == activeWorkspace)
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
        val ids = WatchCatalog.forWorkspace(chartSettings.read().workspaceId).map { it.id }
        history.clear(ids)
        _state.value = _state.value.copy(quotes = _state.value.quotes.mapValues { (symbolId, quotes) ->
            if (symbolId in ids) quotes.filterValues { !it.stale } else quotes
        })
    }
}
