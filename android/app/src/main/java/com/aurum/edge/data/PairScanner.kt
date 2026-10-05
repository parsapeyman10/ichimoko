package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.SignalAction
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.engine.SignalEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One row of the 50+ universe radar: what the continuous sweep observed for this instrument. */
data class PairScanStatus(
    val symbol: String,
    /** pending | closed | error | no_signal | blocked | candidate */
    val state: String,
    val detail: String,
    val lastScanAt: Long? = null,
    val price: Double? = null,
    /** How many of the eight technical conditions passed on the latest closed bar. */
    val technicalScore: Int? = null,
    val action: SignalAction? = null,
    val confidence: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
)

data class PairScanState(
    val statuses: List<PairScanStatus> = emptyList(),
    val sweeping: Boolean = false,
    val lastSweepAt: Long? = null,
    val lastError: String? = null,
    /** The Top 3 highest-ranking opportunities from the 50+ scan. */
    val topThree: List<PairScanStatus> = emptyList(),
    /** The single #1 absolute best opportunity to trade. */
    val bestPick: PairScanStatus? = null,
)

/**
 * Continuous 50+ instrument scanner and ranking engine:
 * Evaluates at least 50 global instruments (Commodities, Forex, Global Shares/Stocks, Crypto),
 * ranks them based on Ichimoku confluence, MTF alignment, and R:R ratio,
 * isolates the Top 3 best setups, and selects the #1 best trade.
 */
class PairScanner(
    private val client: TwelveDataClient,
    private val publicHistory: PublicCandleHistoryClient,
    private val settings: SettingsStore,
    private val news: NewsRepository,
    private val journal: JournalStore,
    private val opportunities: PaperOpportunityStore,
    private val scope: CoroutineScope,
    private val dukascopyHistory: DukascopyHistoryClient = DukascopyHistoryClient(),
) {
    private val mutex = Mutex()
    private var lastSweepElapsed = 0L
    private val _state = MutableStateFlow(PairScanState(
        statuses = WatchCatalog.scannerSymbols.map { PairScanStatus(it, "pending", "هنوز اسکن نشده") }))
    val state: StateFlow<PairScanState> = _state.asStateFlow()

    fun refreshNow(minIntervalMs: Long = SWEEP_PERIOD_MS) {
        scope.launch { runSweep(minIntervalMs, onCandidate = null) }
    }

    suspend fun sweepOnce(minIntervalMs: Long = SWEEP_PERIOD_MS,
                          onCandidate: (suspend (PaperOpportunity) -> Unit)? = null) {
        runSweep(minIntervalMs, onCandidate)
    }

    private suspend fun runSweep(minIntervalMs: Long, onCandidate: (suspend (PaperOpportunity) -> Unit)?) =
        mutex.withLock {
            val elapsed = SystemClock.elapsedRealtime()
            if (_state.value.sweeping) return@withLock
            if (lastSweepElapsed != 0L && elapsed - lastSweepElapsed < minIntervalMs) return@withLock
            lastSweepElapsed = elapsed
            _state.value = _state.value.copy(sweeping = true, lastError = null)
            try {
                sweepAll(onCandidate)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    lastError = "اسکن دوره‌ای ناموفق بود؛ پاسخ یا اتصال provider معتبر نبود")
            } finally {
                val currentStatuses = _state.value.statuses
                val ranked = rankOpportunities(currentStatuses)
                _state.value = _state.value.copy(
                    sweeping = false,
                    lastSweepAt = System.currentTimeMillis(),
                    topThree = ranked.take(3),
                    bestPick = ranked.firstOrNull(),
                )
            }
        }

    private fun rankOpportunities(statuses: List<PairScanStatus>): List<PairScanStatus> {
        return statuses
            .filter { it.price != null && (it.state == "candidate" || (it.technicalScore ?: 0) >= 5) }
            .sortedWith(
                compareByDescending<PairScanStatus> { it.state == "candidate" }
                    .thenByDescending { it.technicalScore ?: 0 }
                    .thenByDescending { it.confidence ?: 0.0 }
                    .thenByDescending { it.riskReward ?: 0.0 }
            )
    }

    private suspend fun sweepAll(onCandidate: (suspend (PaperOpportunity) -> Unit)?) {
        val statuses = _state.value.statuses.associateBy { it.symbol }.toMutableMap()
        fun update(
            symbol: String,
            state: String,
            detail: String,
            price: Double? = null,
            score: Int? = null,
            action: SignalAction? = null,
            confidence: Double? = null,
            entry: Double? = null,
            sl: Double? = null,
            tp: Double? = null,
            rr: Double? = null,
        ) {
            statuses[symbol] = PairScanStatus(
                symbol = symbol,
                state = state,
                detail = detail,
                lastScanAt = System.currentTimeMillis(),
                price = price,
                technicalScore = score,
                action = action,
                confidence = confidence,
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                riskReward = rr,
            )
            val currentList = WatchCatalog.scannerSymbols.mapNotNull { statuses[it] }
            val ranked = rankOpportunities(currentList)
            _state.value = _state.value.copy(
                statuses = currentList,
                topThree = ranked.take(3),
                bestPick = ranked.firstOrNull(),
            )
        }

        val config = settings.read()
        val interval: Interval = config.interval
        val headlines = news.state.value
        val trades = journal.trades.value

        WatchCatalog.scannerSymbols.forEachIndexed { index, symbol ->
            if (index > 0) delay(PAIR_SPACING_MS)
            val now = System.currentTimeMillis()
            if (MarketHours.weekendClosedFor(symbol, now)) {
                update(symbol, "closed", "بازار تعطیل است؛ اسکن موقتاً متوقف شد")
                return@forEachIndexed
            }
            if (trades.any { it.symbol == symbol && it.isOpen }) {
                update(symbol, "blocked", "پوزیشن کاغذی این نماد باز است؛ فرصت جدید اسکن نمی‌شود")
                return@forEachIndexed
            }

            suspend fun publicOrDukascopy(): List<Candle> = try {
                publicHistory.fetchCandles(symbol, interval,
                    minimumSize = HistoryPolicy.TARGET_CANDLES,
                    desiredSize = HistoryPolicy.TARGET_CANDLES).candles
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (publicFailure: Exception) {
                try {
                    dukascopyHistory.fetchCandles(symbol, interval,
                        minimumSize = HistoryPolicy.TARGET_CANDLES,
                        desiredSize = HistoryPolicy.TARGET_CANDLES).candles
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (deepFailure: Exception) {
                    throw DataFeedException((deepFailure.message ?: publicFailure.message ?: "خطای دریافت کندل").take(140))
                }
            }

            val candles = try {
                if (config.hasKey) {
                    try {
                        client.fetchCandles(config.apiKey, symbol, interval, outputSize = HistoryPolicy.TARGET_CANDLES)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        publicOrDukascopy()
                    }
                } else {
                    publicOrDukascopy()
                }
            } catch (error: Exception) {
                update(symbol, "error", (error.message ?: "خطای دریافت کندل").take(100))
                return@forEachIndexed
            }

            if (candles.size < HistoryPolicy.TARGET_CANDLES) {
                update(symbol, "error", "ناشر فقط ${candles.size} کندل داد؛ حداقل ${HistoryPolicy.TARGET_CANDLES} کندل لازم است")
                return@forEachIndexed
            }

            val price = candles.lastOrNull()?.close
            val evaluated = withContext(Dispatchers.Default) {
                runCatching { SignalEngine.evaluate(candles, interval, config.minConfidence, config.spreadPrice, config.signalProfile) }.getOrNull()
            }
            if (evaluated == null) {
                update(symbol, "error", "ارزیابی سیگنال روی کندل‌های دریافتی ممکن نشد", price)
                return@forEachIndexed
            }

            val combined = NewsConfluence.apply(evaluated, symbol, headlines, now)!!
            val score = combined.confluence.take(NewsConfluence.TECHNICAL_COUNT).count { it.ok }
            if (!combined.isActionable) {
                update(
                    symbol = symbol,
                    state = "no_signal",
                    detail = if (score >= 5) "شواهد فنی $score از ۸؛ هنوز به آستانهٔ ورود نرسیده" else "بدون سیگنال؛ شواهد فنی $score از ۸",
                    price = price,
                    score = score,
                    action = combined.action,
                    confidence = combined.confidence,
                    entry = combined.entry,
                    sl = combined.stopLoss,
                    tp = combined.takeProfit,
                    rr = combined.riskReward,
                )
                return@forEachIndexed
            }

            val graceMs = interval.millis + 90_000L
            val market = MarketState(
                symbol = symbol, interval = interval, candles = candles, lastPrice = price,
                feed = FeedStatus(FeedMode.POLLING,
                    if (config.hasKey) "اسکن دوره‌ای Twelve/public/Dukascopy fallback" else "اسکن دوره‌ای تاریخچهٔ عمومی/Dukascopy",
                    System.currentTimeMillis()),
                signal = combined,
            )
            val mtf = withContext(Dispatchers.Default) {
                runCatching { MtfAnalyzer.analyze(candles, interval) }.getOrNull()
            }
            val blocker = PaperAlertRules.blocker(market, config, headlines, trades, mtf,
                System.currentTimeMillis(), allowedSymbols = WatchCatalog.scannerSymbols, barAgeGraceMs = graceMs)
            if (blocker != null) {
                update(
                    symbol = symbol,
                    state = "blocked",
                    detail = "سیگنال $score/۸ · ${blocker.take(120)}",
                    price = price,
                    score = score,
                    action = combined.action,
                    confidence = combined.confidence,
                    entry = combined.entry,
                    sl = combined.stopLoss,
                    tp = combined.takeProfit,
                    rr = combined.riskReward,
                )
                return@forEachIndexed
            }

            val evidence = NewsConfluence.record(headlines, symbol)
            val ict = IctEntryRules.approvedEvidence(market, System.currentTimeMillis(), graceMs)
            val fresh = settings.read()
            if (ict == null || mtf == null || price == null ||
                PaperAlertRules.blocker(market, fresh, news.state.value, journal.trades.value, mtf,
                    System.currentTimeMillis(), WatchCatalog.scannerSymbols, graceMs) != null) {
                update(symbol, "blocked", "شواهد فنی/ICT/MTF کاندیدای آموزشی در لحظهٔ ثبت در دسترس نبود", price, score)
                return@forEachIndexed
            }

            val item = runCatching {
                PaperOpportunity.from(combined, symbol, price, MtfSnapshotRecord.from(mtf), evidence, ict)
            }.getOrNull()
            if (item == null) {
                update(symbol, "blocked", "ساخت رکورد فرصت ممکن نشد", price, score)
                return@forEachIndexed
            }

            val recorded = runCatching { opportunities.record(item) }.getOrDefault(false)
            if (recorded) onCandidate?.invoke(item)
            update(
                symbol = symbol,
                state = "candidate",
                detail = "کاندیدای برتر ۸/۸ فنی ثبت شد · امتیاز ${(combined.confidence).toInt()}% · R:R ${String.format(java.util.Locale.US, "%.2f", combined.riskReward ?: 1.5)}",
                price = price,
                score = score,
                action = combined.action,
                confidence = combined.confidence,
                entry = combined.entry,
                sl = combined.stopLoss,
                tp = combined.takeProfit,
                rr = combined.riskReward,
            )
        }
    }

    companion object {
        const val SWEEP_PERIOD_MS = 3 * 60_000L
        const val PAIR_SPACING_MS = 2_000L
    }
}
