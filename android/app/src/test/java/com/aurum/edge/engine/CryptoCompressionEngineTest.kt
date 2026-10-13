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

    @Test fun `a zero volume recent bar blocks entry but an old zero bar is valid history`() {
        val recent = CryptoCompressionEngine.evaluate(
            five.mapIndexed { i, bar -> if (i == 305) bar.copy(volume = 0.0) else bar },
            Interval.M5, "BTC/USDT", frames, now)
        assertEquals(SignalAction.NO_TRADE, recent.action)
        assertTrue(recent.blockers.toString(), recent.blockers.any { it.contains("۴۰ کندل") })
        val old = CryptoCompressionEngine.evaluate(
            five.mapIndexed { i, bar -> if (i == 1) bar.copy(volume = 0.0) else bar },
            Interval.M5, "BTC/USDT", frames, now)
        assertEquals(SignalAction.NO_TRADE, old.action)
        assertFalse(old.blockers.toString(), old.blockers.any { it.contains("۳۲۰ کندل") })
        val noMacroVolume = CryptoCompressionEngine.evaluate(five, Interval.M5,
            "BTC/USDT", frames.mapValues { (frame, bars) ->
                if (frame == Interval.H1) bars.dropLast(1) + bars.last().copy(volume = 0.0) else bars
            }, now)
        assertEquals(SignalAction.NO_TRADE, noMacroVolume.action)
        assertTrue(noMacroVolume.blockers.toString(), noMacroVolume.blockers.any { it.contains("M15/H1") })
    }

    @Test fun `twenty genuinely quiet bars are watch only even with full compression score`() {
        val original = five
        val quiet = original.mapIndexed { i, bar ->
            bar.copy(high = 100.0 + if (i < 270) 0.6 else 0.02,
                low = 100.0 - if (i < 270) 0.6 else 0.02,
                volume = if (i < 289) 1000.0 else 10.0)
        }
        val signal = SignalEngine.evaluate(quiet, Interval.M5, 60.0, "BTC/USDT",
            timeframes = frames)
        assertEquals(signal.blockers.toString(), SignalAction.NO_TRADE, signal.action)
        assertTrue(signal.blockers.toString(), signal.blockers.any { it.contains("واچ‌لیست") })
        assertTrue(signal.reasons.toString(), signal.reasons.any { it.contains("12/26") })
        assertNull(signal.cryptoBreakout)
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

    @Test fun `new crypto trail uses entry ATR not old R multiple or unverified lines`() {
        val trade = PaperTrade("new", "BTC/USDT", Interval.M5, SignalAction.BUY,
            100.0, 98.8, 103.6, 100.0, 3.0, london,
            initialStopLoss = 98.8, trailExtreme = 100.0, cryptoEntryAtr = 1.0)
        assertEquals(98.8, com.aurum.edge.core.PaperTrailing.advance(trade,
            100.9, 100.9).stopLoss, 1e-6)
        assertEquals(100.0, com.aurum.edge.core.PaperTrailing.advance(trade,
            101.0, 101.0).stopLoss, 1e-6)
        assertEquals(100.0, com.aurum.edge.core.PaperTrailing.advance(trade,
            103.0, 103.0).stopLoss, 1e-6)
        assertEquals(101.8, com.aurum.edge.core.PaperTrailing.advance(
            trade.copy(cryptoEntryAtr = null), 103.0, 103.0).stopLoss, 1e-6)
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
