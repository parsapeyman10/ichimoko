package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.engine.V1Scoring
import kotlinx.coroutines.CancellationException

/** Independent real OHLC for all seven V1 intervals. No extrapolation of M1 from M5 or D1
 * from insufficient history. A failure leaves that layer UNKNOWN and the engine fails closed.
 */
internal class TimeframeFeed(
    private val publicHistory: PublicCandleHistoryClient,
    private val dukascopy: DukascopyHistoryClient,
    private val twelve: TwelveDataClient,
    private val nobitex: NobitexHistoryClient = NobitexHistoryClient(),
) {
    private data class Snapshot(val fetchedAt: Long, val candles: List<Candle>)
    private val cache = mutableMapOf<Pair<String, Interval>, Snapshot>()

    suspend fun fetch(symbol: String, base: Interval, baseBars: List<Candle>, apiKey: String,
                      now: Long = System.currentTimeMillis()): Map<Interval, List<Candle>> {
        val result = mutableMapOf(base to baseBars.filter { it.closed && it.time + base.millis <= now }
            .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES))
        for (interval in V1Scoring.intervals.filter { it != base }) {
            val key = symbol to interval
            val previous = cache[key]
            if (previous != null && now - previous.fetchedAt in 0L..(if (previous.candles.size >= HistoryPolicy.LIVE_MIN_CANDLES) minOf(300_000L, interval.millis) else 60_000L)) {
                result[interval] = previous.candles
                continue
            }
            // H4 adapters resample actual H1 bars; desired refers to H4 OUTPUT bars,
            // not 320 H1 inputs. The adapter requests enough raw H1 bars itself.
            val desired = HistoryPolicy.LIVE_FETCH_CANDLES
            val bars = try {
                if (CryptoCatalog.isCrypto(symbol)) {
                    // BTC/USDT is not BTC/USD: do not relabel Yahoo USD candles as USDT.
                    // This is the same identity-preserving crypto feed used for the live chart.
                    try {
                        nobitex.fetchCandles(symbol, interval, desiredSize = desired,
                            minimumSize = HistoryPolicy.LIVE_MIN_CANDLES).candles
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (apiKey.isBlank()) emptyList() else twelve.fetchCandles(apiKey, symbol,
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
            // Providers can label the still-forming bucket closed: use the clock, never that label.
            val closed = bars.filter { it.time > 0L && it.time + interval.millis <= now }
                .sortedBy { it.time }.distinctBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
                .map { it.copy(closed = true) }
            if (closed.size == HistoryPolicy.LIVE_REQUEST_CANDLES) {
                cache[key] = Snapshot(now, closed)
                result[interval] = closed
            } else {
                cache[key] = Snapshot(now, emptyList()) // retry later; never reuse stale frames
            }
        }
        return result
    }
}
