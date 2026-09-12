package com.aurum.edge.core

import android.content.Context
import com.aurum.edge.data.CandleCache
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketRepository
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.TwelveDataClient
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

    init {
        market.attach(appScope)
    }

    /** Download real candles from the provider (no fallback, throws on failure). */
    suspend fun fetchCandles(interval: Interval, outputSize: Int): List<Candle> {
        val s = settingsStore.read()
        if (!s.hasKey) throw DataFeedException("کلید Twelve Data وارد نشده است")
        return client.fetchCandles(s.apiKey, s.symbol, interval, outputSize)
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
