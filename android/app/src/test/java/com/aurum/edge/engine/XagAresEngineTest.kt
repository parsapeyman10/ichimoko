package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.core.VenueSpecs
import com.aurum.edge.core.XagAresEngineReport
import com.aurum.edge.core.XagAresPolicy
import com.aurum.edge.core.XagAresRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * ALS-5S invariants. Every assertion is a rule that must hold whatever silver does, because these
 * are the rules that decide whether a phone may open a silver paper position.
 */
class XagAresEngineTest {
    private val now = 1_792_000_000_000L
    private val step = Interval.M5.millis
    private val symbol = "XAG/USD"

    /** Closed M5 silver bars ending at the last completed boundary before [now]. */
    private fun bars(
        count: Int = HistoryPolicy.LIVE_MIN_CANDLES + 40,
        base: Double = 30.0,
        amplitude: Double = 0.06,
        trend: Double = 0.0,
    ): List<Candle> = (0 until count).map { i ->
        val time = now - (count - i) * step
        val mid = base + trend * i + sin(i / 6.0) * amplitude
        Candle(time, mid, mid + amplitude * 0.4, mid - amplitude * 0.4, mid + amplitude * 0.1,
            10.0 + (i % 5), true)
    }

    private fun frames(m5: List<Candle>): Map<Interval, List<Candle>> = mapOf(
        Interval.M15 to (0 until 260).map { i ->
            Candle(m5.first().time - (260 - i) * Interval.M15.millis, 30.0,
                30.08, 29.92, 30.0 + (i % 7) * 0.002, 100.0, true)
        },
        Interval.H1 to (0 until 220).map { i ->
            Candle(m5.first().time - (220 - i) * Interval.H1.millis, 30.0,
                30.15, 29.85, 30.0 + (i % 5) * 0.004, 500.0, true)
        },
    )

    private fun goldBars(m5: List<Candle>): List<Candle> = m5.map { bar ->
        Candle(bar.time, 2_400.0, 2_401.0, 2_399.0, 2_400.0 + (bar.close - 30.0) * 40.0, 50.0, true)
    }

    // -------------------------------------------------------------------------------------
    // Identity and routing
    // -------------------------------------------------------------------------------------
    @Test fun `only XAG-USD is accepted and the record is always attached`() {
        val m5 = bars()
        val wrongSymbol = XagAresEngine.evaluate(m5, Interval.M5, "XAU/USD", external = frames(m5), now = now)
        assertEquals(SignalAction.NO_TRADE, wrongSymbol.action)
        assertTrue(wrongSymbol.blockers.any { it.contains("XAG/USD") })
        assertTrue(wrongSymbol.als5s!!.engines.isEmpty())

        val wrongInterval = XagAresEngine.evaluate(m5, Interval.M15, symbol, external = frames(m5), now = now)
        assertEquals(SignalAction.NO_TRADE, wrongInterval.action)
        assertTrue(wrongInterval.blockers.any { it.contains("M5") })

        val short = XagAresEngine.evaluate(m5.take(50), Interval.M5, symbol, now = now)
        assertEquals(SignalAction.NO_TRADE, short.action)
        assertTrue(short.blockers.isNotEmpty())
    }

    @Test fun `every evaluation reports the five engine rows including the cross module`() {
        val m5 = bars()
        val signal = XagAresEngine.evaluate(m5, Interval.M5, symbol, external = frames(m5), now = now)
        val record = signal.als5s!!
        assertEquals("ALS-5S", record.strategyName)
        assertEquals(
            listOf("ENGINE_A_TREND", "ENGINE_B_SWEEP", "ENGINE_C_RANGE", "ENGINE_C_BREAKOUT", "ENGINE_X_CROSS"),
            record.engines.map { it.engine },
        )
        record.engines.forEach { engine ->
            assertTrue("score must stay inside 0..10", engine.scoreOutOf10 in 0..10)
            assertEquals(
                engine.scoreOutOf10,
                engine.regimeScore + engine.structureScore + engine.confirmationScore +
                    engine.rrScore + engine.executionScore,
            )
        }
        val cross = record.engines.single { it.engine == "ENGINE_X_CROSS" }
        assertEquals(SignalAction.NO_TRADE, cross.direction)
    }

    @Test fun `the cross module is unavailable without gold bars and silver keeps working`() {
        val m5 = bars()
        val withoutGold = XagAresEngine.evaluate(m5, Interval.M5, symbol, external = frames(m5), now = now)
        val cross = withoutGold.als5s!!.engines.single { it.engine == "ENGINE_X_CROSS" }
        assertFalse(cross.dataAvailable)
        assertEquals("UNAVAILABLE", cross.state)
        assertFalse(withoutGold.als5s!!.goldContext.available)
        assertTrue(withoutGold.als5s!!.dataNotes.any { it.contains("ماژول طلا") })

        val withGold = XagAresEngine.evaluate(
            m5, Interval.M5, symbol, external = frames(m5), goldBars = goldBars(m5), now = now)
        val goldCross = withGold.als5s!!.engines.single { it.engine == "ENGINE_X_CROSS" }
        assertTrue(goldCross.dataAvailable)
        assertTrue(withGold.als5s!!.goldContext.available)
        assertNotNull(withGold.als5s!!.goldContext.correlation)
        // The module is context only: it can never turn itself into an entry.
        assertEquals(SignalAction.NO_TRADE, goldCross.direction)
    }

    // -------------------------------------------------------------------------------------
    // No look-ahead, regime hysteresis
    // -------------------------------------------------------------------------------------
    @Test fun `pivots are invisible until their right bars have closed`() {
        val bars = (0 until 20).map { i ->
            Candle(i * step, 30.0, if (i == 10) 30.5 else 30.02, if (i == 10) 29.5 else 29.98, 30.0, 1.0, true)
        }
        val highsBefore = XagAresEngine.confirmedSwings(bars, 3, 3, upToIndex = 12)
        assertFalse(highsBefore.any { it.first == 10 })
        val highsAfter = XagAresEngine.confirmedSwings(bars, 3, 3, upToIndex = 13)
        assertTrue(highsAfter.any { it.first == 10 && it.second == 30.5 })
        val lowsAfter = XagAresEngine.confirmedLows(bars, 3, 3, upToIndex = 13)
        assertTrue(lowsAfter.any { it.first == 10 && it.second == 29.5 })
    }

    @Test fun `a new regime label must survive a confirmation bar unless it is protective`() {
        val cfg = XagAresEngine.Config()
        // A new, non-protective label is sticky: the previous regime survives until confirmed.
        assertEquals(XagAresEngine.Regime.RANGE,
            XagAresEngine.classifyRegime(XagAresEngine.Regime.COMPRESSION, XagAresEngine.Regime.RANGE, cfg).first)
        assertEquals(XagAresEngine.Regime.COMPRESSION,
            XagAresEngine.classifyRegime(XagAresEngine.Regime.COMPRESSION, XagAresEngine.Regime.COMPRESSION, cfg).first)
        // Protective state is entered immediately.
        assertEquals(XagAresEngine.Regime.HIGH_VOL,
            XagAresEngine.classifyRegime(XagAresEngine.Regime.HIGH_VOL, XagAresEngine.Regime.RANGE, cfg).first)
        // …and left only with confirmation.
        assertEquals(XagAresEngine.Regime.HIGH_VOL,
            XagAresEngine.classifyRegime(XagAresEngine.Regime.RANGE, XagAresEngine.Regime.HIGH_VOL, cfg).first)
        assertEquals(XagAresEngine.Regime.RANGE,
            XagAresEngine.classifyRegime(XagAresEngine.Regime.RANGE, XagAresEngine.Regime.RANGE, cfg).first)
    }

    @Test fun `volatility uses its own distribution rather than one fixed number`() {
        val cfg = XagAresEngine.Config()
        // A *constant* amplitude is self-similar: percentile ranks correctly call both tapes normal.
        // What must register is an expansion against the instrument's own recent history.
        val calm = bars(amplitude = 0.02)
        val expansion = calm.dropLast(30) + calm.takeLast(30).mapIndexed { i, bar ->
            val shock = 0.02 + i * 0.02
            bar.copy(open = bar.close, high = bar.close + shock, low = bar.close - shock, volume = 20.0)
        }
        val calmState = XagAresEngine.analyze(calm, frames(calm)[Interval.M15]!!, frames(calm)[Interval.H1]!!, config = cfg)
        val shockedState = XagAresEngine.analyze(
            expansion, frames(expansion)[Interval.M15]!!, frames(expansion)[Interval.H1]!!, config = cfg)
        assertEquals(XagAresEngine.Volatility.NORMAL, calmState.second)
        assertTrue("an expansion must lift volatility (got ${shockedState.second})",
            shockedState.second == XagAresEngine.Volatility.ELEVATED ||
                shockedState.second == XagAresEngine.Volatility.EXTREME)
    }

    // -------------------------------------------------------------------------------------
    // Costs: the silver-specific part
    // -------------------------------------------------------------------------------------
    @Test fun `the silver contract is its own and costs are charged per ounce`() {
        val spec = VenueSpecs.of(symbol)
        assertEquals(0.001, spec.tickSize, 1e-12)
        assertEquals(0.0007, spec.commissionPerUnit, 1e-9)
        // With a 2-cent spread, a 2 bp slippage allowance and 2 × commission the round trip is
        // 0.0014 + 0.0200 + 0.0120 = 0.0334 USD/oz at 30.00.
        val cost = XagAresPolicy.roundTripCostPerOz(30.0, 0.02, 2.0)
        assertEquals(0.0334, cost, 1e-9)
    }

    @Test fun `net reward risk pays for the round trip and a tight stop is refused`() {
        val cost = XagAresPolicy.roundTripCostPerOz(30.0, 0.02, 2.0)
        // A 5-cent stop with a 10-cent target cannot survive 3.3 cents of cost.
        val tight = XagAresPolicy.netRewardRisk(30.0, 29.95, 30.10, cost)
        assertTrue("tight stop must not clear the gate (got $tight)", tight < 1.5)
        val wide = XagAresPolicy.netRewardRisk(30.0, 29.60, 30.80, cost)
        assertTrue("wide stop must clear the gate (got $wide)", wide >= 1.5)
        val free = 0.0
        assertEquals(2.0, XagAresPolicy.netRewardRisk(30.0, 29.8, 30.4, free), 1e-9)
    }

    @Test fun `position size comes from risk and respects the venue step and notional cap`() {
        val cost = XagAresPolicy.roundTripCostPerOz(30.0, 0.02, 2.0)
        val (oz, risk) = XagAresPolicy.positionOz(30.0, 29.70, 10_000.0, 0.25, cost)
        assertTrue(oz > 0.0)
        assertTrue("risk must stay inside 0.25 % + tolerance", risk <= 25.0 + 1e-6)
        assertEquals(0.0, oz % 0.01, 1e-9)
        // A tiny account cannot clear the minimum notional and must be refused, not upsized.
        assertEquals(0.0, XagAresPolicy.positionOz(30.0, 29.70, 10.0, 0.25, cost).first, 1e-12)
        // Extreme volatility: the notional cap shrinks the position instead of allowing leverage.
        val (cappedOz, _) = XagAresPolicy.positionOz(30.0, 29.99, 1_000.0, 0.25, cost)
        assertTrue(cappedOz * 30.0 <= 1_000.0 * 5.0 + 1e-6)
    }

    @Test fun `an uneconomic setup produces no entry and says why`() {
        // Calm silver: stop distances are a few cents, the round trip is more than half of them.
        val m5 = bars(amplitude = 0.01)
        val signal = XagAresEngine.evaluate(m5, Interval.M5, symbol, external = frames(m5), now = now)
        assertEquals(SignalAction.NO_TRADE, signal.action)
        val reasons = signal.als5s!!.noTradeReasons.joinToString(" · ")
        assertTrue("refusal must be explained: $reasons", reasons.isNotBlank())
        assertTrue(signal.als5s!!.engines.any { it.costPerOzUsd > 0.0 })
    }

    // -------------------------------------------------------------------------------------
    // Evidence plumbing
    // -------------------------------------------------------------------------------------
    private fun sampleEngine(): XagAresEngineReport = XagAresEngineReport(
        engine = "ENGINE_A_TREND",
        labelFa = "موتور A · ادامهٔ روند",
        state = "READY",
        direction = SignalAction.BUY,
        scoreOutOf10 = 9,
        regimeScore = 2, structureScore = 2, confirmationScore = 2, rrScore = 2, executionScore = 1,
        structuralLevel = 29.90,
        invalidationLevel = 29.60,
        entry = 30.00,
        stopLoss = 29.60,
        takeProfit = 30.80,
        rewardToRisk = 2.0,
        netRewardRisk = 1.83,
        costPerOzUsd = 0.0334,
        costToRiskFraction = 0.0835,
        setupType = "TREND_CONTINUATION",
    )

    private fun sampleRecord(engine: XagAresEngineReport = sampleEngine()): XagAresRecord = XagAresRecord(
        regime = "BULL_TREND",
        regimeLabelFa = "روند صعودی نقره",
        volatilityState = "NORMAL",
        atr = 0.10,
        engines = listOf(engine),
        chosenEngine = "ENGINE_A_TREND",
        action = SignalAction.BUY,
        scoreOutOf10 = 9,
        structuralLevel = 29.90,
        invalidationLevel = 29.60,
        entry = 30.00,
        stopLoss = 29.60,
        takeProfit = 30.80,
        rewardToRisk = 2.0,
        netRewardRisk = 1.83,
        costPerOzUsd = 0.0334,
        costToRiskFraction = 0.0835,
        positionOz = 0.55,
        riskUsd = 25.0,
        setupKey = "ALS-5S|XAG/USD|ENGINE_A_TREND|TREND_CONTINUATION|BUY|29.900|$now",
        barTime = now,
    )

    private fun sampleSignal(record: XagAresRecord): Signal = Signal(
        action = SignalAction.BUY,
        confidence = 100.0,
        entry = record.entry,
        stopLoss = record.stopLoss,
        takeProfit = record.takeProfit,
        riskReward = record.rewardToRisk,
        interval = Interval.M5,
        barTime = record.barTime,
        confluence = listOf(
            "رژیم و نوسان · ALS-5S", "ساختار و سطح · ALS-5S",
            "تأیید و موتور · ALS-5S", "هزینه و اجرا · ALS-5S",
        ).map { com.aurum.edge.core.ConfluenceItem(it, true, "fixture", scorePercent = 25) },
        als5s = record,
    )

    @Test fun `a hand built silver record cannot authorise a signal it does not describe`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        assertTrue(record.matches(signal))
        assertTrue(TechnicalEvidence.confirmed(signal))

        assertFalse(record.matches(signal.copy(stopLoss = 29.5)))
        assertFalse(record.matches(signal.copy(barTime = now - step)))
        assertFalse(record.matches(signal.copy(interval = Interval.M15)))
        assertFalse(record.matches(signal.copy(action = SignalAction.SELL)))
        assertFalse(record.copy(scoreOutOf10 = 7).matches(signal))
        // A gross-only R:R is not evidence: the net number is required and must clear 1.5.
        assertFalse(record.copy(netRewardRisk = 1.2).matches(signal))
        assertFalse(record.copy(netRewardRisk = null).matches(signal))
        // The score must equal its five published sub-scores.
        assertFalse(record.copy(engines = listOf(sampleEngine().copy(rrScore = 1))).matches(signal))
    }

    @Test fun `silver evidence is not interchangeable with gold or V1 rows`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        assertTrue(TechnicalEvidence.confirmed(signal))
        val goldRows = signal.copy(confluence = signal.confluence.map {
            it.copy(name = it.name.replace("ALS-5S", "ALS-5"))
        })
        assertFalse("gold evidence must not authorise silver", TechnicalEvidence.confirmed(goldRows))
        val v1Rows = signal.copy(confluence = listOf(
            "ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
            "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی",
        ).map { com.aurum.edge.core.ConfluenceItem(it, true, "fixture", scorePercent = 25) })
        assertFalse("V1 evidence must not authorise silver", TechnicalEvidence.confirmed(v1Rows))
    }

    @Test fun `the silver entry policy rejects missing or wide quotes and accepts a clean one`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        val clean = PriceTick(30.00, now, bid = 29.995, ask = 30.005)
        assertNull(XagAresPolicy.quoteBlocker(signal, clean, 0.10, now))
        assertNotNull("no quote, no entry", XagAresPolicy.quoteBlocker(signal, null, 0.10, now))
        assertNotNull("a stale quote is not a quote",
            XagAresPolicy.quoteBlocker(signal, clean.copy(at = now - 120_000L), 0.10, now))
        assertNotNull("a one-sided print is not a spread",
            XagAresPolicy.quoteBlocker(signal, clean.copy(bid = null, ask = null), 0.10, now))
        assertNotNull("a 5-cent spread on a 10-cent ATR is too wide",
            XagAresPolicy.quoteBlocker(signal, clean.copy(bid = 29.975, ask = 30.025), 0.10, now))
        assertNotNull("a price 10 % away from the entry is stale",
            XagAresPolicy.quoteBlocker(signal, clean.copy(price = 33.0), 0.10, now))
        assertNotNull("an invalid ATR cannot authorise anything",
            XagAresPolicy.quoteBlocker(signal, clean, 0.0, now))
    }

    @Test fun `signal engine routes silver to ALSS and never to gold or V1`() {
        val m5 = bars()
        val viaEngine = SignalEngine.evaluate(m5, Interval.M5, 80.0, symbol, timeframes = frames(m5))
        assertNotNull("silver must carry its own record", viaEngine.als5s)
        assertNull("silver must not carry gold evidence", viaEngine.als5Opportunity)
        assertNull("silver must not carry V1 evidence", viaEngine.ichimokuEntry)
        assertNull("silver must not carry a BTC record", viaEngine.btcAres)

        val gold = SignalEngine.evaluate(m5, Interval.M5, 80.0, "XAU/USD", timeframes = frames(m5))
        assertNull("gold must never receive a silver record", gold.als5s)
    }
}
