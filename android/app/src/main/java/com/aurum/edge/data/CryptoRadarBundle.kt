package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer

/**
 * 1-to-1 single-source crypto bundle:
 * Each of the 5 crypto symbols has its own dedicated exchange source (no two symbols share a source),
 * fetches ONLY 1500 closed M5 candles, and derives M15 (every 3 M5 candles = 1 M15 candle) and
 * H1 (every 12 M5 candles = 1 H1 candle) by direct conversion from M5.
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
        val source = dedicatedSource(symbol)
            ?: throw DataFeedException("$symbol: منبع اختصاصی تک‌سورس برای این رمزارز تعریف نشده است")
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val snapshot = admission.tryRead(source) {
            val result = read(symbol, Interval.M5, source)
            val receivedAt = System.currentTimeMillis()
            val m5Bars = result.candles
                .filter { it.closed && it.time + Interval.M5.millis <= receivedAt }
                .sortedBy { it.time }
                .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
            if (!CandleSourceHealth.ready(m5Bars, Interval.M5, receivedAt,
                    requireContiguous = true, requireVolume = false))
                throw DataFeedException("${HistoryPolicy.LIVE_REQUEST_CANDLES} کندل بستهٔ پیوسته و تازهٔ 5m از ${source.label} ناقص است")
            val m15Bars = MtfAnalyzer.convertFromM5(m5Bars, Interval.M15)
            val h1Bars = MtfAnalyzer.convertFromM5(m5Bars, Interval.H1)
            val series = linkedMapOf(
                Interval.M5 to m5Bars,
                Interval.M15 to m15Bars,
                Interval.H1 to h1Bars,
            )
            Snapshot(result.provider, source, series, emptyList())
        }
        val receivedAt = System.currentTimeMillis()
        val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, 2)
            ?: gate.revision(symbol)
        HistorySourceAudits.record(
            symbol,
            snapshot.provider,
            "منبع اختصاصی تک‌سورس (${source.label}) · ۱۵۰۰ کندل M5 + تبدیل M15 (۵۰۰) و H1 (۱۲۵)",
            receivedAt,
        )
        return snapshot.copy(integrityRevision = verifiedRevision)
    }
}
