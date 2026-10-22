package com.aurum.edge.data

import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One owner for every open-position tick worker.
 *
 * Before this, the same per-symbol worker was written twice:
 *   * [AurumViewModel.ensureLiveTickerLoop]  (foreground, timestamped valuation)
 *   * [SignalMonitorService.positionJobs]   (background, settlement)
 *
 * Both live in the same process and share [AppContainer], so one coordinator instance
 * means exactly one network read per open symbol, exactly one [settleTick] path, and
 * exactly one place to cancel a worker when its position closes. The UI reads
 * [liveTicks] for display; settlement happens here once and is observed by everyone
 * through [JournalStore.trades].
 *
 * All quoting goes through [SpotFallbackClient.fetchQuote], which is process-wide
 * coalesced ([QuoteCoalescer]) — UI and service never issue duplicate requests for
 * the same symbol.
 */
class OpenPositionCoordinator(
    private val journal: JournalStore,
    private val spot: SpotFallbackClient,
    private val ticks: OpenTradeTickMonitor,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var positionsJob: Job? = null

    private val _liveTicks = MutableStateFlow(emptyMap<String, PriceTick>())
    /** Latest accepted, fresh quote per open symbol — consumed by the UI for display. */
    val liveTicks: StateFlow<Map<String, PriceTick>> = _liveTicks.asStateFlow()

    fun start() {
        positionsJob?.cancel()
        positionsJob = scope.launch {
            try {
                journal.load()
            } catch (_: Exception) {
                return@launch
            }
            val workers = mutableMapOf<String, Pair<Long, Job>>()
            try {
                journal.trades.collect { trades ->
                    val open = trades.filter { it.isOpen }.groupBy { it.symbol }
                        .mapValues { (_, rows) -> rows.minOf { it.openedAt } }
                    val openSymbols = open.keys.toSet()

                    // Drop workers whose position has been closed.
                    workers.toMap().forEach { entry ->
                        val symbol = entry.key
                        val worker = entry.value
                        if (open[symbol] != worker.first) {
                            worker.second.cancel()
                            workers.remove(symbol)
                        }
                    }

                    // Start one independent worker per open position.
                    open.forEach { (symbol, openedAt) ->
                        if (symbol !in workers) {
                            val worker = launch(Dispatchers.IO) {
                                val crypto = CryptoCatalog.isCrypto(symbol)
                                // Separate closed-candle lane: never slow the ordered tick stream.
                                if (crypto) launch {
                                    while (isActive) {
                                        try {
                                            ticks.syncCryptoClosedBars(symbol, openedAt)
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            // Keep the prior stop/valuation when history is unavailable.
                                        }
                                        delay(60_000L)
                                    }
                                }
                                while (isActive) {
                                    try {
                                        if (!MarketHours.closedFor(symbol)) {
                                            val quote: PriceTick? = when {
                                                crypto -> ticks.poll(symbol, openedAt)
                                                else -> spot.fetchQuote(symbol)
                                            }
                                            val fresh = quote?.takeIf {
                                                it.at >= openedAt && TradeQuotePolicy.accepts(it)
                                            }
                                            if (fresh != null) {
                                                _liveTicks.update { previous ->
                                                    OpenTickSnapshot.updated(
                                                        previous, symbol, fresh, openSymbols,
                                                    )
                                                }
                                                // Single settlement path for every symbol and every caller.
                                                journal.settleTick(
                                                    symbol, fresh.price, fresh.at,
                                                    expectedOpenedAt = openedAt,
                                                )
                                            }
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        // Only this symbol becomes unknown; keep the others ticking.
                                    }
                                    delay(if (crypto) 3_000L else 1_000L)
                                }
                            }
                            workers[symbol] = openedAt to worker
                        }
                    }
                }
            } finally {
                workers.values.forEach { it.second.cancel() }
                _liveTicks.value = emptyMap()
            }
        }
    }

    /** Stop all position workers and clear the published ticks. */
    fun stop() {
        positionsJob?.cancel()
        positionsJob = null
        _liveTicks.value = emptyMap()
    }
}
