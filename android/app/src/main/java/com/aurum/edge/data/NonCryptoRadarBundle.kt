package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer

/**
 * 1-to-1 single-source non-crypto bundle (Gold, Silver, Dow Jones, and 7 Ichimoku Forex pairs):
 * Each symbol has its own dedicated HistorySource (no two symbols share a source),
 * fetches ONLY 1500 closed M5 candles, and derives M15 (3x M5) and H1 (12x M5) by conversion.
 */
internal class NonCryptoRadarBundle(
    private val read: suspend (String, Interval, SourceRouting.HistorySource, String) -> PublicCandleHistoryClient.Result,
) {
    companion object {
        private val admission = PublisherBudget(SourceRouting.HistorySource.entries.associateWith { 1 })
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
        val source = SourceRouting.historySources(symbol, apiKey.isNotBlank()).singleOrNull()
            ?: throw DataFeedException("$symbol: منبع اختصاصی تاریخچهٔ M5 تعریف نشده است")
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val snapshot = admission.tryRead(source) {
            val result = read(symbol, Interval.M5, source, apiKey)
            val receivedAt = System.currentTimeMillis()
            val m5Bars = result.candles
                .filter { it.closed && it.time + Interval.M5.millis <= receivedAt }
                .sortedBy { it.time }
                .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
            if (!SourceRouting.matchesSource(result.provider, source) ||
                !CandleSourceHealth.ready(m5Bars, Interval.M5, receivedAt))
                throw DataFeedException("5m: منبع متفاوت یا ${HistoryPolicy.LIVE_REQUEST_CANDLES} کندل بستهٔ تازه/کامل ندارد")
            val m15Bars = MtfAnalyzer.convertFromM5(m5Bars, Interval.M15)
            val h1Bars = MtfAnalyzer.convertFromM5(m5Bars, Interval.H1)
            val frames = linkedMapOf(
                Interval.M5 to m5Bars,
                Interval.M15 to m15Bars,
                Interval.H1 to h1Bars,
            )
            Snapshot(result.provider, source, frames, emptyList())
        }
        val receivedAt = System.currentTimeMillis()
        val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, 2)
            ?: gate.revision(symbol)
        HistorySourceAudits.record(
            symbol,
            snapshot.provider,
            "منبع اختصاصی تک‌سورس (${source.providerLabel}) · ۱۵۰۰ کندل M5 + تبدیل M15 (۵۰۰) و H1 (۱۲۵)",
            receivedAt,
        )
        return snapshot.copy(integrityRevision = verifiedRevision)
    }
}
