package com.aurum.edge.core

import android.content.Context
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.CryptoRepository
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketRepository
import com.aurum.edge.data.MetaTraderCsv
import com.aurum.edge.data.MetaTraderImporter
import com.aurum.edge.data.NewsRepository
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.TwelveDataClient
import com.aurum.edge.data.QuoteHistoryStore
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.WatchRepository
import com.aurum.edge.data.WatchSettingsStore
import com.aurum.edge.engine.Backtester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Process-wide wiring. Single source of truth for settings, real market data, journal and engine.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val candleCache = CandleCache(appContext)
    val journalStore = JournalStore(appContext)
    val client = TwelveDataClient()
    val market = MarketRepository(appContext, client, candleCache, settingsStore, journalStore)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watchSettings = WatchSettingsStore(appContext)
    val quoteHistory = QuoteHistoryStore(appContext)
    val watch = WatchRepository(SourceFetcher(), quoteHistory, watchSettings, settingsStore, appScope)
    val news = NewsRepository(settingsStore, appScope)
    val crypto = CryptoRepository(settingsStore, appScope)
    val metaTraderImporter = MetaTraderImporter(appContext)

    init {
        market.attach(appScope)
    }

    /** Download real candles from the provider (no fallback, throws on failure). */
    suspend fun fetchCandles(interval: Interval, outputSize: Int): List<Candle> {
        val s = settingsStore.read()
        if (!s.hasKey) throw DataFeedException("کلید Twelve Data وارد نشده است")
        return client.fetchCandles(s.apiKey, s.symbol, interval, outputSize)
    }

    /** MetaTrader file/link is untrusted research input, not part of the market feed/cache. */
    suspend fun runImportedBacktest(
        csv: String, symbol: String, interval: Interval, timezone: String,
        initialBalance: Double, riskPercent: Double, spreadPrice: Double,
        commissionPerOz: Double, threshold: Double,
    ): Backtester.Result = withContext(Dispatchers.Default) {
        val imported = MetaTraderCsv.parse(csv, interval, timezone)
        Backtester.run(
            candles = imported.candles, interval = interval, symbol = symbol,
            dataSource = "CSV کاربر از MetaTrader (منشأ تأیید نشده؛ منطقه زمانی ${imported.timezone}؛ " +
                "${imported.candles.size} از ${imported.totalRows} ردیف)",
            initialBalance = initialBalance, riskPercent = riskPercent,
            spreadPrice = spreadPrice, commissionPerOz = commissionPerOz, threshold = threshold,
        )
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
