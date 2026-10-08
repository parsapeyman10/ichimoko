package com.aurum.edge.core

import android.content.Context
import android.net.Uri
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.DukascopyHistoryClient
import com.aurum.edge.data.FreeHistoryDownloader
import com.aurum.edge.data.FreeHistoryResult
import com.aurum.edge.data.ForexCalendarRepository
import com.aurum.edge.data.HistDataCsv
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketRepository
import com.aurum.edge.data.MetaTraderCsv
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.MetaTraderImporter
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
import com.aurum.edge.data.ReplayJournalStore
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.WatchRepository
import com.aurum.edge.data.WatchSettingsStore
import com.aurum.edge.engine.Backtester
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
 * Chart/signal workspace is forex/gold; watchlist also includes Iran gold and USD cash-board rows.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val candleCache = CandleCache(appContext)
    val journalStore = JournalStore(appContext, settingsStore = settingsStore)
    val replayJournalStore = ReplayJournalStore(appContext)
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
        IctEntryRules.withSafePlan(verified)
    }.stateIn(appScope, SharingStarted.Eagerly, market.state.value.copy(signal = null))
    /** Periodic all-pairs online candle sweep: candidates + radar status for every catalog pair. */
    val pairScanner = PairScanner(client, publicHistory, settingsStore, news, journalStore, opportunityStore, appScope, dukascopyHistory, appContext)
    /** The user's own AI (Claude or OpenAI-compatible) as an educational trading companion. */
    // The advisor also reads the journal so it can review OPEN positions ("continue or not"),
    // which is advisory only: JournalStore.attachHoldReview never closes or re-prices a trade.
    val traderAdvisor = TraderAdvisor(settingsStore, market, pairScanner, news, journalStore, appScope)
    val autoPaperTrader = PaperAutoTrader(settingsStore, news, journalStore, traderAdvisor)
    val freeHistory = FreeHistoryDownloader()
    val metaTraderImporter = MetaTraderImporter(appContext)

    init {
        pairScanner.attachAutoTrader(autoPaperTrader)
        market.attach(appScope)
        appScope.launch {
            verifiedMarket.collect { state ->
                val cfg = settingsStore.read()
                if (cfg.autoPaperTrading) {
                    try {
                        val opened = autoPaperTrader.onMarketUpdate(state)
                        if (opened != null) {
                            Notifier.notifyTradeOpened(appContext, opened, cfg.alertSoundUri)
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

    suspend fun exportFreeHistory(uri: Uri, result: FreeHistoryResult) = withContext(Dispatchers.IO) {
        val stream = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalArgumentException("فایل مقصد برای ذخیره باز نشد")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(FreeHistoryDownloader.csv(result)) }
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
     * Download at least 3000 real candles.
     *
     * The keyless public history is deliberately tried first. A Twelve Data key is an explicit
     * last fallback, never a silent primary: provider identity, timestamp and gaps are still
     * validated by the provider adapter and a failure stops the research run.
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

    /** Immutable research input shared by batch backtest and cursor replay. */
    data class ResearchDataset(
        val candles: List<Candle>,
        val source: String,
        val fetchedAt: Long,
        val observedGapCount: Int,
    )

    /** Download and retain the exact verified bars that LearnScreen will replay. */
    suspend fun fetchResearchDataset(interval: Interval, outputSize: Int): ResearchDataset {
        val download = downloadCandles(settingsStore.read().symbol, interval, outputSize)
        require(download.candles.size >= HistoryPolicy.TARGET_CANDLES) {
            "برای replay/backtest حداقل ${HistoryPolicy.TARGET_CANDLES} کندل واقعی لازم است (${download.candles.size} دریافت شد)"
        }
        return ResearchDataset(download.candles, download.source, download.fetchedAt, download.observedGapCount)
    }

    /** Run batch backtest on a dataset already shown to the replay; no second provider request. */
    suspend fun runBacktest(
        dataset: ResearchDataset,
        interval: Interval,
        initialBalance: Double,
        riskPercent: Double,
        spreadPrice: Double,
        commissionPerOz: Double,
        threshold: Double,
    ): Backtester.Result {
        val s = settingsStore.read()
        return withContext(Dispatchers.Default) {
            Backtester.run(
                candles = dataset.candles,
                interval = interval,
                symbol = s.symbol,
                dataSource = dataset.source,
                initialBalance = initialBalance,
                riskPercent = riskPercent,
                spreadPrice = spreadPrice,
                commissionPerOz = commissionPerOz,
                threshold = threshold,
                signalProfile = s.signalProfile,
            )
        }
    }

    /** Download real candles from the active online source; never fabricates bars to reach 3000. */
    suspend fun fetchCandles(interval: Interval, outputSize: Int): List<Candle> {
        val s = settingsStore.read()
        return downloadCandles(s.symbol, interval, outputSize).candles
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

    /** MetaTrader file/link is untrusted research input, not part of the market feed/cache. */
    suspend fun runImportedBacktest(
        csv: String, symbol: String, interval: Interval, timezone: String,
        initialBalance: Double, riskPercent: Double, spreadPrice: Double,
        commissionPerOz: Double, threshold: Double,
    ): Backtester.Result = withContext(Dispatchers.Default) {
        val imported = MetaTraderCsv.parse(csv, interval, timezone)
        val settings = settingsStore.read()
        val volumeNote = if (imported.volumeProvided) ")"
            else "؛ بدون ستون حجم — حجم ۰ فقط برای اندیکاتورهای حجمی ثبت شد)"
        Backtester.run(
            candles = imported.candles, interval = interval, symbol = symbol,
            dataSource = "${imported.formatLabel} کاربر (منشأ تأیید نشده؛ منطقه زمانی ${imported.timezone}؛ " +
                "${imported.candles.size} از ${imported.totalRows} ردیف$volumeNote",
            initialBalance = initialBalance, riskPercent = riskPercent,
            spreadPrice = spreadPrice, commissionPerOz = commissionPerOz, threshold = threshold,
            signalProfile = settings.signalProfile,
        )
    }

    /** HistData monthly CSV is historical BID, never part of Twelve Data live candles or orders. */
    suspend fun runHistDataBacktest(files: List<Pair<String, String>>, interval: Interval,
                                    initialBalance: Double, riskPercent: Double, spreadPrice: Double,
                                    commissionPerOz: Double, threshold: Double): Backtester.Result =
        withContext(Dispatchers.Default) {
            val merged = HistDataCsv.parseMerged(files, interval)
            val settings = settingsStore.read()
            Backtester.run(candles = merged.candles, interval = merged.interval, symbol = merged.symbol,
                dataSource = "HistData فایل‌های کاربر (${merged.months} ماه · ${merged.symbol} · " +
                    "تایم‌فریم ${merged.interval.label} تجمیع‌شده از M1 واقعی) · BID تاریخی · EST ثابت UTC−05:00 · " +
                    "${merged.candles.size} کندل از ${merged.totalRows} ردیف M1؛ منشأ فایل مستقل تأیید نشده",
                initialBalance = initialBalance, riskPercent = riskPercent,
                spreadPrice = spreadPrice, commissionPerOz = commissionPerOz, threshold = threshold,
                signalProfile = settings.signalProfile)
        }

    /** Walk-forward on the same downloaded real bars: older half in-sample, newer half unseen. */
    suspend fun runWalkForward(
        interval: Interval,
        outputSize: Int,
        initialBalance: Double,
        riskPercent: Double,
        spreadPrice: Double,
        commissionPerOz: Double,
        threshold: Double,
    ): Backtester.WalkForward {
        val s = settingsStore.read()
        val download = downloadCandles(s.symbol, interval, outputSize)
        val candles = download.candles
        if (candles.size < HistoryPolicy.TARGET_CANDLES) {
            throw DataFeedException("برای تست خارج از نمونه حداقل ${HistoryPolicy.TARGET_CANDLES} کندل واقعی لازم است (${candles.size} کندل دریافت شد)")
        }
        return withContext(Dispatchers.Default) {
            Backtester.walkForward(
                candles = candles,
                interval = interval,
                symbol = s.symbol,
                initialBalance = initialBalance,
                riskPercent = riskPercent,
                spreadPrice = spreadPrice,
                commissionPerOz = commissionPerOz,
                threshold = threshold,
                signalProfile = s.signalProfile,
                dataSource = download.source,
            )
        }
    }

    /** Honest back-test: the strategy runs over the real bars that were just downloaded. */
    suspend fun runBacktest(
        interval: Interval,
        outputSize: Int,
        initialBalance: Double,
        riskPercent: Double,
        spreadPrice: Double,
        commissionPerOz: Double,
        threshold: Double,
    ): Backtester.Result {
        val s = settingsStore.read()
        val download = downloadCandles(s.symbol, interval, outputSize)
        val candles = download.candles
        return withContext(Dispatchers.Default) {
            Backtester.run(
                candles = candles,
                interval = interval,
                symbol = s.symbol,
                dataSource = download.source,
                initialBalance = initialBalance,
                riskPercent = riskPercent,
                spreadPrice = spreadPrice,
                commissionPerOz = commissionPerOz,
                threshold = threshold,
                signalProfile = s.signalProfile,
            )
        }
    }
}
