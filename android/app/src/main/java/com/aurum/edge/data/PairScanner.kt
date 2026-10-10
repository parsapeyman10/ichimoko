package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MarketPlaybook
import com.aurum.edge.core.MarketTrend
import com.aurum.edge.core.MarketTrendRead
import com.aurum.edge.core.SymbolTrend
import com.aurum.edge.core.TrendAlignment
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
    /** pending | closed | error | no_signal | blocked | observed | candidate */
    val state: String,
    val detail: String,
    val lastScanAt: Long? = null,
    val price: Double? = null,
    /** How many of the technical conditions passed on the latest closed bar. */
    val technicalScore: Int? = null,
    val action: SignalAction? = null,
    val confidence: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
    val conditions: List<com.aurum.edge.core.ConfluenceItem> = emptyList(),
    val assetClass: com.aurum.edge.core.AssetClass = com.aurum.edge.core.AssetClass.of(symbol),
    /** «کدام روش برای کدام بازار»: the method the playbook measured for this instrument. */
    val methodLabel: String? = null,
    val playbookAllowed: Boolean? = null,
    /** The playbook's own reason when this market/method says stand aside. */
    val playbookReason: String? = null,
    /** «روند کلی بازار»: the measured trend of this instrument on its reference timeframe. */
    val trendLabel: String? = null,
    val trendStrength: Int? = null,
    /** Was the latest actionable side WITH the measured trend? null = never got a side. */
    val trendAligned: Boolean? = null,
    val trendNote: String? = null,
)

data class PairScanState(
    val statuses: List<PairScanStatus> = emptyList(),
    val sweeping: Boolean = false,
    /** Completed checks in the current sweep; includes closed, blocked and feed-error rows. */
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val lastSweepAt: Long? = null,
    val lastError: String? = null,
    /** The Top 3 highest-ranking opportunities from the 50+ scan. */
    val topThree: List<PairScanStatus> = emptyList(),
    /** The single #1 absolute best opportunity to trade. */
    val bestPick: PairScanStatus? = null,
    /**
     * «روند کلی بازار» — built from the SAME continuous sweep: every symbol whose real closed
     * candles were measured votes on breadth, and the six majors plus gold/crypto/equities give
     * the dollar direction and the risk tone. null until one sweep has measured anything.
     */
    val marketTrend: MarketTrendRead? = null,
)

/**
 * Continuous editable-watchlist scanner and ranking view:
 * Evaluates supported symbols across commodities, FX, shares/indices and crypto,
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
    private val decisionLog: DecisionLogStore? = null,
    private val nobitexHistory: NobitexHistoryClient = NobitexHistoryClient(),
) {
    private val mutex = Mutex()
    private val timeframeFeed = TimeframeFeed(publicHistory, dukascopyHistory, client, nobitexHistory)
    /** Trends measured during the running sweep; published as one market-wide read at the end. */
    private val trendSample = mutableListOf<SymbolTrend>()
    private var lastSweepElapsed = 0L
    private val _state = MutableStateFlow(PairScanState(
        statuses = com.aurum.edge.core.V1Universe.defaults.map { PairScanStatus(it, "pending", "هنوز اسکن نشده") }))
    val state: StateFlow<PairScanState> = _state.asStateFlow()

    fun refreshNow(minIntervalMs: Long = SWEEP_PERIOD_MS) {
        // A full multi-timeframe universe may take longer than the caller's 60-second tick.
        // Do not queue redundant sweeps behind the mutex or hammer read-only providers.
        if (!_state.value.sweeping) scope.launch { runSweep(minIntervalMs, onCandidate = null) }
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
            var finished = false
            try {
                sweepAll(onCandidate)
                finished = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    lastError = "اسکن دوره‌ای ناموفق بود؛ پاسخ یا اتصال provider معتبر نبود")
            } finally {
                // Throttle from completion, not from the start of a potentially long sweep.
                lastSweepElapsed = SystemClock.elapsedRealtime()
                val currentStatuses = _state.value.statuses
                val ranked = rankOpportunities(currentStatuses)
                // A failed/empty sweep must not promote the previous market read as current.
                val read = if (trendSample.isEmpty()) null
                else runCatching { MarketTrend.overall(trendSample) }.getOrNull()
                _state.value = _state.value.copy(
                    sweeping = false,
                    lastSweepAt = if (finished) System.currentTimeMillis() else _state.value.lastSweepAt,
                    topThree = ranked.take(3),
                    bestPick = ranked.firstOrNull(),
                    marketTrend = read,
                )
            }
        }

    private fun rankOpportunities(statuses: List<PairScanStatus>): List<PairScanStatus> =
        ScanRanking.rank(statuses, settings.read().interval, System.currentTimeMillis())

    private suspend fun sweepAll(onCandidate: (suspend (PaperOpportunity) -> Unit)?) {
        trendSample.clear()
        // The market-wide read of the PREVIOUS sweep: the breadth of the current one is only
        // complete after the last symbol, so entries during this sweep are checked against the
        // latest completed read (its age depends on provider latency). On the first sweep it is null, and then only
        // the instrument's own reference-timeframe trend is used — never a guessed market bias.
        val overallTrend = _state.value.marketTrend
        val config = settings.read()
        // The watchlist is evaluated first, but every supported catalog instrument is also
        // checked by the same candle -> V1 four-layer -> AI veto -> risk pipeline. An absent
        // feed is reported as an error, never filled with a fabricated candle.
        val universe = WatchCatalog.scanUniverse(config.activeWatchlist)
        // A result from the previous sweep cannot remain a current recommendation while
        // the provider is still being checked this time (or after a configuration change).
        val statuses = mutableMapOf<String, PairScanStatus>()
        _state.value = _state.value.copy(
            checkedCount = 0,
            topThree = emptyList(),
            bestPick = null,
            totalCount = universe.size,
            statuses = universe.map { symbol ->
                PairScanStatus(symbol, "pending", "در صف بررسی این دور")
            },
        )
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
            conditions: List<com.aurum.edge.core.ConfluenceItem> = emptyList(),
            methodLabel: String? = null,
            playbookAllowed: Boolean? = null,
            playbookReason: String? = null,
            trend: SymbolTrend? = null,
            trendAligned: Boolean? = null,
            trendNote: String? = null,
        ) {
            decisionLog?.append(symbol, config.interval.label, state, detail,
                signal = if (conditions.isNotEmpty()) com.aurum.edge.core.Signal(
                    action ?: SignalAction.NO_TRADE, confidence ?: 0.0, confluence = conditions)
                    else null, dedupe = false, method = methodLabel, methodReason = playbookReason)
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
                conditions = conditions,
                assetClass = com.aurum.edge.core.AssetClass.of(symbol),
                methodLabel = methodLabel,
                playbookAllowed = playbookAllowed,
                playbookReason = playbookReason,
                trendLabel = trend?.shortFa,
                trendStrength = trend?.strength,
                trendAligned = trendAligned,
                trendNote = trendNote ?: trend?.detailFa?.take(200),
            )
            val currentList = universe.mapNotNull { statuses[it] }
            val ranked = rankOpportunities(currentList)
            _state.value = _state.value.copy(
                statuses = currentList,
                checkedCount = _state.value.checkedCount + 1,
                topThree = ranked.take(3),
                bestPick = ranked.firstOrNull(),
            )
        }

        val interval: Interval = config.interval
        val headlines = news.state.value
        val trades = journal.trades.value

        universe.forEachIndexed { index, symbol ->
            if (index > 0) delay(PAIR_SPACING_MS)
            val now = System.currentTimeMillis()
            if (MarketHours.closedFor(symbol, now)) {
                update(symbol, "closed", "بازار تعطیل است؛ اسکن موقتاً متوقف شد")
                return@forEachIndexed
            }
            if (trades.any { it.symbol == symbol && it.isOpen }) {
                update(symbol, "blocked", "پوزیشن کاغذی این نماد باز است؛ فرصت جدید اسکن نمی‌شود")
                return@forEachIndexed
            }

            suspend fun publicOrDukascopy(): List<Candle> = try {
                publicHistory.fetchCandles(symbol, interval,
                    minimumSize = HistoryPolicy.LIVE_MIN_CANDLES,
                    desiredSize = HistoryPolicy.LIVE_FETCH_CANDLES).candles
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (publicFailure: Exception) {
                try {
                    dukascopyHistory.fetchCandles(symbol, interval,
                        minimumSize = HistoryPolicy.LIVE_MIN_CANDLES,
                        desiredSize = HistoryPolicy.LIVE_FETCH_CANDLES).candles
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (deepFailure: Exception) {
                    throw DataFeedException("عمومی: ${publicFailure.message ?: "ناموفق"}؛ Dukascopy: ${deepFailure.message ?: "ناموفق"}".take(180))
                }
            }

            val rawCandles = try {
                if (CryptoCatalog.isCrypto(symbol)) {
                    try {
                        nobitexHistory.fetchCandles(symbol, interval,
                            HistoryPolicy.LIVE_FETCH_CANDLES, HistoryPolicy.LIVE_MIN_CANDLES).candles
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // A keyed exchange feed may carry the exact USDT pair. Never silently
                        // substitute a USD quote for it if both sources are unavailable.
                        if (config.hasKey) client.fetchCandles(config.apiKey, symbol, interval,
                            outputSize = HistoryPolicy.LIVE_FETCH_CANDLES,
                            minimumOutputSize = HistoryPolicy.LIVE_MIN_CANDLES)
                        else throw DataFeedException("فید واقعی ${symbol} از نوبیتکس در دسترس نیست؛ دادهٔ USD جایگزین USDT نمی‌شود")
                    }
                } else if (config.hasKey) {
                    try {
                        client.fetchCandles(config.apiKey, symbol, interval, outputSize = HistoryPolicy.LIVE_FETCH_CANDLES,
                            minimumOutputSize = HistoryPolicy.LIVE_MIN_CANDLES)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        publicOrDukascopy()
                    }
                } else {
                    publicOrDukascopy()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                update(symbol, "error", (error.message ?: "خطای دریافت کندل").take(180))
                return@forEachIndexed
            }

            // Provider flags can mark an in-progress bar as closed. Never score, settle or
            // construct MTF snapshots from it, even when its OHLC parses correctly.
            val candles = rawCandles.filter { it.closed && it.time + interval.millis <= now }
                .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
            if (candles.size < HistoryPolicy.LIVE_MIN_CANDLES) {
                update(symbol, "error", "بازهٔ ${interval.label} فقط ${candles.size} کندل بستهٔ واقعی دارد؛ ${HistoryPolicy.LIVE_MIN_CANDLES} لازم است")
                return@forEachIndexed
            }

            val price = candles.lastOrNull()?.close
            val lastClosed = candles.lastOrNull { it.closed && it.time + interval.millis <= now }
            // A REST candle close is not a live tick. Never settle a position as if it were one.
            if (lastClosed != null) {
                try {
                    journal.settle(lastClosed, symbol, now)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    update(symbol, "error", "تسویهٔ ژورنال این نماد ناموفق بود؛ سیگنال جدید ارزیابی نشد", price)
                    return@forEachIndexed
                }
            }
            // «روند کلی بازار چطور به دست می‌آید؟» — لایهٔ اول: از همان کندل‌های بستهٔ واقعیِ
            // همین نماد، هم روی تایم‌فریم پایه و هم روی تایم‌فریم مرجع (تجمیع‌شده، نه ساخته‌شده).
            val trend = withContext(Dispatchers.Default) {
                runCatching { MarketTrend.symbolTrend(symbol, candles, interval) }.getOrNull()
            }
            if (trend != null && trend.known) trendSample += trend

            // Playbook is market context only (not a fifth V1 gate). The scanner and chart
            // both send the same selected category mode and independently fetched frames into
            // the same SignalEngine; absolute locks are enforced there and by paper risk rules.
            val playbook = withContext(Dispatchers.Default) {
                runCatching { MarketPlaybook.assess(symbol, candles, interval, now) }.getOrNull()
            }
            // The playbook is context only; V1Scoring has the absolute market locks.

            val frames = try {
                timeframeFeed.fetch(symbol, interval, candles, config.apiKey)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                update(symbol, "error", "دریافت بازه‌های زمانی ناموفق بود: ${(error.message ?: "خطای فید").take(100)}",
                    price, methodLabel = playbook?.method?.label,
                    playbookAllowed = playbook?.allowed, playbookReason = playbook?.reasonFa)
                return@forEachIndexed
            }
            val evaluated = withContext(Dispatchers.Default) {
                try {
                    SignalEngine.evaluateLive(candles, interval, config, symbol, frames)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
            }
            if (evaluated == null) {
                update(symbol, "error", "خطای محاسبهٔ موتور فنی؛ ورود متوقف شد", price)
                return@forEachIndexed
            }

            val combined = NewsConfluence.apply(evaluated, symbol, headlines, now)!!
            val score = combined.confluence.take(NewsConfluence.TECHNICAL_COUNT).count { it.ok }
            if (!combined.isActionable) {
                update(
                    symbol = symbol,
                    state = "no_signal",
                    detail = if (combined.blockers.isNotEmpty()) "امتیاز ${combined.confidence.toInt()}/۱۰۰ · ${combined.blockers.joinToString("، ").take(120)}" else "بدون سیگنال؛ شواهد فنی $score از ۴",
                    price = price,
                    score = score,
                    action = combined.action,
                    confidence = combined.confidence,
                    entry = combined.entry,
                    sl = combined.stopLoss,
                    tp = combined.takeProfit,
                    rr = combined.riskReward,
                    conditions = combined.confluence,
                    methodLabel = playbook?.method?.label,
                    playbookAllowed = playbook?.allowed,
                    playbookReason = playbook?.reasonFa?.take(160),
                    trend = trend,
                )
                return@forEachIndexed
            }

            // ── لایهٔ دوم و سوم: عرض بازار + جهت دلار + جوّ ریسک، و اثرشان روی همین ورود ──
            // روش‌های روندی خلاف جهتِ اندازه‌گیری‌شده مسدود می‌شوند؛ بازگشت به میانگین فقط در
            // بازارِ بدون روند؛ و ورودِ دارایی ریسکی خلافِ جوّ بازار با کف اطمینانِ سخت‌تر.
            val alignment = withContext(Dispatchers.Default) {
                runCatching { MarketTrend.alignmentOf(combined.action, trend) }.getOrNull()
            }
            val trendGate = withContext(Dispatchers.Default) {
                runCatching {
                    MarketTrend.entryGate(combined.action, playbook?.method, trend, overallTrend)
                }.getOrNull()
            }
            val aligned = alignment == TrendAlignment.WITH
            // The four-layer user threshold is authoritative, not a second trend/session bar.

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
                System.currentTimeMillis(), allowedSymbols = config.activeWatchlist, barAgeGraceMs = graceMs)
            if (blocker != null) {
                // A technically actionable closed-bar read is still useful when alerts,
                // monitor, watchlist, live quote or risk permission are absent. Rank it
                // read-only; NEVER persist/notify/open it as a paper opportunity.
                update(symbol = symbol, state = "observed",
                    detail = "فقط سیگنال فنی؛ ورود/اعلان مجاز نیست: ${blocker.take(160)}",
                    price = price, score = score, action = combined.action,
                    confidence = combined.confidence, entry = combined.entry,
                    sl = combined.stopLoss, tp = combined.takeProfit, rr = combined.riskReward,
                    conditions = combined.confluence, methodLabel = playbook?.method?.label,
                    playbookAllowed = playbook?.allowed,
                    playbookReason = playbook?.reasonFa?.take(160), trend = trend,
                    trendAligned = aligned, trendNote = trendGate?.noteFa?.take(200))
                return@forEachIndexed
            }

            val evidence = NewsConfluence.record(headlines, symbol)
            val ict = IctEntryRules.approvedEvidence(market, System.currentTimeMillis(), graceMs)


            val item = runCatching {
                val mtfRec = MtfSnapshotRecord.from(mtf ?: error("نمایش MTF پایه موجود نیست"))
                PaperOpportunity.from(combined, symbol, price ?: combined.entry ?: 0.0, mtfRec, evidence, ict)
            }.getOrNull()

            if (item == null) {
                update(symbol = symbol, state = "blocked",
                    detail = "دادهٔ MTF پایه برای نمایش فرصت موجود نیست",
                    price = price, score = score, conditions = combined.confluence,
                    trend = trend, trendAligned = aligned)
                return@forEachIndexed
            }
            val recorded = runCatching { opportunities.record(item) }.getOrDefault(false)
            if (recorded) {
                try {
                    onCandidate?.invoke(item)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // A notification failure must not stop analysis of the remaining symbols.
                    // The opportunity is already persisted; do not emit a false entry alert.
                }
            }

            // Radar history may suggest candidates, but cannot auto-open a trade without a
            // separate fresh, verified quote in the selected live-market path.
            update(
                symbol = symbol,
                state = "candidate",
                detail = "فرصت معاملاتی تایید شد" +
                    (playbook?.let { " · متد ${it.method.label}" } ?: "") +
                    (trend?.let { " · روند ${it.direction.label} ${it.strength}٪" } ?: "") +
                    (overallTrend?.takeIf { it.known }?.let { " · بازار ${it.bias.label}" } ?: "") +
                    " · امتیاز فنی ${combined.confidence.toInt()}/۱۰۰ · R:R 1:${combined.riskReward?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "نامعلوم"}",
                price = price,
                score = score,
                action = combined.action,
                confidence = combined.confidence,
                entry = combined.entry,
                sl = combined.stopLoss,
                tp = combined.takeProfit,
                rr = combined.riskReward,
                conditions = combined.confluence,
                methodLabel = playbook?.method?.label,
                playbookAllowed = playbook?.allowed,
                playbookReason = playbook?.reasonFa?.take(160),
                trend = trend,
                trendAligned = aligned,
                trendNote = trendGate?.noteFa?.take(200),
            )
        }
    }

    companion object {
        const val SWEEP_PERIOD_MS = 30_000L
        const val PAIR_SPACING_MS = 1_000L
    }
}
