package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.V1Scoring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Same exact instrument and publisher for the entire M5/M15/H1 decision.
 * On a missing or stale frame, retry the WHOLE bundle from the next supported publisher;
 * never certify M5 from Yahoo with M15 from Dukascopy (or a CFD/futures proxy).
 */
internal class NonCryptoRadarBundle(
    private val read: suspend (String, Interval, SourceRouting.HistorySource, String) -> PublicCandleHistoryClient.Result,
) {
    data class Snapshot(val provider: String, val source: SourceRouting.HistorySource,
                        val frames: Map<Interval, List<Candle>>, val priorFailures: List<String>) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }

    suspend fun fetch(symbol: String, apiKey: String, now: Long = System.currentTimeMillis()): Snapshot {
        val choices = SourceRouting.historySources(symbol, apiKey.isNotBlank())
        if (choices.isEmpty()) throw DataFeedException("$symbol: منبع دقیق تاریخچه تعریف نشده است")
        val errors = mutableListOf<String>()
        for (source in choices) {
            try {
                // Network requests for the frames start together; only a verified complete
                // bundle can be returned. No attempt is made to merge publishers.
                val fetched = coroutineScope {
                    V1Scoring.intervals.map { interval -> async {
                        try { Result.success(interval to read(symbol, interval, source, apiKey)) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { Result.failure(failure) }
                    } }.awaitAll()
                }
                val results = fetched.map { it.getOrThrow() }
                val provider = results.first().second.provider
                val frames = results.associate { (interval, result) ->
                    if (SourceRouting.fromProvider(result.provider) != source ||
                        !CandleSourceHealth.ready(result.candles, interval, now))
                        throw DataFeedException("${interval.label}: منبع متفاوت یا کندل بستهٔ تازه/کامل ندارد")
                    interval to result.candles
                }
                return Snapshot(provider, source, frames, errors)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                errors += "${source.name}: ${(failure.message ?: "ناموجود").take(60)}"
            }
        }
        throw DataFeedException("$symbol: هیچ بستهٔ هم‌ناشر M5/M15/H1 معتبر نیست؛ ${errors.joinToString("؛ ").take(170)}")
    }
}
