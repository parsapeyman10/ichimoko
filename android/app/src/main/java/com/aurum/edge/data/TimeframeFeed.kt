package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer

/**
 * Only M5 (1500 closed candles) is fetched over the network.
 * M15 (every 3 M5 candles = 1 M15 candle) and H1 (every 12 M5 candles = 1 H1 candle)
 * are derived directly from the M5 candles without additional network requests.
 */
internal class TimeframeFeed(
    @Suppress("UNUSED_PARAMETER") private val publicHistory: PublicCandleHistoryClient,
    @Suppress("UNUSED_PARAMETER") private val dukascopy: DukascopyHistoryClient,
    @Suppress("UNUSED_PARAMETER") private val twelve: TwelveDataClient,
    @Suppress("UNUSED_PARAMETER") private val binance: BinanceSpotClient = BinanceSpotClient(),
) {
    suspend fun fetch(
        symbol: String,
        base: Interval,
        baseBars: List<Candle>,
        apiKey: String,
        now: Long = System.currentTimeMillis(),
        requireBinanceCrypto: Boolean = false,
        pinnedSource: SourceRouting.HistorySource? = null,
    ): Map<Interval, List<Candle>> {
        val m5Closed = baseBars
            .filter { it.closed && it.time + Interval.M5.millis <= now }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        if (base != Interval.M5 || m5Closed.isEmpty()) {
            return mapOf(base to m5Closed)
        }
        return linkedMapOf(
            Interval.M5 to m5Closed,
            Interval.M15 to MtfAnalyzer.convertFromM5(m5Closed, Interval.M15),
            Interval.H1 to MtfAnalyzer.convertFromM5(m5Closed, Interval.H1),
        )
    }
}
