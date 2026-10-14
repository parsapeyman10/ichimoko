package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One spot venue for all three independent frames; source changes apply to the WHOLE
 * evaluation, never only one timeframe. No Binance/Nobitex source is selected here;
 * no read-only observation can authorize a new paper position.
 */
internal class CryptoRadarBundle(
    private val read: suspend (String, Interval, Source) -> PublicCandleHistoryClient.Result,
) {
    enum class Source(val label: String) { GATE("Gate Spot"), OKX("OKX Spot"), KRAKEN("Kraken Spot") }
    data class Snapshot(val provider: String, val venue: Source,
                        val frames: Map<Interval, List<Candle>>, val priorFailures: List<String>, val integrityRevision: Long = 0) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }
    private data class Preferred(val source: Source, val at: Long, val route: Long, val integrityRevision: Long)
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Preferred>()
    private val frames = listOf(Interval.M5, Interval.M15, Interval.H1)

    // Bound whole bundles: 200 M5 requests must not queue ahead of every M15/H1 request.
    // Non-crypto publishers and open-position monitoring have independent budgets.
    companion object { private val activeBundles = Semaphore(6) }
    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): Snapshot =
        activeBundles.withPermit { fetchBundle(symbol, now) }

    private suspend fun fetchBundle(symbol: String, now: Long): Snapshot {
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val startRevision = gate.revision(symbol)
        val last = lastGood[symbol]?.takeIf {
            !gate.channelBlocked(symbol, SourceIntegrity.Channel.HISTORY) && now - it.at in 0L..600_000L && it.route == AppNetwork.route.value.revision && it.integrityRevision == startRevision }?.source
        val choices = (listOfNotNull(last) + listOf(Source.GATE, Source.OKX, Source.KRAKEN)).distinct()
        val errors = mutableListOf<String>()
        val healthy = mutableListOf<Snapshot>()
        for (source in choices) {
            try {
                val series = linkedMapOf<Interval,List<Candle>>()
                var provider: String? = null
                // M5 validates/list-caches the spot pair first. Then M15/H1 are requested
                // concurrently from that SAME venue rather than waiting behind each other.
                val primary = read(symbol, Interval.M5, source)
                val other = coroutineScope {
                    frames.drop(1).map { interval -> async {
                        try { Result.success(interval to read(symbol, interval, source)) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { Result.failure(failure) }
                    } }.awaitAll()
                }
                val results = listOf(Interval.M5 to primary) + other.map { it.getOrThrow() }
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
                val snapshot = Snapshot(provider ?: throw DataFeedException("منبع نامعلوم است"), source, series, errors.toList())
                if (source == last && gate.revision(symbol) == startRevision)
                    return snapshot.copy(integrityRevision = startRevision) // revalidate all frames
                // A concurrent conflict requires a new complete audit, not this old response.
                if (gate.revision(symbol) != startRevision) throw DataFeedException("اعتبار منابع هنگام دریافت تغییر کرد")
                healthy += snapshot
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                errors += "${source.label}: ${(failure.message ?: "ناموجود").take(55)}"
            }
        }
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
        lastGood[symbol] = Preferred(winner.venue, receivedAt, AppNetwork.route.value.revision, verifiedRevision)
        HistorySourceAudits.record(symbol, winner.provider,
            choice.note + " · بررسی: Gate / OKX / Kraken" +
                (if (errors.isNotEmpty()) " · نامعتبر/قطع: ${errors.joinToString("؛ ")}" else ""), receivedAt)
        return winner.copy(priorFailures = errors, integrityRevision = verifiedRevision)
    }
}
