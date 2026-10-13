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
    enum class Source(val label: String) { GATE("Gate Spot"), OKX("OKX Spot"), KRAKEN("Kraken Spot") }
    data class Snapshot(val provider: String, val venue: Source,
                        val frames: Map<Interval, List<Candle>>, val priorFailures: List<String>) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Pair<Source, Long>>()
    private val frames = listOf(Interval.M5, Interval.M15, Interval.H1)

    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): Snapshot {
        val last = lastGood[symbol]?.takeIf {
            now - it.second in 0L..600_000L }?.first
        val choices = (listOfNotNull(last) + listOf(Source.GATE, Source.OKX, Source.KRAKEN)).distinct()
        val errors = mutableListOf<String>()
        for (source in choices) {
            try {
                val series = linkedMapOf<Interval,List<Candle>>()
                var provider: String? = null
                for (interval in frames) {
                    val result = read(symbol, interval, source)
                    if (provider != null && result.provider != provider)
                        throw DataFeedException("ناشر بازه‌های زمانی متفاوت است")
                    provider = result.provider
                    val bars = result.candles.filter { it.closed && it.time + interval.millis <= now }
                        .sortedBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
                    if (!CandleSourceHealth.ready(bars, interval, now,
                            requireContiguous = true, requireVolume = true))
                        throw DataFeedException("۳۲۰ کندل بستهٔ پیوسته و تازهٔ ${interval.label} ناقص است")
                    series[interval] = bars
                }
                lastGood[symbol] = source to now
                return Snapshot(provider ?: throw DataFeedException("منبع نامعلوم است"), source, series, errors)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                errors += "${source.label}: ${(failure.message ?: "ناموجود").take(55)}"
            }
        }
        throw DataFeedException("$symbol: هیچ منبع هم‌نماد M5/M15/H1 معتبر نیست؛ ${errors.joinToString("؛ ").take(210)}")
    }
}
