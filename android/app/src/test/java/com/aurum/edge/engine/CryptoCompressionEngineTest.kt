package com.aurum.edge.engine

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.CryptoBreakoutEvidence
import com.aurum.edge.core.CryptoCompressionPolicy
import com.aurum.edge.core.IchimokuEntryEvidence
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic policy fixtures, NOT proof of profitable performance or available venue quotes. */
class CryptoCompressionEngineTest {
    private val now = System.currentTimeMillis()
    private val london = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    private fun bars(frame: Interval, volume: Double = 100.0): List<Candle> {
        val end = now / frame.millis * frame.millis - frame.millis
        return (0 until 320).map { j ->
            Candle(end - (319 - j)*frame.millis, 100.0, 100.3, 99.7, 100.0, volume)
        }
    }
    private val five get() = bars(Interval.M5)
    private val frames get() = mapOf(Interval.M15 to bars(Interval.M15), Interval.H1 to bars(Interval.H1))

    @Test fun `crypto does not fall through to EMA RSI range or old breakout when setup is absent`() {
        val trend = SignalEngine.evaluate(five, Interval.M5, 60.0, "BTC/USDT",
            CategoryStrategy.TREND, frames)
        val range = SignalEngine.evaluate(five, Interval.M5, 95.0, "BTC/USDT",
            CategoryStrategy.RANGE, frames)
        assertEquals(SignalAction.NO_TRADE, trend.action)
        assertEquals(trend.blockers, range.blockers)
        assertNull(trend.cryptoBreakout)
        assertFalse(trend.reasons.any { it.contains("EMA") || it.contains("RSI") })
        assertEquals(SignalAction.NO_TRADE, SignalEngine.evaluate(five, Interval.M5, 60.0,
            "BTC/USDT", timeframes = frames - Interval.H1).action)
        assertTrue(SignalEngine.evaluate(five, Interval.M5, 60.0,
            "BTC/USDT", timeframes = frames - Interval.H1).blockers.single().contains("H1"))
        assertEquals(SignalAction.NO_TRADE, SignalEngine.evaluate(bars(Interval.M15),
            Interval.M15, 60.0, "BTC/USDT", timeframes = frames).action)
        assertEquals(SignalAction.NO_TRADE, SignalEngine.evaluate(
            five.dropLast(1) + five.last().copy(volume = 0.0),
            Interval.M5, 60.0, "BTC/USDT", timeframes = frames).action)
    }

    private val names = listOf("فشردگی · ۲۰ کندل", "انفجار · حجم/ADX/چیکو",
        "فیلتر کلان · ۵۲/۱۰۳ و M15/H1", "ورود · کندل/ریسک/سشن")
    private fun proof(): Signal {
        val bar = london - Interval.M5.millis
        return Signal(SignalAction.BUY, 100.0, entry = 100.0, stopLoss = 99.0,
            takeProfit = 103.0, interval = Interval.M5, barTime = bar,
            cryptoBreakout = CryptoBreakoutEvidence(SignalAction.BUY, bar, bar,
                bar - 20*Interval.M5.millis, 1.0, 20, "FLAG"),
            ichimokuEntry = IchimokuEntryEvidence(SignalAction.BUY, bar, bar, Interval.M5, 5),
            confluence = names.map { ConfluenceItem(it, true, "synthetic", scorePercent = 25) })
    }

    @Test fun `crypto proof cannot be replaced by old four layers or weakened RR`() {
        val signal = proof()
        assertTrue(TechnicalEvidence.confirmed(signal))
        assertFalse(TechnicalEvidence.confirmed(signal.copy(takeProfit = 102.0)))
        assertFalse(TechnicalEvidence.confirmed(signal.copy(cryptoBreakout = null)))
        assertFalse(TechnicalEvidence.confirmed(signal.copy(confluence = signal.confluence.map {
            it.copy(name = "ساختار روند · EMA + ایچیموکو") })))
    }

    @Test fun `spread must be measured from independent top of book and under tenth ATR`() {
        val signal = proof()
        assertNull(CryptoCompressionPolicy.quoteBlocker(signal,
            PriceTick(100.0, london, bid = 99.98, ask = 100.02), london))
        assertNotNull(CryptoCompressionPolicy.quoteBlocker(signal, PriceTick(100.0, london), london))
        assertNotNull(CryptoCompressionPolicy.quoteBlocker(signal,
            PriceTick(100.0, london, bid = 99.94, ask = 100.06), london))
        assertNotNull(CryptoCompressionPolicy.quoteBlocker(signal,
            PriceTick(100.0, london - 46_000L, bid = 99.98, ask = 100.02), london))
        assertNotNull(CryptoCompressionPolicy.quoteBlocker(signal,
            PriceTick(101.0, london, bid = 100.98, ask = 101.02), london))
        assertFalse(CryptoCompressionPolicy.londonOrNewYork(
            Instant.parse("2026-10-13T23:00:00Z").toEpochMilli()))
    }

    @Test fun `fifth crypto entry fills the UTC day cap even when earlier trades are closed`() {
        val signal = proof()
        val earlier = (0 until 5).map { id -> PaperTrade(id.toString(), "BTC/USDT", Interval.M5,
            SignalAction.BUY, 100.0, 99.0, 103.0, 100.0, 3.0,
            london - 2*60*60*1000L - id*60_000L, closedAt = london - 1_000L, pnlUsd = 1.0) }
        assertTrue(PaperPortfolioPolicy.blocker(earlier, "ETH/USDT", 100_000.0, 0.5,
            now = london).orEmpty().contains("۵ ورود"))
        assertEquals(AssetClass.CRYPTO, AssetClass.of("ETH/USDT"))
        assertTrue(signal.cryptoBreakout!!.matches(signal))
    }
}
