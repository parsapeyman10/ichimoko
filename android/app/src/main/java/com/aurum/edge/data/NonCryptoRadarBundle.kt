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

    private data class Preferred(val source: SourceRouting.HistorySource, val at: Long, val revision: Long)
    private val preferred = java.util.concurrent.ConcurrentHashMap<String, Preferred>()

    suspend fun fetch(symbol: String, apiKey: String, now: Long = System.currentTimeMillis()): Snapshot {
        val choices = SourceRouting.historySources(symbol, apiKey.isNotBlank())
        if (choices.isEmpty()) throw DataFeedException("$symbol: منبع دقیق تاریخچه تعریف نشده است")
        val revision = AppNetwork.route.value.revision
        val previous = preferred[symbol]?.takeIf { it.source in choices && it.revision == revision && now - it.at in 0 until 600_000L }
        if (previous != null) {
            try { return readSource(symbol, apiKey, previous.source) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { preferred.remove(symbol) }
        }
        val errors = mutableListOf<String>()
        val healthy = mutableListOf<Snapshot>()
        for (source in choices) {
            try { healthy += readSource(symbol, apiKey, source) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { errors += "${source.name}: ${(failure.message ?: "ناموجود").take(105)}" }
        }
        val receivedAt = System.currentTimeMillis()
        val choice = try {
            HistorySourceSelection.choose(symbol, healthy.map {
                HistorySourceSelection.Candidate(it, it.source.name, it.frames)
            }, receivedAt)
        } catch (failure: DataFeedException) {
            val note = "${failure.message}؛ ${errors.joinToString("؛ ").take(355)}"
            HistorySourceAudits.record(symbol, null, note, receivedAt)
            throw DataFeedException(note)
        }
        val winner = choice.selected.value
        preferred[symbol] = Preferred(winner.source, receivedAt, revision)
        HistorySourceAudits.record(symbol, winner.provider,
            choice.note + " · بررسی: " + choices.joinToString { it.name } +
                (if (errors.isNotEmpty()) " · نامعتبر/قطع: ${errors.joinToString("؛ ")}" else ""), receivedAt)
        return winner.copy(priorFailures = errors)
    }

    private suspend fun readSource(symbol: String, apiKey: String, source: SourceRouting.HistorySource): Snapshot {
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
        val receivedAt = System.currentTimeMillis()
        val provider = results.first().second.provider
        val frames = results.associate { (interval, result) ->
            if (SourceRouting.fromProvider(result.provider) != source ||
                !CandleSourceHealth.ready(result.candles, interval, receivedAt))
                throw DataFeedException("${interval.label}: منبع متفاوت یا کندل بستهٔ تازه/کامل ندارد")
            interval to result.candles
        }
        return Snapshot(provider, source, frames, emptyList())
    }
}
