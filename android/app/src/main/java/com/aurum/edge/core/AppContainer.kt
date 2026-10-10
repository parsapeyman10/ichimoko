package com.aurum.edge.core

import android.content.Context
import android.net.Uri
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.DukascopyHistoryClient
import com.aurum.edge.data.ForexCalendarRepository
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketRepository
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsRepository
import com.aurum.edge.data.PublicWebNewsRepository
import com.aurum.edge.data.PublicNewsCategory
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PaperAutoTrader
import com.aurum.edge.data.PublicCandleHistoryClient
import com.aurum.edge.data.PaperOpportunityStore
import com.aurum.edge.data.PairScanner
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.TwelveDataClient
import com.aurum.edge.data.TraderAdvisor
import com.aurum.edge.data.QuoteHistoryStore
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.WatchRepository
import com.aurum.edge.data.WatchSettingsStore
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.notify.Notifier
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

    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val decisionLog = com.aurum.edge.data.DecisionLogStore(appContext)
    val candleCache = CandleCache(appContext)
    val journalStore = JournalStore(appContext, settingsStore = settingsStore)
    val opportunityStore = PaperOpportunityStore(appContext)
    val client = TwelveDataClient()
    val publicHistory = PublicCandleHistoryClient()
    val dukascopyHistory = DukascopyHistoryClient()
    val market = MarketRepository(appContext, client, candleCache, settingsStore, journalStore,
        publicHistory = publicHistory, dukascopyHistory = dukascopyHistory)
    val updater = AppUpdateRepository(appContext)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watchSettings = WatchSettingsStore(appContext)
    val quoteHistory = QuoteHistoryStore(appContext)
    val watch = WatchRepository(SourceFetcher(), quoteHistory, watchSettings, settingsStore, appScope)
    // Forex publisher headlines only: FXStreet/BLS feed the research context, never the AI gate.
    val publicWebNews = PublicWebNewsRepository(appScope,
        feeds = PublicNewsFeeds.all.filter { it.category in setOf(PublicNewsCategory.MARKETS, PublicNewsCategory.ECONOMY) })
    val forexCalendar = ForexCalendarRepository(appScope) // public schedule UI; server checks it independently for the AI gate
    // Backend server mode (preferred if configured) OR direct-from-phone client mode (publicWebNews
    // + forexCalendar + the user's own key) — see NewsRepository's class doc.
    val news = NewsRepository(settingsStore, appScope, publicWebNews, forexCalendar)
    /** Shared by chart, signal tab, notifications and automatic *paper* entries. Expires on time. */
    val verifiedMarket: StateFlow<MarketState> = combine(
        market.state, news.state, flow { while (true) { emit(System.currentTimeMillis()); delay(20_000L) } },
    ) { raw, headlines, _ ->
        // The periodic clock only forces recomposition; a one-second market tick must always be
        // judged against the real current clock, not the last 20-second timer emission.
        val now = System.currentTimeMillis()
        val observedFeed = FeedLiveness.display(raw.feed, now)
        val delayed = observedFeed.mode == FeedMode.DELAYED
        val verified = raw.copy(feed = observedFeed,
            showingCachedData = raw.showingCachedData || delayed,
            signal = if (delayed) null else NewsConfluence.apply(raw.signal, raw.symbol, headlines, now))
        if (raw.candles.isNotEmpty()) decisionLog.append(raw.symbol, raw.interval.label,
            when {
                verified.evaluationError != null -> "error"
                verified.signal?.isActionable == true -> "candidate"
                else -> "no_signal"
            },
            verified.evaluationError ?: verified.signal?.blockers?.joinToString("، ")?.ifBlank { "چهار لایه و AI بررسی شدند" }
                ?: "دادهٔ زنده/تایم‌فریم معتبر نیست", verified.signal, now, dedupe = true)
        verified // four-layer engine owns the price plan; ICT remains optional research context
    }.stateIn(appScope, SharingStarted.Eagerly, market.state.value.copy(signal = null))
    /** Periodic sweep of the editable V1 watchlist; only verified independent bars can become candidates. */
    val pairScanner = PairScanner(client, publicHistory, settingsStore, news, journalStore, opportunityStore, appScope, dukascopyHistory, decisionLog)
    /** The user's own AI (Claude or OpenAI-compatible) as an educational trading companion. */
    // The advisor also reads the journal so it can review OPEN positions ("continue or not"),
    // which is advisory only: JournalStore.attachHoldReview never closes or re-prices a trade.
    val traderAdvisor = TraderAdvisor(settingsStore, market, pairScanner, news, journalStore, appScope)
    val autoPaperTrader = PaperAutoTrader(settingsStore, news, journalStore, traderAdvisor)

    init {
        autoPaperTrader.attachTrendSource { pairScanner.state.value.marketTrend }
        market.attach(appScope)
        appScope.launch {
            verifiedMarket.collect { state ->
                val cfg = settingsStore.read()
                if (cfg.autoPaperTrading) {
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
            if (!s.hasKey) {
                throw DataFeedException(
                    "Yahoo و Dukascopy تاریخچهٔ معتبر ندادند و کلید fallback تنظیم نشده است: " +
                        (dukascopyFailure.message ?: publicFailure.message ?: "خطای نامشخص").take(140)
                )
            }
            try {
                val candles = client.fetchCandles(s.apiKey, symbol, interval, twelveRequested)
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
