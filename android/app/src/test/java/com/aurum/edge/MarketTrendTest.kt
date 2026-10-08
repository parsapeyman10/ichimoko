package com.aurum.edge

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketPlaybook
import com.aurum.edge.core.MarketTrend
import com.aurum.edge.core.RiskTone
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.SymbolTrend
import com.aurum.edge.core.TradeMethod
import com.aurum.edge.core.TrendAlignment
import com.aurum.edge.core.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «روند کلی بازار» — pinned with synthetic CLOSED candles and hand-built breadth samples.
 *
 * The point of these tests is that the market-wide trend is a MEASUREMENT, never a guess:
 * a clean uptrend is read as up on the reference timeframe, a clean downtrend as down, a flat
 * tape as sideways, and short history as UNKNOWN (with the honest Persian reason). The gate then
 * blocks a trend-following entry that fights that measurement, allows a range fade only when there
 * is no trend, and makes a risk-asset entry against the market's risk tone clear a higher bar.
 */
class MarketTrendTest {

    /** 2023-11-14 22:00 UTC — aligned to the hour so H1 buckets are exact. */
    private val start = 1_699_999_200_000L
    private val m5 = 300_000L

    private fun series(count: Int, price: (Int) -> Double): List<Candle> {
        val out = ArrayList<Candle>(count)
        var previous = price(0)
        for (i in 0 until count) {
            val close = price(i)
            out += Candle(
                time = start + i * m5,
                open = previous,
                high = maxOf(previous, close) + 0.1,
                low = minOf(previous, close) - 0.1,
                close = close,
                volume = 10.0,
                closed = true,
            )
            previous = close
        }
        return out
    }

    /** 1200 closed M5 bars = 100 H1 buckets, so the reference timeframe really can be measured. */
    private val upCandles = series(1200) { i -> 100.0 + 0.5 * i }
    private val downCandles = series(1200) { i -> 1000.0 - 0.5 * i }
    private val flatCandles = series(1200) { 100.0 }

    private fun trend(symbol: String, direction: TrendDirection, strength: Int = 60): SymbolTrend = SymbolTrend(
        symbol = symbol,
        family = MarketPlaybook.familyOf(symbol),
        intervalLabel = Interval.M5.label,
        higherLabel = Interval.H1.label,
        direction = direction,
        baseDirection = direction,
        higherDirection = direction,
        strength = strength,
        efficiencyRatio = 0.5,
        higherEfficiencyRatio = 0.5,
        slopeAtr = 0.2,
        netAtr = 4.0,
        changePct = 1.0,
        conflict = false,
        closedBars = 120,
        higherBars = 90,
        votes = emptyList(),
        detailFa = "",
    )

    @Test fun cleanUptrendIsMeasuredUpOnTheAggregatedReferenceTimeframe() {
        val read = MarketTrend.symbolTrend("BTC/USDT", upCandles, Interval.M5)
        assertEquals(TrendDirection.UP, read.direction)
        assertEquals(TrendDirection.UP, read.baseDirection)
        assertEquals(TrendDirection.UP, read.higherDirection)
        assertEquals(Interval.H1.label, read.higherLabel)
        assertTrue("تایم‌فریم مرجع باید از همان کندل‌ها تجمیع شود", read.higherBars >= MarketTrend.MIN_BARS)
        assertTrue(read.strength >= 80)
        assertFalse(read.conflict)
        assertNotNull(read.efficiencyRatio)
        assertTrue(read.efficiencyRatio!! > 0.9)
        assertTrue(read.detailFa.contains("صعودی"))
    }

    @Test fun cleanDowntrendIsMeasuredDown() {
        val read = MarketTrend.symbolTrend("XAU/USD", downCandles, Interval.M5)
        assertEquals(TrendDirection.DOWN, read.direction)
        assertEquals(TrendDirection.DOWN, read.higherDirection)
        assertTrue(read.strength >= 80)
        assertTrue(read.detailFa.contains("نزولی"))
    }

    @Test fun flatTapeIsSidewaysAndNeverBecomesATrendByGuessing() {
        val read = MarketTrend.symbolTrend("EUR/USD", flatCandles, Interval.M5)
        assertEquals(TrendDirection.SIDEWAYS, read.direction)
        assertEquals(TrendDirection.SIDEWAYS, read.higherDirection)
        assertEquals(0, read.vote)
        assertTrue("بازارِ کاملاً صاف نباید قدرتِ روند داشته باشد", read.strength <= 5)
        assertFalse(read.conflict)
    }

    @Test fun shortHistoryIsReportedAsUnknownWithAnHonestReason() {
        val read = MarketTrend.symbolTrend("AAPL", series(20) { i -> 100.0 + i }, Interval.M5)
        assertEquals(TrendDirection.UNKNOWN, read.direction)
        assertFalse(read.known)
        assertEquals(0, read.strength)
        assertTrue(read.detailFa.contains("کندل بسته"))
        assertTrue(read.detailFa.contains(MarketTrend.MIN_BARS.toString()))
    }

    @Test fun higherTimeframeIsLeftUnknownWhenThereAreNotEnoughAggregatedBars() {
        // 100 closed M5 bars are enough for the base timeframe but only 8 H1 buckets.
        val read = MarketTrend.symbolTrend("BTC/USDT", series(100) { i -> 100.0 + 0.5 * i }, Interval.M5)
        assertEquals(TrendDirection.UP, read.baseDirection)
        assertEquals(TrendDirection.UNKNOWN, read.higherDirection)
        assertTrue(read.higherBars < MarketTrend.MIN_BARS)
        // The base timeframe still answers; nothing is invented for H1.
        assertEquals(TrendDirection.UP, read.direction)
        assertTrue(read.detailFa.contains("کندل بستهٔ کافی نداریم"))
    }

    @Test fun dailyBarsHaveNoHigherTimeframeAndSaySo() {
        assertNull(MarketTrend.higherFor(Interval.D1))
        assertEquals(Interval.H1, MarketTrend.higherFor(Interval.M5))
        assertEquals(Interval.H4, MarketTrend.higherFor(Interval.M15))
        assertEquals(Interval.D1, MarketTrend.higherFor(Interval.H4))
    }

    @Test fun alignmentComparesTheEntrySideWithTheMeasuredTrend() {
        val up = MarketTrend.symbolTrend("BTC/USDT", upCandles, Interval.M5)
        val down = MarketTrend.symbolTrend("BTC/USDT", downCandles, Interval.M5)
        val flat = MarketTrend.symbolTrend("BTC/USDT", flatCandles, Interval.M5)
        assertEquals(TrendAlignment.WITH, MarketTrend.alignmentOf(SignalAction.BUY, up))
        assertEquals(TrendAlignment.AGAINST, MarketTrend.alignmentOf(SignalAction.SELL, up))
        assertEquals(TrendAlignment.WITH, MarketTrend.alignmentOf(SignalAction.SELL, down))
        assertEquals(TrendAlignment.AGAINST, MarketTrend.alignmentOf(SignalAction.BUY, down))
        assertEquals(TrendAlignment.NEUTRAL, MarketTrend.alignmentOf(SignalAction.BUY, flat))
        assertEquals(TrendAlignment.UNKNOWN, MarketTrend.alignmentOf(SignalAction.BUY, null))
        assertEquals(TrendAlignment.NEUTRAL, MarketTrend.alignmentOf(SignalAction.NO_TRADE, up))
    }

    @Test fun trendFollowingMethodsMayNotEnterAgainstTheMeasuredTrend() {
        val up = MarketTrend.symbolTrend("BTC/USDT", upCandles, Interval.M5)
        val with = MarketTrend.entryGate(SignalAction.BUY, TradeMethod.TREND_PULLBACK, up, null)
        assertTrue(with.allowed)
        assertNull(with.blockerFa)
        assertEquals(0.0, with.minConfidenceAdd, 0.0001)

        val against = MarketTrend.entryGate(SignalAction.SELL, TradeMethod.TREND_PULLBACK, up, null)
        assertFalse(against.allowed)
        assertNotNull(against.blockerFa)
        assertTrue(against.blockerFa!!.contains("خلاف جهتِ روندِ اندازه‌گیری‌شده"))
        assertTrue(against.blockerFa!!.contains(TradeMethod.TREND_PULLBACK.label))

        val breakoutAgainst = MarketTrend.entryGate(SignalAction.SELL, TradeMethod.BREAKOUT_MOMENTUM, up, null)
        assertFalse(breakoutAgainst.allowed)
    }

    @Test fun meanReversionIsOnlyLegalWhenThereIsNoTrendAtAll() {
        val flat = MarketTrend.symbolTrend("EUR/USD", flatCandles, Interval.M5)
        val up = MarketTrend.symbolTrend("EUR/USD", upCandles, Interval.M5)
        assertTrue(MarketTrend.entryGate(SignalAction.BUY, TradeMethod.RANGE_MEAN_REVERSION, flat, null).allowed)
        val againstTrend = MarketTrend.entryGate(SignalAction.BUY, TradeMethod.RANGE_MEAN_REVERSION, up, null)
        assertFalse(againstTrend.allowed)
        assertTrue(againstTrend.blockerFa!!.contains("بازارِ بدون روند"))
    }

    @Test fun missingTrendDataRaisesTheBarInsteadOfBlockingTheTrade() {
        val gate = MarketTrend.entryGate(SignalAction.BUY, TradeMethod.BREAKOUT_MOMENTUM, null, null)
        assertTrue(gate.allowed)
        assertNull(gate.blockerFa)
        assertEquals(4.0, gate.minConfidenceAdd, 0.0001)
        assertTrue(gate.noteFa.contains("اندازه گرفته نشد"))

        val sideways = MarketTrend.entryGate(
            SignalAction.BUY, TradeMethod.TREND_PULLBACK,
            MarketTrend.symbolTrend("EUR/USD", flatCandles, Interval.M5), null,
        )
        assertTrue(sideways.allowed)
        assertEquals(4.0, sideways.minConfidenceAdd, 0.0001)
    }

    @Test fun riskAssetEntryAgainstTheMarketToneCostsMoreButIsNotForbidden() {
        val btcUp = MarketTrend.symbolTrend("BTC/USDT", upCandles, Interval.M5)
        val riskOff = MarketTrend.overall(
            listOf(
                trend("BTC/USDT", TrendDirection.DOWN),
                trend("ETH/USDT", TrendDirection.DOWN),
                trend("SOL/USDT", TrendDirection.DOWN),
                trend("AAPL", TrendDirection.DOWN),
                trend("NVDA", TrendDirection.DOWN),
                trend("XAU/USD", TrendDirection.UP),
                trend("EUR/USD", TrendDirection.DOWN),
                trend("GBP/USD", TrendDirection.DOWN),
                trend("USD/JPY", TrendDirection.UP),
                trend("USD/CHF", TrendDirection.UP),
            ),
        )
        assertEquals(RiskTone.RISK_OFF, riskOff.riskTone)
        assertEquals(TrendDirection.DOWN, riskOff.bias)
        assertEquals(TrendDirection.UP, riskOff.dollarBias)

        // A BUY on a risk asset while the tape is risk-off is not forbidden, it gets expensive:
        // +6 score and +5 confidence for fighting the tone, +2 more for fighting the market bias.
        val gate = MarketTrend.entryGate(SignalAction.BUY, TradeMethod.BREAKOUT_MOMENTUM, btcUp, riskOff)
        assertTrue(gate.allowed)
        assertEquals(6, gate.minScoreAdd)
        assertEquals(7.0, gate.minConfidenceAdd, 0.0001)
        assertTrue(gate.noteFa.contains("جوّ کلی بازار"))
        assertTrue(gate.noteFa.contains("خلاف جهت کلی بازار"))
    }

    @Test fun breadthAcrossTheScannedUniverseDecidesTheMarketWideBias() {
        val bullish = MarketTrend.overall(
            listOf(
                trend("BTC/USDT", TrendDirection.UP),
                trend("ETH/USDT", TrendDirection.UP),
                trend("SOL/USDT", TrendDirection.UP),
                trend("AAPL", TrendDirection.UP),
                trend("TSLA", TrendDirection.DOWN),
                trend("XAU/USD", TrendDirection.SIDEWAYS),
            ),
        )
        assertEquals(4, bullish.breadthUp)
        assertEquals(1, bullish.breadthDown)
        assertEquals(1, bullish.breadthFlat)
        assertEquals(6, bullish.measured)
        assertEquals(TrendDirection.UP, bullish.bias)
        assertEquals(RiskTone.RISK_ON, bullish.riskTone)
        assertTrue(bullish.known)
        assertTrue(bullish.reasonFa.contains("روند کلی بازار صعودی"))
        assertTrue(bullish.families.isNotEmpty())
        assertTrue(bullish.drivers.any { it.contains("عرض بازار") })

        val balanced = MarketTrend.overall(
            listOf(
                trend("BTC/USDT", TrendDirection.UP),
                trend("ETH/USDT", TrendDirection.UP),
                trend("SOL/USDT", TrendDirection.DOWN),
                trend("AAPL", TrendDirection.DOWN),
                trend("XAU/USD", TrendDirection.SIDEWAYS),
                trend("EUR/USD", TrendDirection.SIDEWAYS),
            ),
        )
        assertEquals(TrendDirection.SIDEWAYS, balanced.bias)
    }

    @Test fun tooFewMeasuredSymbolsMeansTheMarketTrendIsUnknownNotGuessed() {
        val read = MarketTrend.overall(
            listOf(trend("BTC/USDT", TrendDirection.UP), trend("ETH/USDT", TrendDirection.UP)),
        )
        assertEquals(TrendDirection.UNKNOWN, read.bias)
        assertFalse(read.known)
        assertEquals(RiskTone.UNKNOWN, read.riskTone)
        assertEquals(2, read.measured)
        assertTrue(read.drivers.first().contains(MarketTrend.MIN_BREADTH.toString()))
        assertTrue(read.reasonFa.contains("اندازه گرفته نشد"))

        val empty = MarketTrend.overall(emptyList())
        assertEquals(TrendDirection.UNKNOWN, empty.bias)
        assertEquals(TrendDirection.UNKNOWN, empty.dollarBias)
        assertEquals(0, empty.measured)
    }

    @Test fun dollarDirectionRespectsWhichSideOfThePairTheDollarSitsOn() {
        // Dollar strong: the USD-base pairs rise while the USD-quote pairs fall.
        val strongDollar = MarketTrend.overall(
            listOf(
                trend("USD/JPY", TrendDirection.UP),
                trend("USD/CHF", TrendDirection.UP),
                trend("USD/CAD", TrendDirection.UP),
                trend("EUR/USD", TrendDirection.DOWN),
                trend("GBP/USD", TrendDirection.DOWN),
                trend("AUD/USD", TrendDirection.DOWN),
                trend("XAU/USD", TrendDirection.UP),
                trend("BTC/USDT", TrendDirection.DOWN),
            ),
        )
        assertEquals(TrendDirection.UP, strongDollar.dollarBias)
        assertEquals(RiskTone.RISK_OFF, strongDollar.riskTone)

        // Same six majors but sideways ⇒ no dollar direction is claimed.
        val flatDollar = MarketTrend.overall(
            listOf(
                trend("USD/JPY", TrendDirection.SIDEWAYS),
                trend("USD/CHF", TrendDirection.SIDEWAYS),
                trend("USD/CAD", TrendDirection.SIDEWAYS),
                trend("EUR/USD", TrendDirection.SIDEWAYS),
                trend("GBP/USD", TrendDirection.SIDEWAYS),
                trend("BTC/USDT", TrendDirection.UP),
            ),
        )
        assertEquals(TrendDirection.SIDEWAYS, flatDollar.dollarBias)
    }

    @Test fun contextBundlesTheReadForTheJournalSnapshot() {
        val up = MarketTrend.symbolTrend("BTC/USDT", upCandles, Interval.M5)
        val overall = MarketTrend.overall(
            listOf(
                trend("BTC/USDT", TrendDirection.UP),
                trend("ETH/USDT", TrendDirection.UP),
                trend("SOL/USDT", TrendDirection.UP),
                trend("AAPL", TrendDirection.UP),
                trend("XAU/USD", TrendDirection.SIDEWAYS),
            ),
        )
        val context = MarketTrend.contextOf(SignalAction.BUY, up, overall, TradeMethod.BREAKOUT_MOMENTUM)
        assertEquals(TrendAlignment.WITH, context.alignment)
        assertTrue(context.gate.allowed)
        assertTrue(context.noteFa.contains("روند کلی بازار"))
        assertTrue(context.noteFa.contains("جایگاه ورود"))

        val record = com.aurum.edge.core.MarketTrendRecord.from(context)
        assertEquals("WITH", record.alignment)
        assertEquals("UP", record.bias)
        assertTrue(record.aligned)
        assertFalse(record.against)
        assertNotNull(record.symbol)
        assertEquals("UP", record.symbol?.direction)
        assertEquals(up.strength, record.symbol?.strength)
    }
}
