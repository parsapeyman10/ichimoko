package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.PaperOpportunity
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

/** One row of the all-pairs radar: what the last online candle sweep actually observed for this pair. */
data class PairScanStatus(
    val symbol: String,
    /** pending | closed | error | no_signal | blocked | candidate */
    val state: String,
    val detail: String,
    val lastScanAt: Long? = null,
    val price: Double? = null,
    /** How many of the eight technical conditions passed on the latest closed bar. */
    val technicalScore: Int? = null,
)

data class PairScanState(
    val statuses: List<PairScanStatus> = emptyList(),
    val sweeping: Boolean = false,
    val lastSweepAt: Long? = null,
    val lastError: String? = null,
)

/**
 * Periodic 3000-candle sweep over the whole watch catalog (gold + majors) so no strong
 * technical/risk-screened opportunity goes unnoticed while the live tick feed follows only the selected chart symbol.
 *
 * Honest limits, identical to the single-symbol pipeline:
 * - REST/public-history candles justify an educational CANDIDATE + notification, never an automatic paper
 *   fill (auto entry stays live-tick only, on the selected symbol).
 * - Every pair is evaluated with the SAME eight technical conditions plus a per-pair
 *   news/calendar risk layer, the SAME ICT gate and the SAME MTF veto; a missing key or a failed fetch is an explicit
 *   status, never a fabricated signal.
 * - Calls are spaced to respect Twelve/Yahoo provider limits (≤7 requests/minute).
 */
class PairScanner(
    private val client: TwelveDataClient,
    private val publicHistory: PublicCandleHistoryClient,
    private val settings: SettingsStore,
    private val news: NewsRepository,
    private val journal: JournalStore,
    private val opportunities: PaperOpportunityStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var lastSweepElapsed = 0L
    private val _state = MutableStateFlow(PairScanState(
        statuses = WatchCatalog.chartSymbols.map { PairScanStatus(it, "pending", "هنوز اسکن نشده") }))
    val state: StateFlow<PairScanState> = _state.asStateFlow()

    /** Throttled sweep request; the service probes every minute, the UI button uses this too. */
    fun refreshNow(minIntervalMs: Long = SWEEP_PERIOD_MS) {
        scope.launch { runSweep(minIntervalMs, onCandidate = null) }
    }

    /**
     * One full sweep. [onCandidate] is invoked ONLY for brand-new recorded candidates so the
     * caller can play the verified-alert sound; passing null records candidates silently.
     */
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
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    lastError = "اسکن دوره‌ای ناموفق بود: ${(error.message ?: "خطا").take(100)}")
            } finally {
                _state.value = _state.value.copy(sweeping = false, lastSweepAt = System.currentTimeMillis())
            }
        }

    private suspend fun sweepAll(onCandidate: (suspend (PaperOpportunity) -> Unit)?) {
        val statuses = _state.value.statuses.associateBy { it.symbol }.toMutableMap()
        fun update(symbol: String, state: String, detail: String, price: Double? = null, score: Int? = null) {
            statuses[symbol] = PairScanStatus(symbol, state, detail, System.currentTimeMillis(), price, score)
            _state.value = _state.value.copy(statuses = WatchCatalog.chartSymbols.mapNotNull { statuses[it] })
        }

        val config = settings.read()
        val interval: Interval = config.interval
        if (MarketHours.forexWeekendClosed()) {
            WatchCatalog.chartSymbols.forEach { update(it, "closed", "بازار فارکس تعطیل است؛ اسکن ارسال نشد") }
            return
        }
        val headlines = news.state.value
        val trades = journal.trades.value
        // One online candle-history call per pair, spaced so a full sweep stays under the
        // provider's per-minute quota even on free/public paths.
        WatchCatalog.chartSymbols.forEachIndexed { index, symbol ->
            if (index > 0) delay(PAIR_SPACING_MS)
            val now = System.currentTimeMillis()
            if (MarketHours.forexWeekendClosed(now)) { update(symbol, "closed", "بازار تعطیل شد؛ اسکن متوقف شد"); return@forEachIndexed }
            if (trades.any { it.symbol == symbol && it.isOpen }) {
                update(symbol, "blocked", "پوزیشن کاغذی این نماد باز است؛ فرصت جدید اسکن نمی‌شود")
                return@forEachIndexed
            }
            val candles = try {
                if (config.hasKey) {
                    try {
                        client.fetchCandles(config.apiKey, symbol, interval, outputSize = HistoryPolicy.TARGET_CANDLES)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        publicHistory.fetchCandles(symbol, interval, minimumSize = HistoryPolicy.TARGET_CANDLES).candles
                    }
                } else {
                    publicHistory.fetchCandles(symbol, interval, minimumSize = HistoryPolicy.TARGET_CANDLES).candles
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
            if (evaluated == null) { update(symbol, "error", "ارزیابی سیگنال روی کندل‌های دریافتی ممکن نشد", price); return@forEachIndexed }
            val combined = NewsConfluence.apply(evaluated, symbol, headlines, now)!!
            val score = combined.confluence.take(NewsConfluence.TECHNICAL_COUNT).count { it.ok }
            if (!combined.isActionable) {
                update(symbol, "no_signal",
                    if (score >= 5) "شواهد فنی $score از ۸؛ هنوز سیگنال قابل معامله نیست" else "بدون سیگنال؛ شواهد فنی $score از ۸",
                    price, score)
                return@forEachIndexed
            }
            // Sweep data: the provider's newest CLOSED bar may be up to one interval old.
            val graceMs = interval.millis + 90_000L
            val market = MarketState(
                symbol = symbol, interval = interval, candles = candles, lastPrice = price,
                feed = FeedStatus(FeedMode.POLLING,
                    if (config.hasKey) "اسکن دوره‌ای Twelve/public fallback" else "اسکن دوره‌ای تاریخچهٔ عمومی",
                    System.currentTimeMillis()),
                signal = combined,
            )
            val mtf = withContext(Dispatchers.Default) {
                runCatching { MtfAnalyzer.analyze(candles, interval) }.getOrNull()
            }
            val blocker = PaperAlertRules.blocker(market, config, headlines, trades, mtf,
                System.currentTimeMillis(), allowedSymbols = WatchCatalog.chartSymbols, barAgeGraceMs = graceMs)
            if (blocker != null) {
                update(symbol, "blocked", "سیگنال $score/۸ · ${blocker.take(120)}", price, score)
                return@forEachIndexed
            }
            val evidence = NewsConfluence.record(headlines, symbol)
            val ict = IctEntryRules.approvedEvidence(market, System.currentTimeMillis(), graceMs)
            val fresh = settings.read() // re-read: the sweep itself can take over a minute
            if (evidence == null || ict == null || mtf == null || price == null ||
                PaperAlertRules.blocker(market, fresh, news.state.value, journal.trades.value, mtf,
                    System.currentTimeMillis(), WatchCatalog.chartSymbols, graceMs) != null) {
                update(symbol, "blocked", "شواهد کامل کاندیدای آموزشی در لحظهٔ ثبت در دسترس نبود", price, score)
                return@forEachIndexed
            }
            val item = runCatching {
                PaperOpportunity.from(combined, symbol, price, MtfSnapshotRecord.from(mtf), evidence, ict)
            }.getOrNull()
            if (item == null) { update(symbol, "blocked", "ساخت رکورد فرصت آموزشی ممکن نشد", price, score); return@forEachIndexed }
            val recorded = runCatching { opportunities.record(item) }.getOrDefault(false)
            if (recorded) onCandidate?.invoke(item)
            update(symbol, "candidate", "کاندیدای آموزشی ۸/۸ + خبر ثبت شد؛ اعلان/ژورنال را ببینید — ورود خودکار فقط با فید زندهٔ همین نماد", price, score)
        }
    }

    companion object {
        /** Full sweep period while background monitoring is on. */
        const val SWEEP_PERIOD_MS = 5 * 60_000L
        /** ~7 requests/minute keeps a sweep inside Twelve Data free-tier credits. */
        const val PAIR_SPACING_MS = 9_000L
    }
}
