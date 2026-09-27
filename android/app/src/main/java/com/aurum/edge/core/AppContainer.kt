package com.aurum.edge.core

import android.content.Context
import android.net.Uri
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.DataFeedException
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
import com.aurum.edge.data.PaperOpportunityStore
import com.aurum.edge.data.PairScanner
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.TwelveDataClient
import com.aurum.edge.data.TraderAdvisor
import com.aurum.edge.data.QuoteHistoryStore
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.WatchRepository
import com.aurum.edge.data.WatchSettingsStore
import com.aurum.edge.engine.Backtester
import com.aurum.edge.engine.NewsConfluence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Process-wide wiring. Single source of truth for settings, real market data, journal and engine.
 * Forex/currency-pair workspace only: gold (XAU/USD) plus the major FX pairs.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val candleCache = CandleCache(appContext)
    val journalStore = JournalStore(appContext)
    val opportunityStore = PaperOpportunityStore(appContext)
    val client = TwelveDataClient()
    val market = MarketRepository(appContext, client, candleCache, settingsStore, journalStore)

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
    ) { raw, headlines, now ->
        val observedFeed = FeedLiveness.display(raw.feed, now)
        val delayed = observedFeed.mode == FeedMode.DELAYED
        val verified = raw.copy(feed = observedFeed,
            showingCachedData = raw.showingCachedData || delayed,
            signal = if (delayed) null else NewsConfluence.apply(raw.signal, raw.symbol, headlines, now))
        IctEntryRules.withSafePlan(verified)
    }.stateIn(appScope, SharingStarted.Eagerly, market.state.value.copy(signal = null))
    val autoPaperTrader = PaperAutoTrader(settingsStore, news, journalStore)
    /** Periodic all-pairs REST sweep: candidates + radar status for every catalog pair. */
    val pairScanner = PairScanner(client, settingsStore, news, journalStore, opportunityStore, appScope)
    /** The user's own AI (Claude or OpenAI-compatible) as an educational trading companion. */
    val traderAdvisor = TraderAdvisor(settingsStore, market, pairScanner, news, appScope)
    val freeHistory = FreeHistoryDownloader()
    val metaTraderImporter = MetaTraderImporter(appContext)

    init {
        market.attach(appScope)
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

    /** Download real candles from the provider (no fallback, throws on failure). */
    suspend fun fetchCandles(interval: Interval, outputSize: Int): List<Candle> {
        val s = settingsStore.read()
        if (!s.hasKey) throw DataFeedException("کلید Twelve Data وارد نشده است")
        return client.fetchCandles(s.apiKey, s.symbol, interval, outputSize)
    }

    /** Verified candles already stored on this device for this pair/timeframe; may be empty. */
    suspend fun cachedCandles(symbol: String, interval: Interval): List<Candle> =
        candleCache.load(symbol, interval)

    /**
     * Real provider bars for the window of an ALREADY RECORDED paper trade — display only.
     * Same client, parser and verification as the live chart; it never becomes a quote, a signal
     * or an entry, and a cache-write failure must not hide bars that were just verified.
     */
    suspend fun fetchTradeCandles(symbol: String, interval: Interval, outputSize: Int = 3000): List<Candle> {
        val s = settingsStore.read()
        if (!s.hasKey) throw DataFeedException("کلید Twelve Data وارد نشده است")
        val fresh = client.fetchCandles(s.apiKey, symbol, interval, outputSize.coerceIn(10, 3000))
        return runCatching { candleCache.merge(symbol, interval, fresh) }.getOrDefault(fresh)
    }

    /** MetaTrader file/link is untrusted research input, not part of the market feed/cache. */
    suspend fun runImportedBacktest(
        csv: String, symbol: String, interval: Interval, timezone: String,
        initialBalance: Double, riskPercent: Double, spreadPrice: Double,
        commissionPerOz: Double, threshold: Double,
    ): Backtester.Result = withContext(Dispatchers.Default) {
        val imported = MetaTraderCsv.parse(csv, interval, timezone)
        val volumeNote = if (imported.volumeProvided) ")"
            else "؛ بدون ستون حجم — حجم ۰ فقط برای اندیکاتورهای حجمی ثبت شد)"
        Backtester.run(
            candles = imported.candles, interval = interval, symbol = symbol,
            dataSource = "${imported.formatLabel} کاربر (منشأ تأیید نشده؛ منطقه زمانی ${imported.timezone}؛ " +
                "${imported.candles.size} از ${imported.totalRows} ردیف$volumeNote",
            initialBalance = initialBalance, riskPercent = riskPercent,
            spreadPrice = spreadPrice, commissionPerOz = commissionPerOz, threshold = threshold,
        )
    }

    /** HistData monthly CSV is historical BID, never part of Twelve Data live candles or orders. */
    suspend fun runHistDataBacktest(files: List<Pair<String, String>>, interval: Interval,
                                    initialBalance: Double, riskPercent: Double, spreadPrice: Double,
                                    commissionPerOz: Double, threshold: Double): Backtester.Result =
        withContext(Dispatchers.Default) {
            val merged = HistDataCsv.parseMerged(files, interval)
            Backtester.run(candles = merged.candles, interval = merged.interval, symbol = merged.symbol,
                dataSource = "HistData فایل‌های کاربر (${merged.months} ماه · ${merged.symbol} · " +
                    "تایم‌فریم ${merged.interval.label} تجمیع‌شده از M1 واقعی) · BID تاریخی · EST ثابت UTC−05:00 · " +
                    "${merged.candles.size} کندل از ${merged.totalRows} ردیف M1؛ منشأ فایل مستقل تأیید نشده",
                initialBalance = initialBalance, riskPercent = riskPercent,
                spreadPrice = spreadPrice, commissionPerOz = commissionPerOz, threshold = threshold)
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
        val candles = fetchCandles(interval, outputSize)
        if (candles.size < 400) {
            throw DataFeedException("برای تست خارج از نمونه حداقل ۴۰۰ کندل واقعی لازم است (${candles.size} کندل دریافت شد)")
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
        val candles = fetchCandles(interval, outputSize)
        return withContext(Dispatchers.Default) {
            Backtester.run(
                candles = candles,
                interval = interval,
                symbol = s.symbol,
                initialBalance = initialBalance,
                riskPercent = riskPercent,
                spreadPrice = spreadPrice,
                commissionPerOz = commissionPerOz,
                threshold = threshold,
            )
        }
    }
}
