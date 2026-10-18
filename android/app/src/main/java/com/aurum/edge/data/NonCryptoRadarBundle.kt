package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.CancellationException

/**
 * Non-crypto history bundle (Gold, Silver, Dow Jones, 7 Ichimoku Forex pairs):
 *
 * M5, M15 and H1 are each fetched INDEPENDENTLY from the same publisher — higher
 * timeframes are never built from the M5 series. The dedicated source is tried
 * first; if it does not answer with a healthy full bundle, the next free source
 * is tried immediately and the first healthy responder becomes the sticky
 * primary for the symbol (see [SourcePromotion]). Every failure is recorded and
 * surfaced in the audit note / [Snapshot.priorFailures] — dead sources are
 * announced, never silently skipped.
 */
internal class NonCryptoRadarBundle(
    private val read: suspend (String, Interval, SourceRouting.HistorySource, String) -> PublicCandleHistoryClient.Result,
) {
    companion object {
        private val admission = PublisherBudget(SourceRouting.HistorySource.entries.associateWith { 1 })
        private val FRAMES = listOf(Interval.M5, Interval.M15, Interval.H1)
    }

    data class Snapshot(
        val provider: String,
        val source: SourceRouting.HistorySource,
        val frames: Map<Interval, List<Candle>>,
        val priorFailures: List<String>,
        val integrityRevision: Long = 0,
    ) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }

    suspend fun fetch(symbol: String, apiKey: String, now: Long = System.currentTimeMillis()): Snapshot {
        val candidates = SourcePromotion.ordered(
            symbol, SourcePromotion.Kind.HISTORY,
            SourceRouting.historyCandidates(symbol, apiKey.isNotBlank()),
        ) { it.name }
        if (candidates.isEmpty())
            throw DataFeedException("$symbol: منبع تاریخچه برای این نماد تعریف نشده است")

        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val failures = mutableListOf<String>()

        for (source in candidates) {
            try {
                val snapshot = admission.tryRead(source) { readBundle(symbol, source, apiKey) }
                SourcePromotion.onSuccess(symbol, SourcePromotion.Kind.HISTORY, source.name)
                val receivedAt = System.currentTimeMillis()
                val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, 2)
                    ?: gate.revision(symbol)
                HistorySourceAudits.record(
                    symbol,
                    snapshot.provider,
                    "M5/M15/H1 مستقل از ${source.providerLabel} · " +
                        SourcePromotion.summaryFa(symbol, SourcePromotion.Kind.HISTORY) { id ->
                            SourceRouting.HistorySource.entries.firstOrNull { it.name == id }?.providerLabel ?: id
                        } +
                        if (failures.isEmpty()) "" else " · پیش از آن ناموفق: ${failures.joinToString("؛ ").take(220)}",
                    receivedAt,
                )
                return snapshot.copy(priorFailures = failures.toList(),
                    integrityRevision = verifiedRevision)
            } catch (occupied: PublisherOccupied) {
                // Local admission pressure is not a source outage: never demote for it.
                failures += "${source.providerLabel}: ظرفیت محلی پر است"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val count = SourcePromotion.onFailure(symbol, SourcePromotion.Kind.HISTORY,
                    source.name, error.message ?: "خطای نامشخص")
                failures += "${source.providerLabel}: ${(error.message ?: "خطای نامشخص").take(110)}" +
                    " (خطای متوالی $count)"
            }
        }

        HistorySourceAudits.record(
            symbol, null,
            "همهٔ منابع تاریخچه بی‌پاسخ‌اند و باید جایگزین شوند: ${failures.joinToString("؛ ").take(300)}",
            System.currentTimeMillis(),
        )
        throw DataFeedException(
            "$symbol: هیچ منبع تاریخچه‌ای بستهٔ سالم نداد — ${failures.joinToString("؛ ").take(300)}")
    }

    /** One publisher must deliver ALL THREE independent frames healthy, or it does not count. */
    private suspend fun readBundle(
        symbol: String,
        source: SourceRouting.HistorySource,
        apiKey: String,
    ): Snapshot {
        val frames = linkedMapOf<Interval, List<Candle>>()
        var provider = ""
        for (frame in FRAMES) {
            val result = read(symbol, frame, source, apiKey)
            val receivedAt = System.currentTimeMillis()
            if (frame == Interval.M5) provider = result.provider
            val bars = result.candles
                .filter { it.closed && it.time + frame.millis <= receivedAt }
                .sortedBy { it.time }
                .distinctBy { it.time }
                .takeLast(HistoryPolicy.fetchSize(frame))
            if (!SourceRouting.matchesSource(result.provider, source))
                throw DataFeedException("${frame.label}: ناشرِ پاسخ با منبع انتخابی یکی نیست (${result.provider})")
            if (!CandleSourceHealth.ready(bars, frame, receivedAt))
                throw DataFeedException(
                    "${frame.label}: کندل بستهٔ تازه/کافی ندارد (${bars.size}/${HistoryPolicy.minSize(frame)})")
            frames[frame] = bars
        }
        return Snapshot(provider, source, frames, emptyList())
    }
}
