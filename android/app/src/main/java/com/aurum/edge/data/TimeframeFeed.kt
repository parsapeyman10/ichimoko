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
    private val cache = ConcurrentHashMap<Triple<String, Interval, Boolean>, Snapshot>()

    suspend fun fetch(symbol: String, base: Interval, baseBars: List<Candle>, apiKey: String,
                      now: Long = System.currentTimeMillis(),
                      requireBinanceCrypto: Boolean = false): Map<Interval, List<Candle>> = coroutineScope {
        val result = mutableMapOf(base to baseBars.filter { it.closed && it.time + base.millis <= now }
            .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES))
        V1Scoring.intervals.filter { it != base }.map { interval ->
            async { interval to fetchFrame(symbol, interval, apiKey, now, requireBinanceCrypto) }
        }.awaitAll().forEach { (interval, bars) -> result[interval] = bars }
        result
    }

    private suspend fun fetchFrame(symbol: String, interval: Interval, apiKey: String,
                                   now: Long, requireBinanceCrypto: Boolean): List<Candle> {
        val key = Triple(symbol, interval, requireBinanceCrypto)
        val previous = cache[key]
        if (previous != null && now - previous.fetchedAt in 0L..(if (previous.candles.size >= HistoryPolicy.LIVE_MIN_CANDLES) minOf(300_000L, interval.millis) else 60_000L))
            return previous.candles
        val desired = HistoryPolicy.LIVE_FETCH_CANDLES
        val bars = try {
            if (CryptoCatalog.isCrypto(symbol)) {
                // BTC/USDT is not BTC/USD. Twelve Data cannot authorize a Binance entry.
                try {
                    binance.fetchCandles(symbol, interval, desiredSize = desired,
                        minimumSize = HistoryPolicy.LIVE_MIN_CANDLES).candles
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (requireBinanceCrypto || apiKey.isBlank()) emptyList() else twelve.fetchCandles(apiKey, symbol,
                        interval, outputSize = desired, minimumOutputSize = HistoryPolicy.LIVE_MIN_CANDLES)
                }
            } else try {
                publicHistory.fetchCandles(symbol, interval, minimumSize = HistoryPolicy.LIVE_MIN_CANDLES,
                    desiredSize = desired).candles
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                try {
                    dukascopy.fetchCandles(symbol, interval, minimumSize = HistoryPolicy.LIVE_MIN_CANDLES,
                        desiredSize = desired).candles
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (apiKey.isBlank()) emptyList() else twelve.fetchCandles(apiKey, symbol,
                        interval, outputSize = desired, minimumOutputSize = HistoryPolicy.LIVE_MIN_CANDLES)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        val closed = bars.filter { it.time > 0L && it.time + interval.millis <= now }
            .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
            .map { it.copy(closed = true) }
        return if (closed.size == HistoryPolicy.LIVE_REQUEST_CANDLES) {
            cache[key] = Snapshot(now, closed)
            closed
        } else {
            cache[key] = Snapshot(now, emptyList()) // retry later; never reuse stale frames
            emptyList()
        }
    }
}
