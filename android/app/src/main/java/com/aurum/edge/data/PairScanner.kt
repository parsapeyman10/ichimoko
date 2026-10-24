package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.domain.Interval
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
import com.aurum.edge.core.MarketState

import com.aurum.edge.domain.SignalAction
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.SignalEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One row of the universe radar: what the continuous sweep observed for this instrument. */
data class PairScanStatus(
    val symbol: String,
    /** pending | closed | error | partial | no_signal | watch | blocked | observed | candidate | opened */
    val state: String,
    val detail: String,
    val interval: Interval? = null,
    val lastScanAt: Long? = null,
    /** Intended exact-pair fallback order; not proof of a reachable source. */
    val sourcePlan: String? = null,
    /** Primary historical candle publisher; not proof of a same-venue entry/exit tick. */
    val baseSource: String? = null,
    val lastBarAt: Long? = null,
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
    /** ALS-5 pre-confirmation monitor record; present for XAU/USD, including WAIT/NO_TRADE. */
    val als5Opportunity: com.aurum.edge.core.Als5OpportunityRecord? = null,
    /** BTC-ARES per-engine record; present for BTC/USDT only, including NO_TRADE. */
    val btcAres: com.aurum.edge.core.BtcAresRecord? = null,
    /** ALS-5S per-engine record; present for XAG/USD only, including NO_TRADE. */
    val als5s: com.aurum.edge.core.XagAresRecord? = null,
    /** EUR-ARES per-engine record; present for EUR/USD only, including NO_TRADE. */
    val eurAres: com.aurum.edge.core.EurAresRecord? = null,
)

data class PairScanState(
    val statuses: List<PairScanStatus> = emptyList(),
    val sweeping: Boolean = false,
    /** Rows with an independently completed check, including closed/error rows. */
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val lastSweepAt: Long? = null,
    /** Elapsed time of the most recently completed symbol engine; never a universe-wide SLA. */
    val lastSweepDurationMs: Long? = null,
    val lastError: String? = null,
    /** The Top 3 highest-ranking opportunities from the universe scan. */
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
    private val journal: JournalStore,
    private val opportunities: PaperOpportunityStore,
    private val scope: CoroutineScope,
    private val dukascopyHistory: DukascopyHistoryClient = DukascopyHistoryClient(),
    private val decisionLog: DecisionLogStore? = null,
    private val binanceHistory: BinanceSpotClient = BinanceSpotClient(),
) {
    private val workers = SymbolWorkers(scope) { SystemClock.elapsedRealtime() }
    /** Radar history is bar-scoped, never clock-scoped: see [ScanHistoryCache]. */
    private val historyCache = ScanHistoryCache()
    private data class ScanHistory(
        val provider: String,
        val frames: Map<Interval, List<com.aurum.edge.domain.Candle>>,
        val integrityRevision: Long,
    )
    private val activeWorkers = java.util.concurrent.atomic.AtomicInteger()
    private val autoReviewMutex = Mutex() // serialize the non-atomic AI/entry review across scanner workers
    private var onAutoSignal: (suspend (MarketState) -> CatalogAutoReview)? = null

    /** Wired once by the process container. Scanning never calls this unless paper auto is on. */
    fun attachAutoReview(callback: suspend (MarketState) -> CatalogAutoReview) {
        onAutoSignal = callback
    }

    private val nonCryptoBundle = NonCryptoRadarBundle { symbol, frame, source, apiKey ->
        when {
            source.usesYahoo -> publicHistory.fetchCandles(symbol, frame,
                minimumSize = HistoryPolicy.minSize(frame), desiredSize = HistoryPolicy.fetchSize(frame))
            source.usesDukascopy -> dukascopyHistory.fetchCandles(symbol, frame,
                minimumSize = HistoryPolicy.minSize(frame), desiredSize = HistoryPolicy.fetchSize(frame))
                .let { PublicCandleHistoryClient.Result(it.candles, it.provider, it.fetchedAt) }
            else -> PublicCandleHistoryClient.Result(
                withContext(Dispatchers.IO) { client.fetchCandles(apiKey, symbol, frame,
                    outputSize = HistoryPolicy.fetchSize(frame),
                    minimumOutputSize = HistoryPolicy.minSize(frame)) }, "Twelve Data · fallback")
        }
    }
    private val cryptoReadOnly = CryptoRadarCandleClient()
    private val nobitexCrypto = NobitexCryptoBundle()
    // Read-only quote path: Nobitex executed trade first, per-symbol exchange as fallback.
    private val spotQuote = SpotFallbackClient()
    private val cryptoBundle = CryptoRadarBundle { symbol, interval, source ->
        cryptoReadOnly.fetch(symbol, interval, source.venue)
    }

    /**
     * Crypto candles for the sweep: the Nobitex REFERENCE first, then the per-symbol exchange
     * as a labelled read-only fallback. Failures from both are reported together; a fallback
     * venue never inherits the Nobitex label and cannot authorize a Nobitex entry.
     */
    private suspend fun cryptoBook(symbol: String, now: Long): CryptoHistoryBook {
        var nobitexFailure: Exception? = null
        if (NobitexReference.serves(symbol)) {
            try {
                return nobitexCrypto.fetch(symbol, now)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                nobitexFailure = failure
            }
        }
        try {
            return cryptoBundle.fetch(symbol, now)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (nobitexFailure == null) throw failure
            throw DataFeedException(
                "$symbol: نوبیتکس (مرجع رمزارز) پاسخ نداد: " +
                    "${(nobitexFailure.message ?: "خطای نامشخص").take(90)}؛ جایگزین فقط‌خواندنی هم ناموفق بود: " +
                    (failure.message ?: "بدون داده").take(90))
        }
    }
    /**
     * Newest closed XAU/USD bars from this scanner. The silver engine uses them only as an aligned
     * context module; when they are missing or out of step the module reports UNAVAILABLE and
     * silver keeps working alone. Never fetched by the silver evaluation itself.
     */
    @Volatile private var lastGoldBars: List<com.aurum.edge.domain.Candle> = emptyList()

    /** One latest real trend sample per symbol, independently expired. */
    private val trendSample = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SymbolTrend>>()
    private val _state = MutableStateFlow(PairScanState(
        statuses = com.aurum.edge.core.V1Universe.defaults.map { PairScanStatus(it, "pending", "هنوز اسکن نشده") }))
    val state: StateFlow<PairScanState> = _state.asStateFlow()

    fun refreshNow(minIntervalMs: Long = -1L,
                   onCandidate: (suspend (PaperOpportunity) -> Unit)? = null) {
        schedule(effectiveInterval(minIntervalMs), onCandidate)
    }

    /**
     * The scanner's own per-symbol throttle IS the app-wide cadence: 60s normally,
     * 20s while any symbol is a live candidate / near entry (MonitorCadence).
     * Callers may still force an explicit interval (0 = event-driven immediate
     * re-check after a network/settings change).
     */
    private fun effectiveInterval(requested: Long): Long =
        if (requested >= 0) requested
        else com.aurum.edge.core.MonitorCadence.sweepDelayMs(
            state.value.statuses.map { it.state to it.confidence })

    private fun schedule(minIntervalMs: Long, onCandidate: (suspend (PaperOpportunity) -> Unit)?): List<Job> {
        val config = settings.read()
        val universe = config.activeSymbols
        _state.update { old -> old.copy(totalCount = universe.size,
            statuses = universe.map { symbol -> old.statuses.find { it.symbol == symbol }
                ?: PairScanStatus(symbol, "pending", "موتور مستقل این نماد هنوز داده ندارد",
                    sourcePlan = SourceRouting.historyPlan(symbol, config.hasKeyFor(symbol))) }) }
        // Randomize admission order, so scarce publisher slots do not always favor the
        // first catalog rows. No symbol waits for the last symbol of a previous pass.
        return universe.shuffled().mapNotNull { symbol -> workers.submit(symbol, minIntervalMs) {
            val started = SystemClock.elapsedRealtime()
            activeWorkers.incrementAndGet()
            _state.update { it.copy(sweeping = true) }
            try {
                if (!AppNetwork.route.value.ready) {
                    publishFailure(symbol, "waiting_connection", "شبکهٔ گوشی آماده نیست؛ درخواست به منبع ارسال نشد")
                } else if (withTimeoutOrNull(180_000L) {
                    do {
                        sweepAll(onCandidate, listOf(symbol))
                        val occupied = state.value.statuses.find { it.symbol == symbol }?.state == "capacity"
                        if (occupied) kotlinx.coroutines.delay(kotlin.random.Random.nextLong(1_000L, 4_000L))
                    } while (occupied && AppNetwork.route.value.ready)
                    true
                } != true) {
                    publishFailure(symbol, "error", "مهلت موتور این نماد تمام شد؛ نمادهای دیگر مستقل ادامه دارند")
                }
            } catch (cancel: CancellationException) {
                publishFailure(symbol, "pending", "بررسی این نماد لغو شد؛ سیگنال قبلی مجوز ورود نیست")
                throw cancel
            } catch (_: Exception) {
                publishFailure(symbol, "error", "موتور این نماد پاسخ معتبر نگرفت؛ تلاش بعدی مستقل است")
            } finally {
                activeWorkers.decrementAndGet()
                _state.update { it.copy(sweeping = activeWorkers.get() > 0,
                    lastSweepAt = System.currentTimeMillis(),
                    lastSweepDurationMs = SystemClock.elapsedRealtime() - started) }
            }
        } }
    }

    private fun publishFailure(symbol: String, state: String, detail: String) {
        trendSample.remove(symbol)
        historyCache.invalidate(symbol) // the next attempt must re-read, not replay a bad bundle
        _state.update { old ->
            val rows = old.statuses.map { if (it.symbol == symbol) PairScanStatus(symbol, state, detail,
                lastScanAt = System.currentTimeMillis(), sourcePlan = SourceRouting.historyPlan(symbol, settings.read().hasKeyFor(symbol))) else it }
            val ranked = rankOpportunities(rows)
            old.copy(statuses = rows, checkedCount = rows.count { it.lastScanAt != null },
                topThree = ranked.take(3), bestPick = ranked.firstOrNull(), marketTrend = freshTrend())
        }
    }

    private fun freshTrend(): MarketTrendRead? {
        val now = System.currentTimeMillis()
        val fresh = trendSample.values.filter { now - it.first in 0L..600_000L }
        return if (fresh.isEmpty()) null else runCatching {
            MarketTrend.overall(fresh.map { it.second }).copy(computedAt = fresh.minOf { it.first })
        }.getOrNull()
    }

    private fun rankOpportunities(statuses: List<PairScanStatus>): List<PairScanStatus> =
        ScanRanking.rank(statuses, Interval.M5, System.currentTimeMillis())

    private suspend fun sweepAll(onCandidate: (suspend (PaperOpportunity) -> Unit)?, universe: List<String>) {
        universe.forEach { trendSample.remove(it) }
        // Rolling view of independently completed engines. Expiration is anchored to
        // the oldest included sample, never refreshed merely by an unrelated tick.
        val overallTrend = _state.value.marketTrend?.takeIf {
            System.currentTimeMillis() - it.computedAt in 0L..10 * 60_000L
        }
        val config = settings.read()
        // Every catalog instrument uses its category settings. Retired manual watchlists
        // cannot narrow scanning or alert authorization. Missing feeds remain explicit errors.
        // A result from the previous sweep cannot remain a current recommendation while
        // the provider is still being checked this time (or after a configuration change).
        val statuses = mutableMapOf<String, PairScanStatus>()
        val chosenIntervals = java.util.concurrent.ConcurrentHashMap<String, Interval>()
        val baseSources = java.util.concurrent.ConcurrentHashMap<String, String>()
        val barTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val statusLock = Any()
        _state.update { old ->
            val rows = old.statuses.map { row -> if (row.symbol in universe)
                PairScanStatus(row.symbol, "pending", "موتور مستقل در حال دریافت و مقایسهٔ منابع",
                    sourcePlan = SourceRouting.historyPlan(row.symbol, config.hasKeyFor(row.symbol))) else row }
            val ranked = rankOpportunities(rows)
            old.copy(statuses = rows, topThree = ranked.take(3), bestPick = ranked.firstOrNull())
        }
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
            als5Opportunity: com.aurum.edge.core.Als5OpportunityRecord? = null,
            btcAres: com.aurum.edge.core.BtcAresRecord? = null,
            als5s: com.aurum.edge.core.XagAresRecord? = null,
            eurAres: com.aurum.edge.core.EurAresRecord? = null,
        ) = synchronized(statusLock) {
            if (state != "capacity") decisionLog?.append(symbol, (chosenIntervals[symbol] ?: Interval.M5).label, state, detail,
                signal = if (conditions.isNotEmpty()) com.aurum.edge.core.Signal(
                    action ?: SignalAction.NO_TRADE, confidence ?: 0.0, confluence = conditions)
                    else null, dedupe = false, method = methodLabel, methodReason = playbookReason)
            statuses[symbol] = PairScanStatus(
                symbol = symbol,
                state = state,
                detail = detail,
                interval = chosenIntervals[symbol],
                lastScanAt = System.currentTimeMillis(),
                sourcePlan = SourceRouting.historyPlan(symbol, config.hasKeyFor(symbol)),
                baseSource = baseSources[symbol],
                lastBarAt = barTimes[symbol],
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
                als5Opportunity = als5Opportunity,
                btcAres = btcAres,
                als5s = als5s,
                eurAres = eurAres,
            )
            _state.update { old ->
                val currentList = old.statuses.map { statuses[it.symbol] ?: it }
                val ranked = rankOpportunities(currentList)
                old.copy(statuses = currentList,
                    checkedCount = currentList.count { it.lastScanAt != null },
                    topThree = ranked.take(3), bestPick = ranked.firstOrNull(), marketTrend = freshTrend())
            }
        }


        // All symbols start without waiting for an asset-class slot. Only each external
        // publisher's own HTTP capacity is bounded; position monitoring is independent.
        // This is NOT a sub-second multi-source network guarantee.
        coroutineScope {
            universe.map { symbol -> async {
                // A closed market or already-open position does not need a network slot.
                // Publish these statuses immediately, without waiting behind HTTP requests.
                val preflightNow = System.currentTimeMillis()
                if (MarketHours.closedFor(symbol, preflightNow)) {
                    update(symbol, "closed", "بازار طبق ساعت سشن بسته است؛ درخواست فید انجام نمی‌شود")
                    return@async
                }
                if (journal.trades.value.any { it.symbol == symbol && it.isOpen }) {
                    update(symbol, "blocked", "پوزیشن کاغذی این نماد باز است؛ فرصت جدید اسکن نمی‌شود")
                    return@async
                }
                val crypto = CryptoCatalog.isCrypto(symbol)
                // All NEW entries and radar candidates have a closed 5m base, regardless
                // of the selected chart. M15/H1 are independent alignment inputs ONLY.
                var interval = Interval.M5
                var now = System.currentTimeMillis()
                if (MarketHours.closedFor(symbol, now)) {
                    // Schedule only: do not fetch candles, evaluate, rank or attempt entry for
                    // this closed market. Other markets (notably 24/7 crypto) remain independent.
                    update(symbol, "closed", "بازار طبق ساعت سشن بسته است؛ تا بازگشایی هیچ درخواست فید یا ارزیابی سیگنال انجام نمی‌شود")
                    return@async
                }
                // Re-read before any publisher request: a new open position outranks scanning.
                if (journal.trades.value.any { it.symbol == symbol && it.isOpen }) {
                    update(symbol, "blocked", "پوزیشن کاغذی این نماد باز است؛ فرصت جدید اسکن نمی‌شود")
                    return@async
                }

                // Complete bundles are reused for the rest of their M5 bar; everything else
                // (tick, spread, entry review, ranking) still runs on every sweep.
                val history: ScanHistory = historyCache.get(symbol, now)
                    ?.let { ScanHistory(it.provider, it.frames, it.integrityRevision) }
                    ?: if (crypto) {
                        val book = try {
                            cryptoBook(symbol, now)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            if (!AppNetwork.route.value.ready) {
                                update(symbol, "waiting_connection", "شبکهٔ گوشی آماده نیست؛ تحلیل و ورود انجام نشد")
                                return@async
                            }
                            // A venue may supply a FRESH, exact-pair trade even when it cannot
                            // supply 320 contiguous closed candles. Show that price as a
                            // READ-ONLY partial observation, never as a candle, signal or fill.
                            if (failure is PublisherOccupied) {
                                update(symbol, "capacity", failure.message.orEmpty())
                                return@async
                            }
                            val quote = try {
                                spotQuote.fetchQuote(symbol)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                null
                            }
                            if (quote != null) {
                                update(symbol, "partial",
                                    "${quote.provider ?: "منبع تیک"}: معاملهٔ زمان‌دار ${quote.at}؛ " +
                                        "۳۲۰ کندل بستهٔ M5/M15/H1 تأیید نشد؛ فقط قیمت مشاهده‌ای، بدون سیگنال/ورود",
                                    price = quote.price)
                            } else update(symbol, "error", (failure.message ?: "منبع هم‌نماد موجود نیست").take(420))
                            return@async
                        }
                        ScanHistory(book.provider, book.frames, book.integrityRevision)
                    } else {
                        val snapshot = try {
                            nonCryptoBundle.fetch(symbol, config.apiKeyFor(symbol), now)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            update(symbol, if (failure is PublisherOccupied) "capacity" else "error",
                                (failure.message ?: "منبع هم‌نماد موجود نیست").take(420))
                            return@async
                        }
                        ScanHistory(snapshot.provider, snapshot.frames, snapshot.integrityRevision)
                    }
                // Network permits and fallback can outlast a bar boundary. Use receipt time,
                // not the clock captured before the publisher wait.
                now = System.currentTimeMillis()
                val integrityRevision = history.integrityRevision
                SourceIntegrity.shared.blocker(symbol, integrityRevision)?.let { reason ->
                    // The stored bundle belongs to a rejected revision: never reuse it.
                    historyCache.invalidate(symbol)
                    update(symbol, "blocked", reason)
                    return@async
                }
                val rawCandles = history.frames[Interval.M5].orEmpty()
                val baseSource = history.provider
                val frames = history.frames
                baseSources[symbol] = baseSource
                // Provider flags can mark an in-progress bar as closed. Never score, settle or
                // construct MTF snapshots from it, even when its OHLC parses correctly.
                var candles = rawCandles.filter { it.closed && it.time + interval.millis <= now }
                    .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
                candles.lastOrNull()?.let { barTimes[symbol] = it.time + interval.millis }
                // The silver model reads this aligned snapshot as *context only*; it is refreshed
                // here because XAU/USD is scanned by this same sweep.
                if (symbol == "XAU/USD") lastGoldBars = candles.takeLast(120)
                if (candles.size < HistoryPolicy.LIVE_MIN_CANDLES) {
                    historyCache.invalidate(symbol) // an incomplete bundle must be re-read next time
                    update(symbol, "partial", "فقط مشاهدهٔ کندل قبلی ${candles.lastOrNull()?.time ?: "—"}: " +
                        "${candles.size} کندل بستهٔ واقعی در ${interval.label}؛ برای سیگنال/معامله ${HistoryPolicy.LIVE_MIN_CANDLES} لازم است",
                        price = candles.lastOrNull()?.close)
                    return@async
                }
                val candidates = listOf(Interval.M5)
                val reads = mutableListOf<CryptoIntervalChoice.Read>()
                val unavailable = mutableListOf<String>()
                for (candidate in candidates) {
                    val bars = when (candidate) {
                        interval -> candles
                        else -> frames[candidate].orEmpty()
                    }.filter { it.closed && it.time + candidate.millis <= System.currentTimeMillis() }
                        .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
                    if (bars.size < HistoryPolicy.LIVE_MIN_CANDLES) {
                        unavailable += "${candidate.label}: کمتر از ${HistoryPolicy.LIVE_MIN_CANDLES} کندل بسته"
                        continue
                    }
                    val evaluated = withContext(Dispatchers.Default) {
                        try {
                            SignalEngine.evaluateLive(bars, candidate, config, symbol, frames,
                                goldBars = if (symbol == "XAG/USD") lastGoldBars else emptyList())
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                    }
                    if (evaluated == null) unavailable += "${candidate.label}: محاسبه ناموفق"
                    else reads += CryptoIntervalChoice.Read(candidate, bars, evaluated)
                }
                val selected = CryptoIntervalChoice.pick(reads) ?: run {
                    update(symbol, "partial", "هیچ‌یک از بازه‌های ${candidates.joinToString { it.label }} قابل ارزیابی نبود: " +
                        unavailable.joinToString("؛ ").take(130))
                    return@async
                }
                interval = selected.interval
                chosenIntervals[symbol] = interval
                candles = selected.bars
                val evaluated = selected.signal


                val price = candles.lastOrNull()?.close
                barTimes[symbol] = candles.last().time + interval.millis
                val lastClosed = candles.lastOrNull { it.closed && it.time + interval.millis <= now }
                // A newly fetched response can still contain only old bars. Scan timestamp alone
                // must never make that history look fresh in rankings or the market-wide trend.
                if (lastClosed == null || now - (lastClosed.time + interval.millis) !in
                    0L..(interval.millis + 90_000L)) {
                    historyCache.invalidate(symbol) // a stale source must be re-read, not reused
                    update(symbol, "partial", "آخرین کندل بستهٔ منبع قدیمی است؛ رتبه/روند تازه ساخته نشد",
                        price = price)
                    return@async
                }
                // Cache only what proved complete: three healthy frames ending on the live bar.
                historyCache.record(symbol, baseSource, integrityRevision, frames, now)
                // A REST candle close is not a live tick. Never settle a position as if it were one.
                if (lastClosed != null && !crypto) {
                    try {
                        journal.settle(lastClosed, symbol, now)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        update(symbol, "error", "تسویهٔ ژورنال این نماد ناموفق بود؛ سیگنال جدید ارزیابی نشد", price)
                        return@async
                    }
                }
                // «روند کلی بازار چطور به دست می‌آید؟» — لایهٔ اول: از همان کندل‌های بستهٔ واقعیِ
                // همین نماد، هم روی تایم‌فریم پایه و هم روی تایم‌فریم مرجع (تجمیع‌شده، نه ساخته‌شده).
                val trend = withContext(Dispatchers.Default) {
                    runCatching { MarketTrend.symbolTrend(symbol, candles, interval,
                        higherCandles = MarketTrend.higherFor(interval)?.let { frames[it] }.orEmpty()) }.getOrNull()
                }
                if (trend != null && trend.known) trendSample[symbol] = System.currentTimeMillis() to trend

                // Playbook is market context only (not a fifth V1 gate). The scanner and chart
                // both send the same selected category mode and independently fetched frames into
                // the same SignalEngine; absolute locks are enforced there and by paper risk rules.
                val playbook = withContext(Dispatchers.Default) {
                    runCatching { MarketPlaybook.assess(symbol, candles, interval, now) }.getOrNull()
                }
                // The playbook is context only; V1Scoring has the absolute market locks.

                val combined = evaluated
                val score = com.aurum.edge.core.TechnicalEvidence.items(combined).count { it.ok }
                if (!combined.isActionable) {
                    update(
                        symbol = symbol,
                        state = if (crypto && combined.blockers.any { it.startsWith("واچ‌لیست") }) "watch" else "no_signal",
                        detail = "بازهٔ ${interval.label} از مقایسهٔ ${candidates.joinToString { it.label }} · " +
                            (if (combined.blockers.isNotEmpty()) "امتیاز ${combined.confidence.toInt()}/۱۰۰ · ${combined.blockers.joinToString("، ").take(95)}" else "بدون سیگنال؛ شواهد فنی $score از ۴") +
                            (if (unavailable.isNotEmpty()) " · ${unavailable.joinToString("؛ ").take(90)}" else ""),
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
                        als5Opportunity = combined.als5Opportunity, btcAres = combined.btcAres,
                        als5s = combined.als5s, eurAres = combined.eurAres,
                    )
                    return@async
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
                    sourceIntegrityRevision = integrityRevision,
                    feed = FeedStatus(FeedMode.POLLING,
                        "تاریخچهٔ بستهٔ $baseSource؛ تیک اجرا و اسپرد جداگانه لازم است",
                        System.currentTimeMillis(), provider = baseSource),
                    signal = combined,
                    // Only the ACTUAL publisher can authorize a same-venue entry. Radar fallback
                    // candles from Gate/OKX are never relabelled as Binance history.
                    historyProvider = baseSource,

                )
                val mtf = withContext(Dispatchers.Default) {
                    runCatching { MtfAnalyzer.analyzeFrames(frames, interval) }.getOrNull()
                }
                // This runs for EVERY supported catalog row, not only editable watchlist rows.
                // The callback MUST obtain a separate same-symbol trade tick and route through
                // the atomic JournalStore; this historical candle must never act as the fill.
                val autoReview = if (config.autoPaperTrading &&
                    symbol in WatchCatalog.scannerSymbols) try {
                    autoReviewMutex.withLock { onAutoSignal?.invoke(market) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    CatalogAutoReview(reason = "بازبینی ورود کاغذی کاتالوگ ناموفق بود")
                } else null
                if (autoReview?.trade != null) {
                    update(symbol, "opened", "معاملهٔ کاغذی در ${interval.label} با قیمت تازهٔ مستقل ثبت شد؛ " +
                        "کندل اسکن قیمت ورود نبود", price, score, combined.action,
                        combined.confidence, combined.entry, combined.stopLoss, combined.takeProfit,
                        combined.riskReward, combined.confluence, playbook?.method?.label,
                        playbook?.allowed, playbook?.reasonFa?.take(160), trend, aligned,
                        trendGate?.noteFa?.take(200), combined.als5Opportunity)
                    return@async
                }
                val blocker = PaperAlertRules.blocker(market, config, journal.trades.value, mtf,
                    System.currentTimeMillis(), allowedSymbols = config.activeSymbols, barAgeGraceMs = graceMs)
                if (blocker != null) {
                    // A technically actionable closed-bar read is still useful when alerts,
                    // monitor, live quote or risk permission are absent. Rank it
                    // read-only; NEVER persist/notify/open it as a paper opportunity.
                    update(symbol = symbol, state = "observed",
                        detail = "فقط سیگنال فنی؛ مجوز ورود/اعلان نیست: ${blocker.take(110)}" +
                            (autoReview?.reason?.let { " · خودکار: ${it.take(140)}" } ?: ""),
                        price = price, score = score, action = combined.action,
                        confidence = combined.confidence, entry = combined.entry,
                        sl = combined.stopLoss, tp = combined.takeProfit, rr = combined.riskReward,
                        conditions = combined.confluence, methodLabel = playbook?.method?.label,
                        playbookAllowed = playbook?.allowed,
                        playbookReason = playbook?.reasonFa?.take(160), trend = trend,
                        trendAligned = aligned, trendNote = trendGate?.noteFa?.take(200),
                        als5Opportunity = combined.als5Opportunity, btcAres = combined.btcAres,
                        als5s = combined.als5s, eurAres = combined.eurAres)
                    return@async
                }

                val ict = IctEntryRules.approvedEvidence(market, System.currentTimeMillis(), graceMs)


                val item = runCatching {
                    val mtfRec = MtfSnapshotRecord.from(mtf ?: error("نمایش MTF پایه موجود نیست"))
                    PaperOpportunity.from(combined, symbol, price ?: combined.entry ?: 0.0, mtfRec, ict)
                }.getOrNull()

                if (item == null) {
                    update(symbol = symbol, state = "blocked",
                        detail = "دادهٔ MTF پایه برای نمایش فرصت موجود نیست",
                        price = price, score = score, conditions = combined.confluence,
                        trend = trend, trendAligned = aligned)
                    return@async
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
                // separate fresh same-venue executed trade tick and atomic journal authorization.
                update(
                    symbol = symbol,
                    state = "candidate",
                    detail = "فرصت معاملاتی ${interval.label} از کندل بستهٔ همان نماد تایید شد" +
                        (autoReview?.reason?.let { " · ورود خودکار انجام نشد: ${it.take(140)}" } ?: "") +
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
                    als5Opportunity = combined.als5Opportunity, btcAres = combined.btcAres,
                        als5s = combined.als5s, eurAres = combined.eurAres,
                )
            } }.awaitAll()
        } // independent symbol evaluations; publisher-limited requests
    }
}
