package com.aurum.edge

import com.aurum.edge.core.Candle
import com.aurum.edge.core.MarketFamily
import com.aurum.edge.core.MarketPlaybook
import com.aurum.edge.core.MarketSession
import com.aurum.edge.core.RegimeKind
import com.aurum.edge.core.TradeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * «کدام روش برای کدام بازار» — pinned with FIXED wall clocks and synthetic CLOSED candles, so a
 * refactor of the method matrix can never silently change what a market is allowed to trade.
 *
 * Every assertion here is a market truth rather than a preference: gold/FX trend in London and
 * New York, FX ranges fade in Asia, crypto only trades breakouts, US equities trade the opening
 * drive, and nobody trades the rollover hour, a volatility spike, a dead tape or the weekend.
 */
class MarketPlaybookTest {

    /** Wednesday 2026-01-07 14:00 UTC → London 14:00 AND New York 09:00 (winter, EST = UTC-5). */
    private val overlap = 1_767_794_400_000L
    /** Wednesday 2026-01-07 08:00 UTC → London 08:00 only. */
    private val london = 1_767_772_800_000L
    /** Wednesday 2026-01-07 03:00 UTC → Tokyo 12:00, London and New York closed. */
    private val tokyo = 1_767_754_800_000L
    /** Wednesday 2026-01-07 22:30 UTC → New York 17:30 = the rollover hour. */
    private val rollover = 1_767_825_000_000L
    /** Wednesday 2026-01-07 14:45 UTC → New York 09:45 = the equity opening drive. */
    private val equityOpen = 1_767_797_100_000L
    /** Wednesday 2026-01-07 20:45 UTC → New York 15:45 = the equity closing hour. */
    private val equityClose = 1_767_818_700_000L
    /** Saturday 2026-01-10 12:00 UTC → every regulated venue is closed. */
    private val saturday = 1_768_046_400_000L

    @Test fun familyIsReadFromTheSymbolAndNeverGuessed() {
        assertEquals(MarketFamily.GOLD, MarketPlaybook.familyOf("XAU/USD"))
        assertEquals(MarketFamily.GOLD, MarketPlaybook.familyOf("xauusd"))
        assertEquals(MarketFamily.SILVER, MarketPlaybook.familyOf("XAGUSD"))
        assertEquals(MarketFamily.OIL, MarketPlaybook.familyOf("USOIL"))
        assertEquals(MarketFamily.NATGAS, MarketPlaybook.familyOf("NATGAS"))
        assertEquals(MarketFamily.COPPER, MarketPlaybook.familyOf("COPPER"))
        assertEquals(MarketFamily.CRYPTO, MarketPlaybook.familyOf("BTCUSDT"))
        assertEquals(MarketFamily.US_STOCK, MarketPlaybook.familyOf("AAPL"))
        assertEquals(MarketFamily.INDEX, MarketPlaybook.familyOf("US500"))
        assertEquals(MarketFamily.FX_MAJOR, MarketPlaybook.familyOf("EUR/USD"))
        assertEquals(MarketFamily.FX_MINOR, MarketPlaybook.familyOf("AUD/USD"))
    }

    @Test fun sessionComesFromRealClocksWithDstAwareZones() {
        assertEquals(MarketSession.LONDON_NY_OVERLAP, MarketPlaybook.sessionOf("XAU/USD", overlap))
        assertEquals(MarketSession.LONDON, MarketPlaybook.sessionOf("XAU/USD", london))
        assertEquals(MarketSession.TOKYO, MarketPlaybook.sessionOf("EUR/USD", tokyo))
        assertEquals(MarketSession.ROLLOVER, MarketPlaybook.sessionOf("XAU/USD", rollover))
        assertEquals(MarketSession.WEEKEND, MarketPlaybook.sessionOf("XAU/USD", saturday))
        assertEquals(MarketSession.EQUITY_OPEN, MarketPlaybook.sessionOf("AAPL", equityOpen))
        assertEquals(MarketSession.EQUITY_CLOSE, MarketPlaybook.sessionOf("AAPL", equityClose))
        // A stock is simply closed before the bell: no session, no trade.
        assertEquals(MarketSession.OFF_HOURS, MarketPlaybook.sessionOf("AAPL", overlap))
        // Crypto never closes; the weekend is thin, not closed.
        assertEquals(MarketSession.CRYPTO_24_7, MarketPlaybook.sessionOf("BTCUSDT", saturday))
    }

    @Test fun goldTrendsInTheLondonNewYorkOverlap() {
        val decision = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 2.0, 1.5), now = overlap)

        assertEquals(MarketFamily.GOLD, decision.family)
        assertEquals(MarketSession.LONDON_NY_OVERLAP, decision.session)
        assertEquals(RegimeKind.TREND, decision.regime)
        assertEquals(TradeMethod.TREND_PULLBACK, decision.method)
        assertTrue(decision.allowed)
        assertTrue(decision.blockers.isEmpty())
        assertEquals(75, decision.minScore)
        assertEquals(72.0, decision.minConfidence, 0.001)
        assertEquals(1.5, decision.rrMin, 0.001)
        assertEquals(5.0, decision.rrMax, 0.001)
        assertEquals(0.9, decision.stopAtrMin, 0.001)
        assertEquals(2.5, decision.stopAtrMax, 0.001)
        assertEquals(25.0, decision.minRewardBps, 0.001)
        assertEquals(5.0, decision.costRewardMultiple, 0.001)
        assertFalse(decision.thinLiquidity)
        assertNotNull(decision.venue)
        assertTrue(decision.reasonFa.contains("روند"))
    }

    @Test fun goldStandsAsideOnTheWeekendAndDuringRollover() {
        val weekend = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 2.0, 1.5), now = saturday)
        assertEquals(TradeMethod.STAND_ASIDE, weekend.method)
        assertFalse(weekend.allowed)
        assertTrue(weekend.blockers.any { it.contains("تعطیل") })

        val roll = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 2.0, 1.5), now = rollover)
        assertEquals(TradeMethod.STAND_ASIDE, roll.method)
        assertFalse(roll.allowed)
        assertTrue(roll.blockers.any { it.contains("رول‌اور") })
    }

    @Test fun goldNeedsARealTrendToTradeAsia() {
        // Tokyo with a trend is tradable; Tokyo with a range is not (a fade there is noise).
        val trending = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 2.0, 1.5), now = tokyo)
        assertEquals(MarketSession.TOKYO, trending.session)
        assertTrue(trending.allowed)
        assertEquals(TradeMethod.TREND_PULLBACK, trending.method)
        // The Asian session is thinner, so the entry bar is raised.
        assertEquals(80, trending.minScore)

        val ranging = MarketPlaybook.assess("XAU/USD", rangeSeries(2000.0, 1.5), now = tokyo)
        assertEquals(RegimeKind.RANGE, ranging.regime)
        assertFalse(ranging.allowed)
        assertTrue(ranging.blockers.any { it.contains("آسیا") })
    }

    @Test fun fxMajorsFadeTheRangeInAsiaAndTrendInLondonNewYork() {
        val asia = MarketPlaybook.assess("EUR/USD", rangeSeries(1.10, 0.0008), now = tokyo)
        assertEquals(RegimeKind.RANGE, asia.regime)
        assertEquals(TradeMethod.RANGE_MEAN_REVERSION, asia.method)
        assertTrue(asia.allowed)
        assertEquals(78, asia.minScore)
        assertEquals(74.0, asia.minConfidence, 0.001)
        assertEquals(1.2, asia.rrMin, 0.001)
        assertEquals(2.2, asia.rrMax, 0.001)
        // Stop bounds belong to the FAMILY (a major pair's real spread/structure), not the method.
        assertEquals(0.7, asia.stopAtrMin, 0.001)
        assertEquals(1.6, asia.stopAtrMax, 0.001)
        assertTrue(asia.reasonFa.contains("رنج"))

        val trend = MarketPlaybook.assess("EUR/USD", trendSeries(1.10, 0.0004, 0.0008), now = overlap)
        assertEquals(TradeMethod.TREND_PULLBACK, trend.method)
        assertTrue(trend.allowed)
        assertEquals(8.0, trend.minRewardBps, 0.001)
    }

    @Test fun cryptoOnlyTradesBreakoutsAndNeverAFade() {
        val trend = MarketPlaybook.assess("BTCUSDT", trendSeries(60000.0, 400.0, 1500.0), now = overlap)
        assertEquals(TradeMethod.BREAKOUT_MOMENTUM, trend.method)
        assertTrue(trend.allowed)
        assertEquals(60.0, trend.minRewardBps, 0.001)
        assertEquals(5.0, trend.costRewardMultiple, 0.001)
        assertEquals(1.8, trend.rrMin, 0.001)
        assertEquals(1.2, trend.stopAtrMin, 0.001)
        assertEquals(3.5, trend.stopAtrMax, 0.001)
        assertFalse(trend.thinLiquidity)

        val range = MarketPlaybook.assess("BTCUSDT", rangeSeries(60000.0, 1500.0), now = overlap)
        assertEquals(TradeMethod.STAND_ASIDE, range.method)
        assertFalse(range.allowed)
        // A fade on a 21 bps round-trip venue is a fee donation, and the app says so in Persian.
        assertTrue(range.blockers.any { it.contains("هزینه") })
    }

    @Test fun cryptoOnTheWeekendNeedsATrendAndRaisesTheBar() {
        val thinRange = MarketPlaybook.assess("BTCUSDT", rangeSeries(60000.0, 1500.0), now = saturday)
        assertTrue(thinRange.thinLiquidity)
        assertFalse(thinRange.allowed)
        assertTrue(thinRange.blockers.any { it.contains("رنج") })

        val thinTrend = MarketPlaybook.assess("BTCUSDT", trendSeries(60000.0, 400.0, 1500.0), now = saturday)
        assertTrue(thinTrend.thinLiquidity)
        assertTrue(thinTrend.allowed)
        assertEquals(TradeMethod.BREAKOUT_MOMENTUM, thinTrend.method)
        assertEquals(85, thinTrend.minScore)
        assertEquals(76.0, thinTrend.minConfidence, 0.001)
    }

    @Test fun usStocksTradeTheOpeningDriveOnly() {
        val open = MarketPlaybook.assess("AAPL", trendSeries(250.0, 0.5, 1.0), now = equityOpen)
        assertEquals(MarketSession.EQUITY_OPEN, open.session)
        assertEquals(TradeMethod.OPENING_DRIVE, open.method)
        assertTrue(open.allowed)
        assertEquals(30.0, open.minRewardBps, 0.001)
        assertEquals(4.0, open.rrMax, 0.001)

        val closed = MarketPlaybook.assess("AAPL", trendSeries(250.0, 0.5, 1.0), now = overlap)
        assertEquals(MarketSession.OFF_HOURS, closed.session)
        assertFalse(closed.allowed)
        assertTrue(closed.blockers.any { it.contains("بسته") })

        // The closing half hour is exit liquidity, not an entry: stand aside.
        val late = MarketPlaybook.assess("AAPL", trendSeries(250.0, 0.5, 1.0), now = equityClose)
        assertEquals(MarketSession.EQUITY_CLOSE, late.session)
        assertFalse(late.allowed)
    }

    @Test fun aVolatilitySpikeStandsAsideEvenInTrend() {
        val spike = MarketPlaybook.assess(
            "XAU/USD", withTail(trendSeries(2000.0, 2.0, 1.5), tail = 20, wick = 12.0), now = overlap)
        assertEquals(RegimeKind.SPIKE, spike.regime)
        assertFalse(spike.allowed)
        assertTrue(spike.blockers.any { it.contains("جهش نوسان") })
        assertNotNull(spike.atrRatio)
        assertTrue(spike.atrRatio!! >= 2.5)
    }

    @Test fun aDeadTapeStandsAsideBecauseThereIsNoRangeToCapture() {
        val dead = MarketPlaybook.assess(
            "XAU/USD", withTail(trendSeries(2000.0, 0.2, 4.0), tail = 30, wick = 0.005), now = overlap)
        assertEquals(RegimeKind.DEAD, dead.regime)
        assertFalse(dead.allowed)
        assertTrue(dead.blockers.any { it.contains("بی‌جان") })
        assertNotNull(dead.atrRatio)
        assertTrue(dead.atrRatio!! <= 0.6)
    }

    @Test fun aWideSpreadIsANoTradeBecauseTheCostEatsTheMove() {
        // A tiny ATR against the real venue spread: spread/ATR far above the 25 % ceiling.
        val tight = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 0.02, 0.02), now = overlap)
        assertFalse(tight.allowed)
        assertNotNull(tight.spreadAtrRatio)
        assertTrue(tight.spreadAtrRatio!! > 0.25)
        assertTrue(tight.blockers.any { it.contains("اسپرد") })
    }

    @Test fun tooLittleHistoryIsUnknownAndNeverAGuess() {
        val short = MarketPlaybook.assess("XAU/USD", trendSeries(2000.0, 2.0, 1.5, count = 18), now = overlap)
        assertEquals(RegimeKind.UNKNOWN, short.regime)
        assertFalse(short.allowed)
        assertNull(short.efficiencyRatio)
        assertTrue(short.blockers.any { it.contains("کمتر از 40") })
    }

    @Test fun aStillFormingCandleIsNeverUsedToDecideTheMethod() {
        val base = trendSeries(2000.0, 2.0, 1.5)
        // Same history, but the last bar is still forming and prints an absurd spike. If it were
        // measured, the regime would flip to SPIKE and the market would stand aside.
        val forming = base.dropLast(1) + base.last().copy(
            open = 2000.0, high = 2100.0, low = 90.0, close = 100.0, closed = false)
        val decision = MarketPlaybook.assess("XAU/USD", forming, now = overlap)
        assertEquals(RegimeKind.TREND, decision.regime)
        assertTrue(decision.allowed)
        assertEquals(TradeMethod.TREND_PULLBACK, decision.method)
    }

    @Test fun theCostFloorIsReadFromTheRealVenueTable() {
        assertEquals(8.0, MarketPlaybook.minRewardBpsFor("EUR/USD"), 0.001)
        assertEquals(15.0, MarketPlaybook.minRewardBpsFor("AUD/USD"), 0.001)
        assertEquals(25.0, MarketPlaybook.minRewardBpsFor("XAU/USD"), 0.001)
        assertEquals(35.0, MarketPlaybook.minRewardBpsFor("XAGUSD"), 0.001)
        assertEquals(30.0, MarketPlaybook.minRewardBpsFor("USOIL"), 0.001)
        assertEquals(60.0, MarketPlaybook.minRewardBpsFor("NATGAS"), 0.001)
        assertEquals(60.0, MarketPlaybook.minRewardBpsFor("BTCUSDT"), 0.001)
        assertEquals(30.0, MarketPlaybook.minRewardBpsFor("AAPL"), 0.001)
        assertEquals(20.0, MarketPlaybook.minRewardBpsFor("US500"), 0.001)

        assertEquals(5.0, MarketPlaybook.costRewardMultipleFor("XAU/USD"), 0.001)
        assertEquals(6.0, MarketPlaybook.costRewardMultipleFor("NATGAS"), 0.001)
        assertEquals(5.0, MarketPlaybook.costRewardMultipleFor("BTCUSDT"), 0.001)
        assertEquals(5.0, MarketPlaybook.costRewardMultipleFor("EUR/USD"), 0.001)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private val baseTime = 1_767_700_000_000L

    private fun candleAt(index: Long, open: Double, high: Double, low: Double, close: Double) =
        Candle(time = baseTime + index * 300_000L, open = open, high = high, low = low, close = close, volume = 100.0)

    /** A steady trend: every bar closes `step` away from the previous one, with a fixed wick. */
    private fun trendSeries(start: Double, step: Double, wick: Double, count: Int = 60): List<Candle> =
        (0 until count).map { i ->
            val previous = start + step * (i - 1).coerceAtLeast(0)
            val close = start + step * i
            candleAt(i.toLong(), previous, max(close, previous) + wick, min(close, previous) - wick, close)
        }

    /** An alternating range: closes bounce between `start` and `start + wick`. */
    private fun rangeSeries(start: Double, wick: Double, count: Int = 60): List<Candle> =
        (0 until count).map { i ->
            val close = if (i % 2 == 0) start else start + wick
            val previous = if (i == 0 || (i - 1) % 2 == 0) start else start + wick
            candleAt(i.toLong(), previous, max(close, previous) + wick, min(close, previous) - wick, close)
        }

    /** Rebuilds the last `tail` bars around a new wick → volatility spike / dead tape fixtures. */
    private fun withTail(candles: List<Candle>, tail: Int, wick: Double): List<Candle> =
        candles.mapIndexed { index, candle ->
            if (index < candles.size - tail) candle
            else candle.copy(open = candle.close - wick / 2.0, high = candle.close + wick, low = candle.close - wick)
        }
}
