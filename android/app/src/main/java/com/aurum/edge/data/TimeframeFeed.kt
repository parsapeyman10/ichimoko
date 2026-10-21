package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.CancellationException

/**
 * Reference alignment frames for the SELECTED market.
 *
 * M15 and H1 are fetched INDEPENDENTLY over the network from the same publisher
 * family as the base M5 series (pinned source first, then [SourcePromotion]
 * order) — they are never built by resampling the M5 candles. If no source
 * delivers a healthy frame, that frame is simply absent and the engine blocks
 * honestly instead of trading on invented alignment.
 */
internal class TimeframeFeed(
    private val publicHistory: PublicCandleHistoryClient,
    private val dukascopy: DukascopyHistoryClient,
    private val twelve: TwelveDataClient,
    private val binance: BinanceSpotClient = BinanceSpotClient(),
    private val nobitex: NobitexHistoryClient = NobitexHistoryClient(),
) {
    suspend fun fetch(
        symbol: String,
        base: Interval,
        baseBars: List<Candle>,
        apiKey: String,
        now: Long = System.currentTimeMillis(),
        requireBinanceCrypto: Boolean = false,
        requireNobitexCrypto: Boolean = false,
        pinnedSource: SourceRouting.HistorySource? = null,
    ): Map<Interval, List<Candle>> {
        val m5Closed = baseBars
            .filter { it.closed && it.time + Interval.M5.millis <= now }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .takeLast(HistoryPolicy.fetchSize(Interval.M5))
        if (base != Interval.M5 || m5Closed.isEmpty()) {
            return mapOf(base to m5Closed)
        }
        val frames = linkedMapOf<Interval, List<Candle>>(Interval.M5 to m5Closed)

        // Nobitex is the crypto reference: its M15/H1 frames are fetched independently from
        // the same venue as the M5 base. Its absence is reported, never padded from Binance.
        if (requireNobitexCrypto) {
            for (frame in listOf(Interval.M15, Interval.H1)) {
                val bars = try {
                    healthy(nobitex.fetchCandles(symbol, frame,
                        desiredSize = HistoryPolicy.fetchSize(frame),
                        minimumSize = HistoryPolicy.minSize(frame)).candles, frame)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                if (bars != null) frames[frame] = bars
            }
            return frames
        }
        if (requireBinanceCrypto) {
            for (frame in listOf(Interval.M15, Interval.H1)) {
                val bars = try {
                    val result = binance.fetchCandles(symbol, frame,
                        desiredSize = HistoryPolicy.fetchSize(frame),
                        minimumSize = HistoryPolicy.minSize(frame))
                    healthy(result.candles, frame)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                if (bars != null) frames[frame] = bars
            }
            return frames
        }
        // Non-Binance crypto bases receive their frames from the venue bundle only;
        // mixing another venue's frames under this base would be silent venue mixing.
        if (CryptoCatalog.isCrypto(symbol)) return frames

        val natural = SourceRouting.historyCandidates(symbol, apiKey.isNotBlank())
        val ordered = SourcePromotion.ordered(symbol, SourcePromotion.Kind.HISTORY, natural) { it.name }
            .sortedBy { if (it == pinnedSource) 0 else 1 }
        for (source in ordered) {
            try {
                val m15 = readFrame(symbol, Interval.M15, source, apiKey)
                val h1 = readFrame(symbol, Interval.H1, source, apiKey)
                frames[Interval.M15] = m15
                frames[Interval.H1] = h1
                return frames
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                SourcePromotion.onFailure(symbol, SourcePromotion.Kind.HISTORY,
                    source.name, error.message ?: "خطای نامشخص")
            }
        }
        return frames
    }

    private suspend fun readFrame(
        symbol: String,
        frame: Interval,
        source: SourceRouting.HistorySource,
        apiKey: String,
    ): List<Candle> {
        val result = when {
            source.usesYahoo -> publicHistory.fetchCandles(symbol, frame,
                minimumSize = HistoryPolicy.minSize(frame),
                desiredSize = HistoryPolicy.fetchSize(frame))
            source.usesDukascopy -> dukascopy.fetchCandles(symbol, frame,
                minimumSize = HistoryPolicy.minSize(frame),
                desiredSize = HistoryPolicy.fetchSize(frame))
                .let { PublicCandleHistoryClient.Result(it.candles, it.provider, it.fetchedAt) }
            else -> PublicCandleHistoryClient.Result(
                twelve.fetchCandles(apiKey, symbol, frame,
                    outputSize = HistoryPolicy.fetchSize(frame),
                    minimumOutputSize = HistoryPolicy.minSize(frame)),
                "Twelve Data")
        }
        if (!SourceRouting.matchesSource(result.provider, source))
            throw DataFeedException("${frame.label}: ناشرِ پاسخ با منبع انتخابی یکی نیست (${result.provider})")
        return healthy(result.candles, frame)
            ?: throw DataFeedException("${frame.label}: کندل بستهٔ تازه/کافی ندارد")
    }

    private fun healthy(candles: List<Candle>, frame: Interval): List<Candle>? {
        val receivedAt = System.currentTimeMillis()
        val bars = candles
            .filter { it.closed && it.time + frame.millis <= receivedAt }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .takeLast(HistoryPolicy.fetchSize(frame))
        return bars.takeIf { CandleSourceHealth.ready(it, frame, receivedAt) }
    }
}
