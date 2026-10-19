package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.CancellationException

/**
 * Crypto history bundle (5 USDT spot pairs):
 *
 * M5, M15 and H1 are each fetched INDEPENDENTLY from the same exchange venue —
 * never derived from the M5 series. The symbol's dedicated venue is tried first;
 * if it does not answer with a healthy full bundle, the next free venue is tried
 * and the first healthy responder becomes the sticky primary for that symbol
 * (see [SourcePromotion]). Venue promotion here is about DATA reliability only:
 * paper-entry venue rules (same-venue history + executed-trade tick) still gate
 * entries separately and are not weakened by a promoted read source.
 */
internal class CryptoRadarBundle(
    private val read: suspend (String, Interval, Source) -> PublicCandleHistoryClient.Result,
) {
    enum class Source(val label: String, val venue: CryptoRadarCandleClient.Venue) {
        BINANCE("Binance Spot", CryptoRadarCandleClient.Venue.BINANCE),
        OKX("OKX Spot", CryptoRadarCandleClient.Venue.OKX),
        GATE("Gate Spot", CryptoRadarCandleClient.Venue.GATE),
        MEXC("MEXC Spot", CryptoRadarCandleClient.Venue.MEXC),
        KRAKEN("Kraken Spot", CryptoRadarCandleClient.Venue.KRAKEN),
    }

    data class Snapshot(
        val provider: String,
        val venue: Source,
        val frames: Map<Interval, List<Candle>>,
        val priorFailures: List<String>,
        val integrityRevision: Long = 0,
    ) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }

    companion object {
        private val admission = PublisherBudget(Source.entries.associateWith { 1 })
        private val FRAMES = listOf(Interval.M5, Interval.M15, Interval.H1)

        fun dedicatedSource(symbol: String): Source? = when (symbol.trim().uppercase()) {
            "BTC/USDT" -> Source.BINANCE
            "ETH/USDT" -> Source.OKX
            "SOL/USDT" -> Source.GATE
            "BNB/USDT" -> Source.MEXC
            "XRP/USDT" -> Source.KRAKEN
            else -> null
        }
    }

    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): Snapshot {
        val dedicated = dedicatedSource(symbol)
            ?: throw DataFeedException("$symbol: منبع اختصاصی این رمزارز تعریف نشده است")
        val natural = listOf(dedicated) + Source.entries.filter { it != dedicated }
        val candidates = SourcePromotion.ordered(
            symbol, SourcePromotion.Kind.CRYPTO, natural) { it.name }

        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val failures = mutableListOf<String>()

        for (source in candidates) {
            try {
                val snapshot = admission.tryRead(source) { readBundle(symbol, source) }
                SourcePromotion.onSuccess(symbol, SourcePromotion.Kind.CRYPTO, source.name)
                val receivedAt = System.currentTimeMillis()
                val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, 2)
                    ?: gate.revision(symbol)
                HistorySourceAudits.record(
                    symbol,
                    snapshot.provider,
                    "M5/M15/H1 مستقل از ${source.label} · " +
                        SourcePromotion.summaryFa(symbol, SourcePromotion.Kind.CRYPTO) { id ->
                            Source.entries.firstOrNull { it.name == id }?.label ?: id
                        } +
                        if (failures.isEmpty()) "" else " · پیش از آن ناموفق: ${failures.joinToString("؛ ").take(220)}",
                    receivedAt,
                )
                return snapshot.copy(priorFailures = failures.toList(),
                    integrityRevision = verifiedRevision)
            } catch (occupied: PublisherOccupied) {
                failures += "${source.label}: ظرفیت محلی پر است"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val count = SourcePromotion.onFailure(symbol, SourcePromotion.Kind.CRYPTO,
                    source.name, error.message ?: "خطای نامشخص")
                failures += "${source.label}: ${(error.message ?: "خطای نامشخص").take(110)}" +
                    " (خطای متوالی $count)"
            }
        }

        HistorySourceAudits.record(
            symbol, null,
            "همهٔ صرافی‌های منبع تاریخچه بی‌پاسخ‌اند و باید جایگزین شوند: ${failures.joinToString("؛ ").take(300)}",
            System.currentTimeMillis(),
        )
        throw DataFeedException(
            "$symbol: هیچ صرافی‌ای بستهٔ سالم نداد — ${failures.joinToString("؛ ").take(300)}")
    }

    /** One venue must deliver ALL THREE independent frames healthy and contiguous. */
    private suspend fun readBundle(symbol: String, source: Source): Snapshot {
        val frames = linkedMapOf<Interval, List<Candle>>()
        var provider = ""
        for (frame in FRAMES) {
            val result = read(symbol, frame, source)
            val receivedAt = System.currentTimeMillis()
            if (frame == Interval.M5) provider = result.provider
            val bars = result.candles
                .filter { it.closed && it.time + frame.millis <= receivedAt }
                .sortedBy { it.time }
                .distinctBy { it.time }
                .takeLast(HistoryPolicy.fetchSize(frame))
            if (!CandleSourceHealth.ready(bars, frame, receivedAt,
                    requireContiguous = true, requireVolume = false))
                throw DataFeedException(
                    "${frame.label}: کندل بستهٔ پیوسته/تازه از ${source.label} کافی نیست " +
                        "(${bars.size}/${HistoryPolicy.minSize(frame)})")
            frames[frame] = bars
        }
        return Snapshot(provider, source, frames, emptyList())
    }
}
