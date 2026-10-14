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
    companion object {
        private val admission = PublisherBudget(SourceRouting.HistorySource.entries.associateWith {
            if (it == SourceRouting.HistorySource.YAHOO) 6 else 2
        })
    }
    data class Snapshot(val provider: String, val source: SourceRouting.HistorySource,
                        val frames: Map<Interval, List<Candle>>, val priorFailures: List<String>, val integrityRevision: Long = 0) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }

    private data class Preferred(val source: SourceRouting.HistorySource, val at: Long, val revision: Long, val integrityRevision: Long, val auditTtl: Long)
    private val preferred = java.util.concurrent.ConcurrentHashMap<String, Preferred>()

    suspend fun fetch(symbol: String, apiKey: String, now: Long = System.currentTimeMillis()): Snapshot {
        val choices = SourceRouting.historySources(symbol, apiKey.isNotBlank())
        if (choices.isEmpty()) throw DataFeedException("$symbol: منبع دقیق تاریخچه تعریف نشده است")
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val startRevision = gate.revision(symbol)
        val revision = AppNetwork.route.value.revision
        val previous = preferred[symbol]?.takeIf { it.source in choices && it.revision == revision && it.integrityRevision == startRevision && now - it.at in 0 until it.auditTtl }
        if (previous != null && !gate.channelBlocked(symbol, SourceIntegrity.Channel.HISTORY)) {
            try {
                val snapshot = readSource(symbol, apiKey, previous.source)
                if (gate.revision(symbol) != startRevision) throw DataFeedException("اعتبار منابع هنگام دریافت تغییر کرد")
                return snapshot.copy(integrityRevision = startRevision)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { preferred.remove(symbol) }
        }
        val errors = mutableListOf<String>()
        val healthy = mutableListOf<Snapshot>()
        var occupied = false
        for (source in choices) {
            try { healthy += readSource(symbol, apiKey, source) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { occupied = occupied || failure is PublisherOccupied; errors += "${source.name}: ${(failure.message ?: "ناموجود").take(105)}" }
        }
        if (healthy.isEmpty() && occupied) throw PublisherOccupied()
        val receivedAt = System.currentTimeMillis()
        val choice = try {
            HistorySourceSelection.choose(symbol, healthy.map {
                HistorySourceSelection.Candidate(it, it.source.name, it.frames)
            }, receivedAt)
        } catch (failure: DataFeedException) {
            if (failure is SourceDisagreement) gate.reject(symbol, SourceIntegrity.Channel.HISTORY, failure.message.orEmpty())
            val note = "${failure.message}؛ ${errors.joinToString("؛ ").take(355)}"
            HistorySourceAudits.record(symbol, null, note, receivedAt)
            throw DataFeedException(note)
        }
        val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, choice.confirmations)
            ?: throw DataFeedException("اختلاف منابع هنوز رفع نشده؛ مقایسهٔ مستقل تازه لازم است")
        val winner = choice.selected.value
        preferred[symbol] = Preferred(winner.source, receivedAt, revision, verifiedRevision, if (choice.confirmations >= 2) 600_000L else 30_000L)
        HistorySourceAudits.record(symbol, winner.provider,
            choice.note + " · بررسی: " + choices.joinToString { it.name } +
                (if (errors.isNotEmpty()) " · نامعتبر/قطع: ${errors.joinToString("؛ ")}" else ""), receivedAt)
        return winner.copy(priorFailures = errors, integrityRevision = verifiedRevision)
    }

    private suspend fun readSource(symbol: String, apiKey: String, source: SourceRouting.HistorySource): Snapshot = admission.tryRead(source) {
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
        Snapshot(provider, source, frames, emptyList())
    }
}
