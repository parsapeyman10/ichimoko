package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval

/**
 * Nobitex is the app's **reference venue for crypto**.
 *
 * Candles (M5/M15/H1), the displayed executed-trade tick and the paper-entry tick are all
 * expected to come from this one venue, so a signal, its fill and its exit monitoring can
 * never silently mix two exchanges. The other venues (Binance/OKX/Gate/MEXC/Kraken) stay as
 * read-only fallbacks that are labelled as such; they never inherit Nobitex's label.
 *
 * Nothing here places an order: Nobitex is used for public market data only.
 */
object NobitexReference {
    /** Venue id stored with a paper position opened on this reference. */
    const val VENUE = "NOBITEX"

    /** Provider label of a live Nobitex executed trade; it starts with the history label. */
    const val TICK_PROVIDER = NobitexHistoryClient.PROVIDER + " · معاملهٔ زنده"

    /** A pair this app can actually read from Nobitex (BASE/USDT → BASEUSDT). */
    fun serves(symbol: String): Boolean =
        CryptoCatalog.isCrypto(symbol) && NobitexHistoryClient.ticker(symbol) != null

    /** True only for candles published by Nobitex (never for another exchange's bars). */
    fun isHistoryProvider(provider: String?): Boolean =
        provider?.startsWith(NobitexHistoryClient.PROVIDER) == true

    /** True only for ticks/quotes published by Nobitex. */
    fun isTickProvider(provider: String?): Boolean =
        provider?.startsWith(NobitexHistoryClient.PROVIDER) == true
}

/**
 * Common shape of a crypto candle bundle so the repository/scanner can treat the Nobitex
 * reference and the read-only exchange fallback the same way without relabelling either.
 */
internal interface CryptoHistoryBook {
    val provider: String
    val frames: Map<Interval, List<Candle>>
    val integrityRevision: Long
    val m5: List<Candle> get() = frames.getValue(Interval.M5)
}

/**
 * Nobitex M5/M15/H1 history for one pair.
 *
 * The three frames are fetched INDEPENDENTLY from Nobitex — never resampled from M5 — and one
 * venue must deliver all three healthy or the reference does not count. A failure is thrown,
 * never patched with another exchange under the Nobitex label.
 */
internal class NobitexCryptoBundle(
    history: NobitexHistoryClient = NobitexHistoryClient(),
    private val read: suspend (String, Interval, Int, Int, Long) -> PublicCandleHistoryClient.Result =
        { symbol, interval, desiredSize, minimumSize, now ->
            history.fetchCandles(symbol, interval, desiredSize, minimumSize, now)
        },
) {
    data class Snapshot(
        override val provider: String,
        override val frames: Map<Interval, List<Candle>>,
        override val integrityRevision: Long = 0L,
    ) : CryptoHistoryBook

    companion object {
        private val FRAMES = listOf(Interval.M5, Interval.M15, Interval.H1)
    }

    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): Snapshot {
        if (!NobitexReference.serves(symbol))
            throw DataFeedException("نماد $symbol جفت USDT نوبیتکس ندارد")
        val gate = SourceIntegrity.shared
        val token = gate.begin(symbol, SourceIntegrity.Channel.HISTORY)
        val frames = linkedMapOf<Interval, List<Candle>>()
        var provider = NobitexHistoryClient.PROVIDER
        for (frame in FRAMES) {
            val result = fetchFrame(symbol, frame, HistoryPolicy.fetchSize(frame),
                HistoryPolicy.minSize(frame), now)
            provider = result.provider
            val receivedAt = System.currentTimeMillis()
            val bars = result.candles
                .filter { it.closed && it.time + frame.millis <= receivedAt }
                .sortedBy { it.time }
                .distinctBy { it.time }
                .takeLast(HistoryPolicy.fetchSize(frame))
            if (!CandleSourceHealth.ready(bars, frame, receivedAt,
                    requireContiguous = true, requireVolume = false))
                throw DataFeedException(
                    "${frame.label}: کندل بستهٔ پیوسته/تازه از نوبیتکس کافی نیست " +
                        "(${bars.size}/${HistoryPolicy.minSize(frame)})")
            frames[frame] = bars
        }
        // A single publisher answered all three frames: this only clears a stale fault token,
        // it is not a second independent confirmation.
        val verifiedRevision = gate.accept(symbol, SourceIntegrity.Channel.HISTORY, token, 2)
            ?: gate.revision(symbol)
        HistorySourceAudits.record(
            symbol,
            provider,
            "مرجع اصلی رمزارز: نوبیتکس — M5/M15/H1 مستقل از یک صرافی؛ " +
                "ورود/خروج فقط با معاملهٔ اجراشدهٔ همان صرافی",
            System.currentTimeMillis(),
        )
        return Snapshot(provider, frames, verifiedRevision)
    }

    /** One interval, still Nobitex-only (used for deep history and non-M5 chart frames). */
    suspend fun fetchFrame(
        symbol: String,
        interval: Interval,
        desiredSize: Int,
        minimumSize: Int,
        now: Long = System.currentTimeMillis(),
    ): PublicCandleHistoryClient.Result {
        if (!NobitexReference.serves(symbol))
            throw DataFeedException("نماد $symbol جفت USDT نوبیتکس ندارد")
        return read(symbol, interval, desiredSize, minimumSize, now)
    }
}
