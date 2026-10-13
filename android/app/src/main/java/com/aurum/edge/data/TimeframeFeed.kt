package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.engine.V1Scoring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** Only the M5/M15/H1 alignment frames are downloaded; a missing real frame is UNKNOWN.
 * Fetch the independent frames concurrently so a slow source does not serialize them.
 * This is not permission to ignore provider rate limits or to reuse a different venue.
 */
internal class TimeframeFeed(
    private val publicHistory: PublicCandleHistoryClient,
    private val dukascopy: DukascopyHistoryClient,
    private val twelve: TwelveDataClient,
    private val binance: BinanceSpotClient = BinanceSpotClient(),
) {
    private data class Snapshot(val fetchedAt: Long, val candles: List<Candle>)
    // Do not reuse a cached Twelve Data frame as Binance-only evidence after auto mode is enabled.
    private data class Key(val symbol: String, val interval: Interval, val requireBinanceCrypto: Boolean,
                           val source: SourceRouting.HistorySource?)
    private val cache = ConcurrentHashMap<Key, Snapshot>()

    suspend fun fetch(symbol: String, base: Interval, baseBars: List<Candle>, apiKey: String,
                      now: Long = System.currentTimeMillis(),
                      requireBinanceCrypto: Boolean = false,
                      pinnedSource: SourceRouting.HistorySource? = null): Map<Interval, List<Candle>> = coroutineScope {
        val result = mutableMapOf(base to baseBars.filter { it.closed && it.time + base.millis <= now }
            .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES))
        V1Scoring.intervals.filter { it != base }.map { interval ->
            async { interval to fetchFrame(symbol, interval, apiKey, now, requireBinanceCrypto, pinnedSource) }
        }.awaitAll().forEach { (interval, bars) -> result[interval] = bars }
        result
    }

    private suspend fun fetchFrame(symbol: String, interval: Interval, apiKey: String,
                                   now: Long, requireBinanceCrypto: Boolean,
                                   pinnedSource: SourceRouting.HistorySource?): List<Candle> {
        val key = Key(symbol, interval, requireBinanceCrypto, pinnedSource)
        val previous = cache[key]
        if (previous != null && now - previous.fetchedAt in 0L..minOf(300_000L, interval.millis) &&
            CandleSourceHealth.ready(previous.candles, interval, now,
                requireContiguous = CryptoCatalog.isCrypto(symbol),
                requireVolume = CryptoCatalog.isCrypto(symbol))) return previous.candles
        val desired = HistoryPolicy.LIVE_FETCH_CANDLES
        val bars = try {
            if (CryptoCatalog.isCrypto(symbol)) {
                // Selected crypto chart's independent frames must use the original
                // Binance venue, never a Twelve Data/USD or another-venue mix.
                binance.fetchCandles(symbol, interval, desiredSize = desired,
                    minimumSize = HistoryPolicy.LIVE_MIN_CANDLES).candles
            } else {
                suspend fun healthy(bars: List<Candle>): List<Candle> {
                    if (!CandleSourceHealth.ready(bars, interval, now))
                        throw DataFeedException("${interval.label}: تاریخچه ناقص/قدیمی است")
                    return bars
                }
                // A live chart must NOT silently combine another publisher's reference
                // frames with its already chosen base. Unknown publisher => no frames.
                if (pinnedSource != null) when (pinnedSource) {
                    SourceRouting.HistorySource.YAHOO -> healthy(publicHistory.fetchCandles(symbol, interval,
                        minimumSize = HistoryPolicy.LIVE_MIN_CANDLES, desiredSize = desired).candles)
                    SourceRouting.HistorySource.DUKASCOPY -> healthy(dukascopy.fetchCandles(symbol, interval,
                        minimumSize = HistoryPolicy.LIVE_MIN_CANDLES, desiredSize = desired).candles)
                    SourceRouting.HistorySource.TWELVE_DATA -> if (apiKey.isBlank()) emptyList()
                        else healthy(twelve.fetchCandles(apiKey, symbol, interval,
                            outputSize = desired, minimumOutputSize = HistoryPolicy.LIVE_MIN_CANDLES))
                } else emptyList() // unknown publisher is not permission to mix reference frames
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() }
        val closed = bars.filter { it.closed && it.time > 0L && it.time + interval.millis <= now }
            .sortedBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        val valid = CandleSourceHealth.ready(closed, interval, now,
            requireContiguous = CryptoCatalog.isCrypto(symbol),
            requireVolume = CryptoCatalog.isCrypto(symbol))
        return if (valid) {
            cache[key] = Snapshot(now, closed)
            closed
        } else {
            cache[key] = Snapshot(now, emptyList()) // never reuse stale/partial frames
            emptyList()
        }
    }
}
