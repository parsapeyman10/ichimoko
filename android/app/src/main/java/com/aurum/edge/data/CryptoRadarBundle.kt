package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.CancellationException

/** One spot venue for all three independent frames; source changes apply to the WHOLE
 * evaluation, never only one timeframe. No Binance/Nobitex source is selected here;
 * no read-only observation can authorize a new paper position.
 */
internal class CryptoRadarBundle(
    private val read: suspend (String, Interval, Source) -> PublicCandleHistoryClient.Result,
) {
    enum class Source(val label: String) { GATE("Gate Spot"), OKX("OKX Spot"), KRAKEN("Kraken Spot"), MEXC("MEXC Spot") }
    data class Snapshot(val provider: String, val venue: Source,
                        val frames: Map<Interval, List<Candle>>, val priorFailures: List<String>, val integrityRevision: Long = 0) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }
    companion object {
        private val admission = PublisherBudget(Source.entries.associateWith { 1 })
    }
    private data class Preferred(val source: Source, val at: Long, val route: Long, val integrityRevision: Long, val auditTtl: Long)
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Preferred>()
    private val frames = listOf(Interval.M5, Interval.M15, Interval.H1)

    // No asset-wide semaphore: each symbol has its own engine. Publisher admission
    // remains bounded, but a full publisher never queues a symbol behind a slow peer.
    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): Snapshot = fetchBundle(symbol, now)

    private suspend fun fetchBundle(symbol: String, now: Long): Snapshot {
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val startRevision = gate.revision(symbol)
        val last = lastGood[symbol]?.takeIf {
            !gate.channelBlocked(symbol, SourceIntegrity.Channel.HISTORY) && now - it.at in 0L until it.auditTtl && it.route == AppNetwork.route.value.revision && it.integrityRevision == startRevision }?.source
        val choices = (listOfNotNull(last) + Source.entries).distinct()
        val errors = mutableListOf<String>()
        val healthy = mutableListOf<Snapshot>()
        var occupied = false
        for (source in choices) {
            try {
                val snapshot = admission.tryRead(source) {
                val series = linkedMapOf<Interval,List<Candle>>()
                var provider: String? = null
                // Three frames of THIS symbol stay in this publisher's admitted bundle;
                // other symbols try other publishers rather than forming a shared queue.
                val results = frames.map { it to read(symbol, it, source) }
                for ((interval, result) in results) {
                    if (provider != null && result.provider != provider)
                        throw DataFeedException("ناشر بازه‌های زمانی متفاوت است")
                    provider = result.provider
                    val receivedAt = System.currentTimeMillis()
                    val bars = result.candles.filter { it.closed && it.time + interval.millis <= receivedAt }
                        .sortedBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
                    if (!CandleSourceHealth.ready(bars, interval, receivedAt,
                            requireContiguous = true, requireVolume = false))
                        throw DataFeedException("۳۲۰ کندل بستهٔ پیوسته و تازهٔ ${interval.label} ناقص است")
                    series[interval] = bars
                }
                Snapshot(provider ?: throw DataFeedException("منبع نامعلوم است"), source, series, errors.toList())
                }
                if (source == last && gate.revision(symbol) == startRevision)
                    return snapshot.copy(integrityRevision = startRevision) // revalidate all frames
                // A concurrent conflict requires a new complete audit, not this old response.
                if (gate.revision(symbol) != startRevision) throw DataFeedException("اعتبار منابع هنگام دریافت تغییر کرد")
                healthy += snapshot
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                occupied = occupied || failure is PublisherOccupied
                errors += "${source.label}: ${(failure.message ?: "ناموجود").take(55)}"
            }
        }
        if (healthy.isEmpty() && occupied) throw PublisherOccupied()
        val receivedAt = System.currentTimeMillis()
        val choice = try {
            HistorySourceSelection.choose(symbol, healthy.map {
                HistorySourceSelection.Candidate(it, it.venue.name, it.frames)
            }, receivedAt)
        } catch (failure: DataFeedException) {
            if (failure is SourceDisagreement) gate.reject(symbol, SourceIntegrity.Channel.HISTORY, failure.message.orEmpty())
            val note = "$symbol: هیچ منبع هم‌نماد M5/M15/H1 معتبر نیست؛ ${failure.message}؛ ${errors.joinToString("؛ ").take(210)}"
            HistorySourceAudits.record(symbol, null, note, receivedAt)
            throw DataFeedException(note)
        }
        val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, choice.confirmations)
            ?: throw DataFeedException("اختلاف منابع هنوز رفع نشده؛ مقایسهٔ مستقل تازه لازم است")
        val winner = choice.selected.value
        lastGood[symbol] = Preferred(winner.venue, receivedAt, AppNetwork.route.value.revision, verifiedRevision, if (choice.confirmations >= 2) 600_000L else 30_000L)
        HistorySourceAudits.record(symbol, winner.provider,
            choice.note + " · بررسی: Gate / OKX / Kraken / MEXC" +
                (if (errors.isNotEmpty()) " · نامعتبر/قطع: ${errors.joinToString("؛ ")}" else ""), receivedAt)
        return winner.copy(priorFailures = errors, integrityRevision = verifiedRevision)
    }
}
