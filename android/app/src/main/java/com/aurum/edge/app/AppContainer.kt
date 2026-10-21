package com.aurum.edge.app

import android.content.Context
import android.net.Uri
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.Interval
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.DukascopyHistoryClient
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketRepository
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.OpenPositionCoordinator
import com.aurum.edge.data.OpenTradeTickMonitor
import com.aurum.edge.data.SpotFallbackClient
import com.aurum.edge.data.PaperAutoTrader
import com.aurum.edge.data.CatalogPaperTrader
import com.aurum.edge.data.PublicCandleHistoryClient
import com.aurum.edge.data.PaperOpportunityStore
import com.aurum.edge.data.PairScanner
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.SweepCoordinator
import com.aurum.edge.data.TwelveDataClient
import com.aurum.edge.data.QuoteHistoryStore
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.WatchRepository
import com.aurum.edge.data.WatchSettingsStore
import com.aurum.edge.notify.Notifier
import com.aurum.edge.engine.ArbitratorSpec
import com.aurum.edge.engine.EngineSpec
import com.aurum.edge.engine.RiskParams
import com.aurum.edge.engine.StrategyEvaluator
import com.aurum.edge.engine.StrategySpec
import com.aurum.edge.engine.StrategyAdapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Process-wide wiring. Single source of truth for settings, real market data, journal and engine.
 * TradingView is visual only; every V1 entry reads independent candles for the four asset groups.
 */
class AppContainer(context: Context) {

    companion object {
        /** Relative timestamps ("۲ دقیقه پیش") and feed-freshness windows do not need
         * second resolution; 20s keeps every clock reader honest without waking the CPU. */
        private const val CLOCK_PERIOD_MS = 20_000L
    }

    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    init {
        com.aurum.edge.data.AppNetwork.start(appContext)
        com.aurum.edge.data.SourcePromotion.attach(appContext)
    }
    val decisionLog = com.aurum.edge.data.DecisionLogStore(appContext)
    val candleCache = CandleCache(appContext)
    val journalStore = JournalStore(appContext, settingsStore = settingsStore)
    val openTradeTicks = OpenTradeTickMonitor(journalStore, settings = settingsStore)
    /** Process-wide owner of every open-position tick worker (see OpenPositionCoordinator). */
    val spotFallback = SpotFallbackClient()
    val openPositionCoordinator = OpenPositionCoordinator(journalStore, spotFallback, openTradeTicks)

    /** Strategy specifications — declarative, one per engine. These are the data that
     * [StrategyEvaluator] runs; no engine logic lives here. */
    val als5GoldSpec = StrategySpec(
        id = "ALS5_GOLD",
        labelFa = "ALS-5 طلا · XAU/USD",
        symbol = "XAU/USD",
        interval = com.aurum.edge.domain.Interval.M5,
        risk = RiskParams(riskPerTradePct = 0.25, accountEquityUsd = 10_000.0, minRewardRisk = 1.5, maxDailyLossPct = 1.0),
        engines = listOf(
            EngineSpec(id = "ENGINE_A_TREND", labelFa = "موتور A · ادامهٔ روند", enabled = true),
            EngineSpec(id = "ENGINE_B_SWEEP", labelFa = "موتور B · سوییپ نقدینگی", enabled = true),
            EngineSpec(id = "ENGINE_C_RANGE", labelFa = "موتور C · بازگشت به میانگین رنج", enabled = true),
        ),
        arbitrator = ArbitratorSpec(conflictMargin = 15, minScore = 80, enforceNetRewardRisk = true),
        minBars = 280,
        enforceFreshness = true,
        maxSetupAgeBars = 12,
    )
    val btcAresSpec = StrategySpec(
        id = "BTC_ARES",
        labelFa = "BTC-ARES · بیت‌کوین",
        symbol = "BTC/USDT",
        interval = com.aurum.edge.domain.Interval.M5,
        risk = RiskParams(riskPerTradePct = 0.25, accountEquityUsd = 10_000.0, minRewardRisk = 1.5, maxDailyLossPct = 1.0),
        engines = listOf(
            EngineSpec(id = "ENGINE_A_TREND", labelFa = "موتور A · ادامهٔ روند", enabled = true),
            EngineSpec(id = "ENGINE_B_SWEEP", labelFa = "موتور B · شکست ناموفق / سوییپ", enabled = true),
            EngineSpec(id = "ENGINE_C_COMPRESSION", labelFa = "موتور C · شکست فشردگی", enabled = true),
            EngineSpec(id = "ENGINE_D_RANGE", labelFa = "موتور D · رنج و بازگشت", enabled = true),
            EngineSpec(id = "ENGINE_E_EXHAUSTION", labelFa = "موتور E · خستگی مومنتوم", enabled = true),
        ),
        arbitrator = ArbitratorSpec(conflictMargin = 15, minScore = 70, enforceNetRewardRisk = true),
        minBars = 280,
        enforceFreshness = true,
        maxSetupAgeBars = 12,
    )
    val xagAresSpec = StrategySpec(
        id = "ALS5S_SILVER",
        labelFa = "ALS-5S نقره · XAG/USD",
        symbol = "XAG/USD",
        interval = com.aurum.edge.domain.Interval.M5,
        risk = RiskParams(riskPerTradePct = 0.25, accountEquityUsd = 10_000.0, minRewardRisk = 1.5, maxDailyLossPct = 1.0),
        engines = listOf(
            EngineSpec(id = "ENGINE_A_TREND", labelFa = "موتور A · ادامهٔ روند", enabled = true),
            EngineSpec(id = "ENGINE_B_SWEEP", labelFa = "موتور B · سوییپ نقدینگی", enabled = true),
            EngineSpec(id = "ENGINE_C_RANGE", labelFa = "موتور C · بازگشت به میانگین رنج", enabled = true),
            EngineSpec(id = "ENGINE_C_BREAKOUT", labelFa = "موتور C · شکست نوسان", enabled = true),
        ),
        arbitrator = ArbitratorSpec(conflictMargin = 2, minScore = 80, enforceNetRewardRisk = true),
        minBars = 280,
        enforceFreshness = true,
        maxSetupAgeBars = 12,
    )
    val eurAresSpec = StrategySpec(
        id = "EUR_ARES",
        labelFa = "EUR-ARES · یورو/دلار",
        symbol = "EUR/USD",
        interval = com.aurum.edge.domain.Interval.M5,
        risk = RiskParams(riskPerTradePct = 0.25, accountEquityUsd = 10_000.0, minRewardRisk = 1.5, maxDailyLossPct = 1.0),
        engines = listOf(
            EngineSpec(id = "ENGINE_A_TREND", labelFa = "موتور A · ادامهٔ روند و پولبک", enabled = true),
            EngineSpec(id = "ENGINE_B_SWEEP", labelFa = "موتور B · شکست ناموفق / سوییپ", enabled = true),
            EngineSpec(id = "ENGINE_C_RANGE", labelFa = "موتور C · بازگشت به میانگین رنج", enabled = true),
            EngineSpec(id = "ENGINE_D_SESSION", labelFa = "موتور D · شکست رنج سشن", enabled = true),
        ),
        arbitrator = ArbitratorSpec(conflictMargin = 10, minScore = 70, enforceNetRewardRisk = true),
        minBars = 280,
        enforceFreshness = true,
        maxSetupAgeBars = 12,
    )

    /** Strategy evaluator wired with the existing engines via [StrategyAdapters].
     * Additive: [SignalEngine] keeps working unchanged; this is an alternative path. */
    val strategyEvaluator = StrategyEvaluator(engineEvaluators = mapOf(
        "ALS5_GOLD" to { bars, external -> StrategyAdapters.als5Gold(bars, external) },
        "BTC_ARES" to { bars, external -> StrategyAdapters.btcAres(bars, external) },
        "ALS5S_SILVER" to { bars, external -> StrategyAdapters.xagAres(bars, external) },
        "EUR_ARES" to { bars, external -> StrategyAdapters.eurAres(bars, external) },
    ))

    val opportunityStore = PaperOpportunityStore(appContext)
    val client = TwelveDataClient()
    val publicHistory = PublicCandleHistoryClient()
    val dukascopyHistory = DukascopyHistoryClient()
    val market = MarketRepository(appContext, client, candleCache, settingsStore, journalStore,
        publicHistory = publicHistory, dukascopyHistory = dukascopyHistory)
    val updater = AppUpdateRepository(appContext)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * ONE wall clock for the whole app.
     *
     * Before this, six independent `while (true) { delay(...) }` timers ticked separately: one
     * inlined inside [verifiedMarket] below and five more inside Home/Journal/Watch/Signal
     * composables. Every consumer now reads this single flow, so a screen that goes to the
     * background stops its own timer instead of leaving it running until the composable dies.
     * Started eagerly enough to be warm, stopped 5s after the last consumer goes away.
     */
    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_PERIOD_MS)
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), System.currentTimeMillis())
    val watchSettings = WatchSettingsStore(appContext)
    val quoteHistory = QuoteHistoryStore(appContext)
    val watch = WatchRepository(SourceFetcher(), quoteHistory, watchSettings, settingsStore, appScope)
    /** Feed-guard view of the selected market (replaces the old "verified" concept): shows the
     * feed as-delayed instead of silently fresh, nulls the signal when the feed is delayed or a
     * source-integrity conflict is active, and audits every candidate. Shared by chart, signal
     * tab, notifications and automatic *paper* entries. Expires on time. */
    val verifiedMarket: StateFlow<MarketState> = combine(
        market.state, com.aurum.edge.data.SourceIntegrity.shared.state, clock,
    ) { raw, _, _ ->
        // The shared clock only forces recomputation; a one-second market tick must always be
        // judged against the real current clock, not the last clock emission.
        val now = System.currentTimeMillis()
        val observedFeed = FeedLiveness.display(raw.feed, now)
        val delayed = observedFeed.mode == FeedMode.DELAYED
        val integrityBlocker = com.aurum.edge.data.SourceIntegrity.shared.blocker(raw.symbol, raw.sourceIntegrityRevision)
        val verified = raw.copy(feed = observedFeed,
            evaluationError = integrityBlocker ?: raw.evaluationError,
            showingCachedData = raw.showingCachedData || delayed,
            signal = if (delayed || integrityBlocker != null) null else raw.signal)
        if (raw.candles.isNotEmpty()) decisionLog.append(raw.symbol, raw.interval.label,
            when {
                verified.evaluationError != null -> "error"
                verified.signal?.isActionable == true -> "candidate"
                verified.signal == null -> "waiting_data"
                else -> "no_signal"
            },
            verified.evaluationError ?: verified.signal?.blockers?.joinToString("، ")?.ifBlank { "چهار لایهٔ فنی بررسی شدند" }
                ?: "در انتظار دادهٔ معتبر؛ ${observedFeed.provider}: ${observedFeed.detail.take(240)}؛ تحلیل/ورود انجام نشد",
            verified.signal, now, dedupe = true)
        verified // four-layer engine owns the price plan; ICT remains optional research context
    }.stateIn(appScope, SharingStarted.Eagerly, market.state.value.copy(signal = null))
    /** Periodic sweep of the editable V1 watchlist; only verified independent bars can become candidates. */
    val pairScanner = PairScanner(client, publicHistory, settingsStore, journalStore, opportunityStore, appScope, dukascopyHistory, decisionLog)
    /** Single adaptive sweep driver for the whole app universe (see SweepCoordinator). */
    val sweepCoordinator = SweepCoordinator(pairScanner) { com.aurum.edge.service.SignalMonitorService.running.value }
    val autoPaperTrader = PaperAutoTrader(settingsStore, journalStore)
    val catalogPaperTrader = CatalogPaperTrader(settingsStore, autoPaperTrader)

    init {
        pairScanner.attachAutoReview { scanned ->
            val result = catalogPaperTrader.review(scanned)
            // The foreground service observes journal changes itself. If it is off and the
            // app is visible, notify only after the atomic paper journal write succeeded.
            if (result.trade != null && !com.aurum.edge.service.SignalMonitorService.running.value) {
                val config = settingsStore.read()
                if (config.notifyOnSignal) Notifier.notifyRecordedAutoEntry(
                    appContext, result.trade, config.alertSoundUri)
            }
            result
        }
        autoPaperTrader.attachTrendSource { pairScanner.state.value.marketTrend }
        market.attach(appScope)
        openPositionCoordinator.start()
        sweepCoordinator.start()
        appScope.launch {
            com.aurum.edge.data.AppNetwork.route.collect { route ->
                if (route.ready) market.refreshNow()
                // System network / VPN changes invalidate routing caches and trigger a fresh check.
                pairScanner.refreshNow(minIntervalMs = 0)
            }
        }
        appScope.launch {
            verifiedMarket.collect { state ->
                val cfg = settingsStore.read()
                // Dedupe: when the foreground monitor service runs it feeds the same state to
                // the auto trader itself; running both collectors would double the review work.
                if (cfg.autoPaperTrading &&
                    !com.aurum.edge.service.SignalMonitorService.running.value) {
                    try {
                        val opened = autoPaperTrader.onMarketUpdate(state)
                        if (opened != null) {
                            Notifier.notifyRecordedAutoEntry(appContext, opened, cfg.alertSoundUri)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        autoPaperTrader.stopped("ورود خودکار کاغذی در این به‌روزرسانی با خطا متوقف شد")
                    }
                }
            }
        }
    }

    /** PDF of REAL, already-saved journal trades only — same [PerformanceMetrics] the on-screen panel uses. */
    suspend fun exportJournalPdf(uri: Uri, title: String, trades: List<PaperTrade>, startingBalance: Double) =
        withContext(Dispatchers.IO) {
            com.aurum.edge.data.JournalPdfExporter.export(appContext, uri, title, trades, startingBalance)
        }

    private data class CandleDownload(
        val candles: List<Candle>,
        val source: String,
        /** Provider receipt timestamp; distinct from the last candle timestamp. */
        val fetchedAt: Long = System.currentTimeMillis(),
        val observedGapCount: Int = 0,
    )

    /**
     * Historical provider bars for displaying a previously saved paper trade on its chart.
     * They never become a current price, an entry or a source for the live engine.
     * The keyless public history is tried first; Twelve Data is an explicit last fallback.
     */
    private suspend fun downloadCandles(symbol: String, interval: Interval, outputSize: Int): CandleDownload {
        val s = settingsStore.read()
        val requested = HistoryPolicy.deepProviderRequestSize(outputSize)
        val twelveRequested = HistoryPolicy.providerRequestSize(outputSize)
        return try {
            val result = publicHistory.fetchCandles(symbol, interval,
                minimumSize = HistoryPolicy.TARGET_CANDLES, desiredSize = requested)
            if (requested > HistoryPolicy.TARGET_CANDLES && result.candles.size < requested &&
                DukascopyHistoryClient.instrument(symbol) != null) {
                try {
                    val deep = dukascopyHistory.fetchCandles(symbol, interval,
                        minimumSize = HistoryPolicy.TARGET_CANDLES, desiredSize = requested)
                    if (deep.candles.size > result.candles.size) {
                        return CandleDownload(deep.candles, deep.provider, deep.fetchedAt,
                            PublicCandleHistoryClient.detectGaps(deep.candles, interval).size)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // The verified Yahoo window remains valid; do not invent a deeper one.
                }
            }
            CandleDownload(result.candles, result.provider, result.fetchedAt, result.gaps.size)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (publicFailure: Exception) {
            val dukascopyFailure = try {
                val deep = dukascopyHistory.fetchCandles(symbol, interval,
                    minimumSize = HistoryPolicy.TARGET_CANDLES, desiredSize = requested)
                return CandleDownload(deep.candles, deep.provider, deep.fetchedAt,
                    PublicCandleHistoryClient.detectGaps(deep.candles, interval).size)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (deepFailure: Exception) {
                deepFailure
            }
            if (!s.hasKeyFor(symbol)) {
                throw DataFeedException(
                    "Yahoo و Dukascopy تاریخچهٔ معتبر ندادند و کلید fallback تنظیم نشده است: " +
                        (dukascopyFailure.message ?: publicFailure.message ?: "خطای نامشخص").take(140)
                )
            }
            try {
                val candles = client.fetchCandles(s.apiKeyFor(symbol), symbol, interval, twelveRequested)
                if (candles.size < HistoryPolicy.TARGET_CANDLES) {
                    throw DataFeedException("Twelve Data فقط ${candles.size} کندل داد؛ حداقل ${HistoryPolicy.TARGET_CANDLES} کندل واقعی لازم است")
                }
                val sorted = candles.sortedBy { it.time }.takeLast(twelveRequested)
                val gaps = PublicCandleHistoryClient.detectGaps(sorted, interval)
                CandleDownload(
                    candles = sorted,
                    source = "Twelve Data · آخرین fallback پس از خطای Yahoo/Dukascopy: ${(publicFailure.message ?: "نامشخص").take(80)}" +
                        if (gaps.isEmpty()) " · بدون gap مشاهده‌شده" else " · ${gaps.size} gap واقعی بدون پرکردن",
                    fetchedAt = System.currentTimeMillis(),
                    observedGapCount = gaps.size,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (fallbackFailure: Exception) {
                throw DataFeedException(
                    "Yahoo، Dukascopy و آخرین fallback Twelve Data تاریخچهٔ معتبر ندادند؛ دادهٔ ساختگی/پرکننده مجاز نیست: " +
                        (fallbackFailure.message ?: "خطای نامشخص").take(140)
                )
            }
        }
    }

    /** Verified candles already stored on this device for this pair/timeframe; may be empty. */
    suspend fun cachedCandles(symbol: String, interval: Interval): List<Candle> =
        candleCache.load(symbol, interval)

    /**
     * Real provider bars for the window of an ALREADY RECORDED paper trade — display only.
     * Same client, parser and verification as the live chart; it never becomes a quote, a signal
     * or an entry, and a cache-write failure must not hide bars that were just verified.
     */
    suspend fun fetchTradeCandles(symbol: String, interval: Interval,
                                  outputSize: Int = HistoryPolicy.TARGET_CANDLES): Pair<List<Candle>, String> {
        val download = downloadCandles(symbol, interval, outputSize)
        val merged = runCatching { candleCache.merge(symbol, interval, download.candles) }
            .getOrDefault(download.candles)
        return merged to download.source
    }


}
