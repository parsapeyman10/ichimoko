package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.EurAresEngineReport
import com.aurum.edge.core.EurAresPolicy
import com.aurum.edge.core.EurAresRecord
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

/**
 * EUR-ARES invariants. Each assertion is a rule that decides whether a phone may open a
 * euro-dollar paper position, so none of them is cosmetic.
 */
class EurAresEngineTest {
    /** 2026-07-15 10:00 UTC — London 11:00, New York 06:00: a tradable London session. */
    private val now = java.time.Instant.parse("2026-07-15T10:00:00Z").toEpochMilli()
    private val step = Interval.M5.millis
    private val symbol = "EUR/USD"

    /** Closed M5 EUR/USD bars ending at the last completed boundary before [now]. */
    private fun bars(
        count: Int = HistoryPolicy.LIVE_MIN_CANDLES + 60,
        base: Double = 1.08500,
        amplitude: Double = 0.00120,
        trend: Double = 0.0,
    ): List<Candle> = (0 until count).map { i ->
        val time = now - (count - i) * step
        val mid = base + trend * i + sin(i / 6.0) * amplitude
        Candle(time, mid, mid + amplitude * 0.4, mid - amplitude * 0.4, mid + amplitude * 0.1,
            1_000.0 + (i % 5), true)
    }

    private fun frames(m5: List<Candle>, h1Trend: Double = 0.00002): Map<Interval, List<Candle>> = mapOf(
        Interval.M15 to (0 until 260).map { i ->
            Candle(m5.first().time - (260 - i) * Interval.M15.millis, 1.0850,
                1.0856, 1.0844, 1.0850 + (i % 7) * 0.00002, 5_000.0, true)
        },
        Interval.H1 to (0 until 220).map { i ->
            Candle(m5.first().time - (220 - i) * Interval.H1.millis, 1.0800,
                1.0806, 1.0794, 1.0800 + h1Trend * i, 50_000.0, true)
        },
        Interval.H4 to (0 until 60).map { i ->
            Candle(m5.first().time - (60 - i) * Interval.H4.millis, 1.0800,
                1.0810, 1.0790, 1.0800, 200_000.0, true)
        },
    )

    private fun evaluate(
        m5: List<Candle> = bars(),
        external: Map<Interval, List<Candle>> = frames(m5),
        config: EurAresEngine.Config = EurAresEngine.Config(),
        at: Long = now,
        symbolArg: String = symbol,
        interval: Interval = Interval.M5,
    ): Signal = EurAresEngine.evaluate(
        m5, interval, symbolArg, external = external, config = config, now = at,
    )

    // -------------------------------------------------------------------------------------
    // Identity and routing
    // -------------------------------------------------------------------------------------
    @Test fun `only EUR-USD is accepted and a record is always attached`() {
        val m5 = bars()
        val wrongSymbol = evaluate(m5, at = now, symbolArg = "GBP/USD")
        assertEquals(SignalAction.NO_TRADE, wrongSymbol.action)
        assertTrue(wrongSymbol.blockers.any { it.contains("EUR/USD") })
        assertNotNull("even a refusal must carry the EUR-ARES record", wrongSymbol.eurAres)

        val wrongInterval = evaluate(m5, interval = Interval.M15)
        assertEquals(SignalAction.NO_TRADE, wrongInterval.action)
        assertTrue(wrongInterval.blockers.any { it.contains("M5") })

        val proper = evaluate(m5)
        val record = proper.eurAres
        assertNotNull(record)
        assertEquals("EUR-ARES", record!!.strategyName)
        assertEquals("EUR/USD", record.symbol)
        assertEquals(6, record.engines.size)
        assertEquals(
            listOf("ENGINE_A_TREND", "ENGINE_B_SWEEP", "ENGINE_C_RANGE", "ENGINE_D_SESSION",
                "ENGINE_E_NEWS", "ENGINE_X_MACRO"),
            record.engines.map { it.engine },
        )
        // A NO_TRADE decision must still say why.
        if (!record.isActionable) assertTrue(record.noTradeReasons.isNotEmpty())
    }

    @Test fun `a stale or discontinuous tape is refused instead of scored`() {
        val m5 = bars()
        val stale = evaluate(m5, at = now + 30 * 60_000L)
        assertEquals(SignalAction.NO_TRADE, stale.action)
        assertTrue(stale.blockers.any { it.contains("تازه") })

        val gapped = m5.toMutableList()
        gapped[gapped.size / 2] = gapped[gapped.size / 2].copy(time = gapped[gapped.size / 2].time - step)
        val gap = evaluate(gapped)
        assertEquals(SignalAction.NO_TRADE, gap.action)
        assertTrue(gap.blockers.any { it.contains("پیوسته") })
    }

    // -------------------------------------------------------------------------------------
    // Pip arithmetic and sizing — the EUR-specific core
    // -------------------------------------------------------------------------------------
    @Test fun `round trip cost is spread plus two commissions plus two slippages in pips`() {
        // 0.1 pip spread + 2 × 0.35 pip commission (0.000035 / 0.0001) + 2 × 0.2 pip slippage.
        assertEquals(1.2, EurAresPolicy.roundTripCostPips(1.0850, 0.1), 1e-9)
        assertEquals(1.6, EurAresPolicy.roundTripCostPips(1.0850, 0.5), 1e-9)
        // A wide spread is charged to the trade, not hidden.
        assertTrue(EurAresPolicy.roundTripCostPips(1.0850, 2.0) >
            EurAresPolicy.roundTripCostPips(1.0850, 0.1))
    }

    @Test fun `net reward risk is measured after the round trip`() {
        // 10-pip risk, 20-pip reward, 1.2-pip cost.
        assertEquals((20.0 - 1.2) / (10.0 + 1.2), EurAresPolicy.netRewardRisk(1.08500, 1.08400, 1.08700, 1.2), 1e-9)
        // A 2-pip scalp cannot pay for its own costs.
        assertTrue(EurAresPolicy.netRewardRisk(1.08500, 1.08480, 1.08540, 1.2) < 1.5)
        // A target inside the cost is worth less than nothing.
        assertTrue(EurAresPolicy.netRewardRisk(1.08500, 1.07000, 1.08501, 1.2) < 0.0)
    }

    @Test fun `position size comes from the stop in pips, never a constant lot`() {
        val (lots, units, risk) = EurAresPolicy.positionLots(1.0850, 10.0, 10_000.0, 0.25, 1.2)
        // 25 USD risk ÷ (11.2 pips × 10 USD/pip) = 0.2232 → 0.22 lots, 22,000 units.
        assertEquals(0.22, lots, 1e-9)
        assertEquals(22_000.0, units, 1e-6)
        assertEquals(24.64, risk, 1e-6)

        val wide = EurAresPolicy.positionLots(1.0850, 40.0, 10_000.0, 0.25, 1.2)
        assertTrue("a wider stop must buy fewer lots", wide.first < lots)

        val tiny = EurAresPolicy.positionLots(1.0850, 10.0, 50.0, 0.25, 1.2)
        assertEquals(0.0, tiny.first, 1e-9)
    }

    @Test fun `a stop inside ordinary euro noise is never accepted`() {
        assertTrue(EurAresPolicy.MIN_STOP_PIPS >= 4.0)
        val record = sampleRecord(stopPips = 2.0)
        assertFalse("a 2-pip stop is noise and must not authorise an entry", record.matches(sampleSignal(record)))
    }

    // -------------------------------------------------------------------------------------
    // Sessions: DST-aware, rollover-aware
    // -------------------------------------------------------------------------------------
    @Test fun `the same UTC clock is a different session in winter and summer`() {
        val winter = Instant.parse("2026-01-15T12:30:00Z").toEpochMilli()
        val summer = Instant.parse("2026-07-15T12:30:00Z").toEpochMilli()
        // Winter: London 12:30, New York 07:30 → London only. Summer: London 13:30, NY 08:30 → overlap.
        assertEquals("LONDON", EurAresPolicy.sessionAt(winter).session)
        assertEquals("OVERLAP", EurAresPolicy.sessionAt(summer).session)
        assertEquals("12:30", EurAresPolicy.sessionAt(winter).londonClock)
        assertEquals("13:30", EurAresPolicy.sessionAt(summer).londonClock)
        assertEquals("07:30", EurAresPolicy.sessionAt(winter).newYorkClock)
    }

    @Test fun `the new york rollover window blocks entries and then reopens`() {
        val inWindow = Instant.parse("2026-07-15T21:05:00Z").toEpochMilli() // NY 17:05
        val afterWindow = Instant.parse("2026-07-15T21:20:00Z").toEpochMilli() // NY 17:20
        assertTrue(EurAresPolicy.sessionAt(inWindow).rolloverWindow)
        assertEquals("ROLLOVER", EurAresPolicy.sessionAt(inWindow).session)
        assertFalse(EurAresPolicy.sessionAt(afterWindow).rolloverWindow)
        assertFalse("rollover is not a tradable session",
            EurAresPolicy.isTradableSession(EurAresPolicy.sessionAt(inWindow).session))
    }

    @Test fun `a signal evaluated inside rollover carries the veto`() {
        val rollover = Instant.parse("2026-07-15T21:05:00Z").toEpochMilli()
        val m5 = bars(at = rollover)
        val signal = evaluate(m5, at = rollover)
        assertEquals(SignalAction.NO_TRADE, signal.action)
        assertTrue(signal.eurAres!!.noTradeReasons.any { it.contains("رول‌اور") })
    }

    private fun bars(at: Long, count: Int = HistoryPolicy.LIVE_MIN_CANDLES + 60): List<Candle> =
        (0 until count).map { i ->
            val time = at - (count - i) * step
            val mid = 1.08500 + sin(i / 6.0) * 0.0012
            Candle(time, mid, mid + 0.0005, mid - 0.0005, mid, 1_000.0, true)
        }

    // -------------------------------------------------------------------------------------
    // Structure, regime, volatility
    // -------------------------------------------------------------------------------------
    @Test fun `pivots stay invisible until their right bars close`() {
        val flat = bars(count = 40)
        val spiked = flat.toMutableList()
        spiked[20] = spiked[20].copy(high = 1.09200)
        val before = EurAresEngine.confirmedSwings(spiked, 3, 3, upToIndex = 22)
        assertFalse("a pivot three bars away must still be invisible", before.any { it.first == 20 })
        val after = EurAresEngine.confirmedSwings(spiked, 3, 3, upToIndex = 23)
        assertTrue(after.any { it.first == 20 && it.second == 1.09200 })
    }

    @Test fun `volatility is measured against its own history not a fixed number`() {
        val calm = bars()
        val (_, calmVol) = EurAresEngine.analyze(calm, frames(calm)[Interval.H1].orEmpty())
        assertEquals(EurAresEngine.Volatility.NORMAL, calmVol)

        val expanding = bars().toMutableList()
        val tail = expanding.takeLast(12)
        expanding.removeAll(tail)
        expanding.addAll(tail.mapIndexed { i, bar ->
            val shock = 0.0020 + i * 0.0006
            bar.copy(open = bar.close, high = bar.close + shock, low = bar.close - shock)
        })
        val (_, shockedVol) = EurAresEngine.analyze(expanding, frames(expanding)[Interval.H1].orEmpty())
        assertNotEquals("an expansion must lift the volatility state", calmVol, shockedVol)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures for the record contract
    // -------------------------------------------------------------------------------------
    private fun sampleEngine(
        engine: String = "ENGINE_A_TREND",
        score: Int = 82,
    ): EurAresEngineReport = EurAresEngineReport(
        engine = engine,
        labelFa = "موتور A · ادامهٔ روند و پولبک",
        state = "READY",
        direction = SignalAction.BUY,
        scoreOutOf100 = score,
        regimeScore = 15,
        structureScore = 15,
        entryScore = 10,
        confirmationScore = 12,
        volatilityScore = 10,
        sessionScore = 8,
        rrScore = 12,
        costScore = 0,
        structuralLevel = 1.08420,
        invalidationLevel = 1.08370,
        entry = 1.08450,
        stopLoss = 1.08350,
        takeProfit = 1.08650,
        stopPips = 10.0,
        rewardToRisk = 2.0,
        netRewardRisk = 1.68,
        costPips = 1.2,
        costToRiskFraction = 0.12,
        setupType = "TREND_PULLBACK",
    )

    private fun sampleRecord(
        engine: EurAresEngineReport = sampleEngine(),
        stopPips: Double? = 10.0,
    ): EurAresRecord = EurAresRecord(
        regime = "BULL_TREND",
        regimeLabelFa = "روند صعودی یورو",
        volatilityState = "NORMAL",
        atr = 0.00090,
        atrPips = 9.0,
        engines = listOf(engine),
        chosenEngine = "ENGINE_A_TREND",
        action = SignalAction.BUY,
        scoreOutOf100 = engine.scoreOutOf100,
        structuralLevel = 1.08420,
        invalidationLevel = 1.08350,
        entry = 1.08450,
        stopLoss = 1.08350,
        takeProfit = 1.08650,
        stopPips = stopPips,
        rewardToRisk = 2.0,
        netRewardRisk = 1.68,
        costPips = 1.2,
        costToRiskFraction = 0.12,
        positionLots = 0.22,
        positionUnits = 22_000.0,
        pipValueUsd = 10.0,
        riskUsd = 24.64,
        setupKey = "EUR-ARES|EUR/USD|ENGINE_A_TREND|TREND_PULLBACK|BUY|1.08420|$now",
        barTime = now,
    )

    private fun sampleSignal(record: EurAresRecord): Signal = Signal(
        action = SignalAction.BUY,
        confidence = 100.0,
        entry = record.entry,
        stopLoss = record.stopLoss,
        takeProfit = record.takeProfit,
        riskReward = record.rewardToRisk,
        interval = Interval.M5,
        barTime = record.barTime,
        confluence = listOf(
            "رژیم و نوسان · EUR-ARES", "ساختار و سطح · EUR-ARES",
            "تأیید و موتور · EUR-ARES", "هزینه، پیپ و اجرا · EUR-ARES",
        ).map { ConfluenceItem(it, true, "fixture", com.aurum.edge.core.ConfluenceStatus.CONFIRMED, 25) },
        eurAres = record,
    )

    @Test fun `a hand built euro record cannot authorise a signal it does not describe`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        assertTrue(record.matches(signal))
        assertTrue(TechnicalEvidence.confirmed(signal))

        assertFalse(record.matches(signal.copy(stopLoss = 1.08360)))
        assertFalse(record.matches(signal.copy(barTime = now - step)))
        assertFalse(record.matches(signal.copy(interval = Interval.M15)))
        assertFalse(record.matches(signal.copy(action = SignalAction.SELL)))
        // A net R:R that does not clear 1.5 is not evidence, and neither is a missing one.
        assertFalse(record.copy(netRewardRisk = 1.2).matches(signal))
        assertFalse(record.copy(netRewardRisk = null).matches(signal))
        // Cost above 30 % of the stop is a refusal, not a smaller trade.
        assertFalse(record.copy(costToRiskFraction = 0.31).matches(signal))
        // The score must equal its eight published components.
        assertFalse(record.copy(engines = listOf(sampleEngine().copy(costScore = 5))).matches(signal))
        // A record for another pair must never authorise this signal.
        assertFalse(record.copy(symbol = "XAG/USD").matches(signal))
        assertFalse(record.copy(strategyName = "ALS-5S").matches(signal))
    }

    @Test fun `euro evidence is not interchangeable with gold silver or V1 rows`() {
        val signal = sampleSignal(sampleRecord())
        assertTrue(TechnicalEvidence.confirmed(signal))
        val relabelled = signal.copy(confluence = signal.confluence.map {
            it.copy(name = it.name.replace("EUR-ARES", "ALS-5S"))
        })
        assertFalse(TechnicalEvidence.confirmed(relabelled))
        val goldRows = signal.copy(confluence = signal.confluence.map {
            it.copy(name = it.name.replace("EUR-ARES", "ALS-5"))
        })
        assertFalse(TechnicalEvidence.confirmed(goldRows))
    }

    @Test fun `a euro signal cannot be opened by a gold or silver quote check`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        val tick = PriceTick(1.08452, now, bid = 1.08447, ask = 1.08452)
        assertNull(EurAresPolicy.quoteBlocker(signal, tick, record.atr, now))
        // A trade print without a two-sided quote is not a spread.
        assertNotNull(EurAresPolicy.quoteBlocker(signal, tick.copy(bid = null, ask = null), record.atr, now))
        // A wide spread is refused even when the setup is otherwise perfect.
        assertNotNull(EurAresPolicy.quoteBlocker(signal,
            tick.copy(bid = 1.08300, ask = 1.08452), record.atr, now))
        // A stale tick is refused.
        assertNotNull(EurAresPolicy.quoteBlocker(signal, tick.copy(at = now - 5 * 60_000L), record.atr, now))
        // Drift away from the confirmed entry is refused.
        assertNotNull(EurAresPolicy.quoteBlocker(signal,
            tick.copy(price = 1.09000, bid = 1.08995, ask = 1.09000), record.atr, now))
        // Inside the rollover window nothing opens.
        val rollover = Instant.parse("2026-07-15T21:05:00Z").toEpochMilli()
        val rolloverSignal = signal.copy(barTime = rollover)
        assertNotNull(EurAresPolicy.quoteBlocker(
            rolloverSignal.copy(eurAres = record.copy(barTime = rollover)),
            tick.copy(at = rollover), record.atr, rollover))
    }

    @Test fun `engine E and module X report unavailable instead of inventing data`() {
        val record = evaluate(bars()).eurAres!!
        val news = record.engines.single { it.engine == "ENGINE_E_NEWS" }
        assertEquals("UNAVAILABLE", news.state)
        assertEquals(SignalAction.NO_TRADE, news.direction)
        assertFalse(news.dataAvailable)
        val macro = record.engines.single { it.engine == "ENGINE_X_MACRO" }
        assertEquals("UNAVAILABLE", macro.state)
        assertFalse(macro.dataAvailable)
        assertEquals(SignalAction.NO_TRADE, macro.direction)
        assertFalse(record.macro.available)
        assertTrue(record.dataNotes.any { it.contains("سواپ") })
    }

    @Test fun `every engine always publishes its cost in pips`() {
        val record = evaluate(bars()).eurAres!!
        assertTrue(record.costPips > 0.0)
        record.engines.filter { it.engine != "ENGINE_X_MACRO" }.forEach { engine ->
            if (engine.engine != "ENGINE_E_NEWS") {
                assertTrue("${engine.engine} must price the round trip", engine.costPips > 0.0)
            }
        }
    }

    @Test fun `regime hysteresis lets protection in at once and out slowly`() {
        val cfg = EurAresEngine.Config()
        val enter = EurAresEngine.classifyRegime(
            EurAresEngine.Regime.EVENT_UNSTABLE, EurAresEngine.Regime.RANGE, cfg)
        assertEquals(EurAresEngine.Regime.EVENT_UNSTABLE, enter.first)

        val leave = EurAresEngine.classifyRegime(
            EurAresEngine.Regime.RANGE, EurAresEngine.Regime.EVENT_UNSTABLE, cfg)
        assertEquals("protection is left only with confirmation", EurAresEngine.Regime.EVENT_UNSTABLE, leave.first)

        val stable = EurAresEngine.classifyRegime(
            EurAresEngine.Regime.RANGE, EurAresEngine.Regime.RANGE, cfg)
        assertEquals(EurAresEngine.Regime.RANGE, stable.first)

        val confirmOne = EurAresEngine.classifyRegime(
            EurAresEngine.Regime.BULL_TREND, EurAresEngine.Regime.RANGE,
            cfg.copy(regimeConfirmBars = 1))
        assertEquals("with a one-bar confirmation the new label is accepted",
            EurAresEngine.Regime.BULL_TREND, confirmOne.first)
    }

    @Test fun `the score is never described as a probability and never bypasses a veto`() {
        val record = evaluate(bars()).eurAres!!
        record.engines.forEach { engine ->
            val parts = engine.regimeScore + engine.structureScore + engine.entryScore +
                engine.confirmationScore + engine.volatilityScore + engine.sessionScore +
                engine.rrScore + engine.costScore
            assertEquals("${engine.engine} score must equal its components", engine.scoreOutOf100, parts)
            assertTrue(engine.scoreOutOf100 in 0..100)
        }
    }

    @Test fun `setup keys are namespaced by strategy symbol and bar`() {
        val record = evaluate(bars()).eurAres!!
        if (record.isActionable) {
            assertTrue(record.setupKey.startsWith("EUR-ARES|EUR/USD|"))
            assertTrue(record.setupKey.endsWith(record.barTime.toString()))
        }
        val other = EurAresRecord(
            regime = "RANGE", regimeLabelFa = "رنج", volatilityState = "NORMAL",
            setupKey = "EUR-ARES|EUR/USD|ENGINE_C_RANGE|RANGE_REVERSION|SELL|1.08500|$now",
        )
        assertNotEquals(record.setupKey, other.setupKey)
    }
}
