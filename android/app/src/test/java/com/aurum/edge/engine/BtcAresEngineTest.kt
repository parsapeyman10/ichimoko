package com.aurum.edge.engine

import com.aurum.edge.core.BtcAresEngineReport
import com.aurum.edge.core.BtcAresPolicy
import com.aurum.edge.core.BtcAresRecord
import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * BTC-ARES invariants. No network, no exchange, no order: every check is a rule that must hold
 * whatever the market does, because those are the rules that decide whether a phone may trade.
 */
class BtcAresEngineTest {
    private val now = 1_792_000_000_000L
    private val step = Interval.M5.millis

    /** Closed M5 bars ending exactly at the last completed boundary before [now]. */
    private fun bars(count: Int = HistoryPolicy.LIVE_MIN_CANDLES + 40, base: Double = 60_000.0,
                     amplitude: Double = 120.0, trend: Double = 0.0): List<Candle> =
        (0 until count).map { i ->
            val time = now - (count - i) * step
            val mid = base + trend * i + sin(i / 6.0) * amplitude
            Candle(time, mid, mid + amplitude * 0.4, mid - amplitude * 0.4, mid + amplitude * 0.1,
                10.0 + (i % 5), true)
        }

    private fun frames(m5: List<Candle>): Map<Interval, List<Candle>> = mapOf(
        Interval.M15 to (0 until 260).map { i ->
            Candle(m5.first().time - (260 - i) * Interval.M15.millis, 60_000.0,
                60_100.0, 59_900.0, 60_000.0 + (i % 7) * 3.0, 100.0, true)
        },
        Interval.H1 to (0 until 220).map { i ->
            Candle(m5.first().time - (220 - i) * Interval.H1.millis, 60_000.0,
                60_200.0, 59_800.0, 60_000.0 + (i % 5) * 5.0, 500.0, true)
        },
    )

    @Test fun `only BTC-USDT is accepted and the record is always attached`() {
        val m5 = bars()
        val wrongSymbol = BtcAresEngine.evaluate(m5, Interval.M5, "XAU/USD", external = frames(m5), now = now)
        assertEquals(SignalAction.NO_TRADE, wrongSymbol.action)
        assertTrue(wrongSymbol.blockers.any { it.contains("BTC/USDT") })
        assertTrue(wrongSymbol.btcAres!!.engines.isEmpty())

        val wrongInterval = BtcAresEngine.evaluate(m5, Interval.M15, "BTC/USDT", external = frames(m5), now = now)
        assertEquals(SignalAction.NO_TRADE, wrongInterval.action)
        assertTrue(wrongInterval.blockers.any { it.contains("M5") })

        val short = BtcAresEngine.evaluate(m5.take(50), Interval.M5, "BTC/USDT", now = now)
        assertEquals(SignalAction.NO_TRADE, short.action)
    }

    @Test fun `a stale last bar can never authorise an entry`() {
        val old = bars().map { it.copy(time = it.time - 3 * 86_400_000L) }
        val signal = BtcAresEngine.evaluate(old, Interval.M5, "BTC/USDT", now = now)
        assertEquals(SignalAction.NO_TRADE, signal.action)
        assertTrue(signal.blockers.any { it.contains("تازه") })
    }

    @Test fun `every evaluation reports all six engines with their own state`() {
        val m5 = bars()
        val signal = BtcAresEngine.evaluate(m5, Interval.M5, "BTC/USDT", external = frames(m5), now = now)
        val record = signal.btcAres!!
        assertEquals(6, record.engines.size)
        assertEquals(listOf("ENGINE_A_TREND", "ENGINE_B_SWEEP", "ENGINE_C_COMPRESSION",
            "ENGINE_D_RANGE", "ENGINE_E_EXHAUSTION", "ENGINE_F_DERIVATIVES"),
            record.engines.map { it.engine })
        // Engine F has no data feed in this app: it must say so, never show a fabricated score.
        val derivatives = record.engines.first { it.engine == "ENGINE_F_DERIVATIVES" }
        assertFalse(derivatives.dataAvailable)
        assertEquals("UNAVAILABLE", derivatives.state)
        assertEquals(0, derivatives.score)
        assertTrue(derivatives.detail.contains("وجود ندارد"))
        // Entry numbers exist if and only if the record says an engine is actionable.
        assertEquals(record.isActionable, record.entry != null)
        assertEquals(record.isActionable, signal.entry != null)
        assertEquals(record.isActionable, signal.action != SignalAction.NO_TRADE)
        assertTrue(signal.reasons.first().startsWith("BTC-ARES"))
        // A NO_TRADE must always explain itself; silence is what made the update bug hard to see.
        if (!record.isActionable) assertTrue(signal.blockers.isNotEmpty())
    }

    @Test fun `regime classification is deterministic and volatility uses percentiles`() {
        val cfg = BtcAresEngine.Config()
        val flat = (0 until 320).map { i ->
            Candle(now - (320 - i) * step, 60_000.0, 60_020.0, 59_980.0, 60_000.0 + (i % 3), 5.0, true)
        }
        val (calmRegime, calmVol) = BtcAresEngine.analyze(flat, config = cfg)
        assertEquals(BtcAresEngine.Regime.COMPRESSION, calmRegime)
        assertEquals(BtcAresEngine.Volatility.NORMAL, calmVol)

        // A spike far above its own median must be flagged, not silently treated as normal.
        val spiky = flat.dropLast(1) + Candle(flat.last().time, 60_000.0, 63_000.0, 57_000.0, 60_000.0, 900.0, true)
        val (_, spikyVol) = BtcAresEngine.analyze(spiky, config = cfg)
        assertTrue("a 6% bar after a 0.07% range must not be NORMAL", spikyVol != BtcAresEngine.Volatility.NORMAL)
    }

    @Test fun `pivots are invisible until their right bars have closed`() {
        val bars = (0 until 20).map { i ->
            val high = if (i == 10) 61_000.0 else 60_000.0 + (i % 2)
            val low = if (i == 10) 59_000.0 else 59_900.0 - (i % 2)
            Candle(now - (20 - i) * step, 60_000.0, high, low, 60_000.0, 10.0, true)
        }
        val early = BtcAresEngine.confirmedSwings(bars, 3, 3, upToIndex = 12)
        assertTrue("a 3-right-bar pivot at 10 is not known at 12", early.none { it.first == 10 })
        val confirmed = BtcAresEngine.confirmedSwings(bars, 3, 3, upToIndex = 13)
        assertTrue(confirmed.any { it.first == 10 && it.second == 61_000.0 })
    }

    @Test fun `position sizing uses 0_25 percent of equity and respects the minimum order`() {
        val cfg = BtcAresEngine.Config(accountEquityUsd = 10_000.0, riskPerTradePct = 0.25,
            commissionBpsPerSide = 10.0, spreadBps = 1.0, slippageBps = 2.0,
            qtyStep = 0.00001, minNotionalUsd = 5.0)
        val (qty, risk) = BtcAresEngine.positionSize(60_000.0, 59_700.0, cfg)
        assertEquals(25.0, risk, 1e-9)
        // 25 USD / (300 stop + 138 costs) USD per BTC = 0.0570776, floored to the 0.00001 step.
        assertEquals(0.05707, qty, 1e-6)
        assertTrue("size must come from risk, never from a fixed lot", qty * 60_000.0 < 4_000.0)
        assertEquals("a notional below the exchange minimum is refused, not rounded up",
            0.0, BtcAresEngine.positionSize(60_000.0, 59_700.0,
                BtcAresEngine.Config(accountEquityUsd = 10.0)).first, 1e-12)
        assertEquals(0.0, BtcAresEngine.positionSize(60_000.0, 60_000.0, cfg).first, 1e-12)
    }

    @Test fun `net reward risk pays for the round trip and a tight stop is refused`() {
        val cfg = BtcAresEngine.Config()
        // 23 bps round trip on 60,000 = 138 USD per BTC of cost, whatever the chart says.
        val tight = BtcAresEngine.netRewardRisk(60_000.0, 59_990.0, 60_020.0, cfg)
        assertTrue("a 10 USD stop cannot clear 138 USD of cost (got $tight)", tight < 1.0)
        val wide = BtcAresEngine.netRewardRisk(60_000.0, 59_300.0, 61_400.0, cfg)
        assertTrue("a wide stop must still earn after costs (got $wide)", wide >= 1.5)
        // Free trading must reproduce the gross ratio exactly, which proves the formula is linear.
        val free = BtcAresEngine.Config(commissionBpsPerSide = 0.0, spreadBps = 0.0, slippageBps = 0.0)
        assertEquals(2.0, BtcAresEngine.netRewardRisk(60_000.0, 59_700.0, 60_600.0, free), 1e-9)
        // Costs strictly reduce the ratio; they never improve it.
        assertTrue(BtcAresEngine.netRewardRisk(60_000.0, 59_700.0, 60_600.0, cfg) < 2.0)
    }

    @Test fun `a gross-only record without a net ratio cannot authorise an entry`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        assertFalse("a record that does not state its post-cost ratio is not evidence",
            record.copy(netRewardRisk = null).matches(signal))
        assertFalse(record.copy(netRewardRisk = 1.2).matches(signal))
        assertTrue(record.matches(signal))
    }

    @Test fun `a hand built record cannot authorise a signal it does not describe`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        assertTrue(record.matches(signal))
        assertTrue(TechnicalEvidence.confirmed(signal))

        // One changed field is enough to invalidate the whole authorisation.
        assertFalse(record.matches(signal.copy(stopLoss = signal.stopLoss!! - 1.0)))
        assertFalse(record.matches(signal.copy(barTime = signal.barTime - step)))
        assertFalse(record.matches(signal.copy(interval = Interval.M15)))
        assertFalse(record.matches(signal.copy(action = SignalAction.SELL)))
        assertFalse(record.copy(score = 55).matches(signal))
        assertFalse(record.copy(chosenEngine = "ENGINE_B_SWEEP").matches(signal))
        assertFalse(record.copy(positionQty = 0.0).matches(signal))
        assertFalse(TechnicalEvidence.confirmed(
            signal.copy(confluence = signal.confluence.map { it.copy(name = it.name.replace("BTC-ARES", "ALS-5")) })))
    }

    @Test fun `the btc entry policy rejects missing or wide quotes and accepts a clean one`() {
        val record = sampleRecord()
        val signal = sampleSignal(record)
        val atr = record.atr!!
        assertEquals("no quote, no entry",
            "BTC-ARES: تیک تازه نیست", BtcAresPolicy.quoteBlocker(signal, null, atr, now))
        val wide = PriceTick(60_000.0, now, 59_950.0, 60_050.0)
        assertTrue(BtcAresPolicy.quoteBlocker(signal, wide, atr, now)!!.contains("اسپرد"))
        val stale = PriceTick(60_000.0, now - 600_000L, 59_999.0, 60_001.0)
        assertEquals("BTC-ARES: تیک تازه نیست", BtcAresPolicy.quoteBlocker(signal, stale, atr, now))
        val oneSided = PriceTick(60_000.0, now, bid = 59_999.0, ask = null)
        assertTrue(BtcAresPolicy.quoteBlocker(signal, oneSided, atr, now)!!.contains("bid/ask"))
        val clean = PriceTick(60_000.0, now, 59_999.0, 60_001.0)
        assertNull(BtcAresPolicy.quoteBlocker(signal, clean, atr, now))
        assertTrue("a signal without an ARES record can never enter",
            BtcAresPolicy.quoteBlocker(signal.copy(btcAres = null), clean, atr, now)!!.contains("BTC-ARES"))
    }

    @Test fun `the compression engine refuses BTC and still serves other crypto`() {
        val m5 = bars()
        val btc = CryptoCompressionEngine.evaluate(m5, Interval.M5, "BTC/USDT", frames(m5))
        assertTrue(btc.blockers.any { it.contains("BTC-ARES") })
        val eth = CryptoCompressionEngine.evaluate(m5, Interval.M5, "ETH/USDT", frames(m5))
        assertTrue("ETH must stay on the compression model",
            eth.blockers.none { it.contains("BTC-ARES") })
    }

    @Test fun `signal engine routes btc to ares and never to compression`() {
        // Bars that end on the last closed M5 boundary of the real clock, so the routing test also
        // exercises the freshness gate that the scanner relies on.
        val live = System.currentTimeMillis()
        val lastOpen = (live / step - 1) * step
        val count = HistoryPolicy.LIVE_MIN_CANDLES + 20
        val refreshed = (0 until count).map { i ->
            val time = lastOpen - (count - 1 - i) * step
            val mid = 60_000.0 + sin(i / 6.0) * 80.0
            Candle(time, mid, mid + 40.0, mid - 40.0, mid, 12.0, true)
        }
        val signal = SignalEngine.evaluate(refreshed, Interval.M5, 85.0, "BTC/USDT",
            com.aurum.edge.core.CategoryStrategy.HYBRID, frames(refreshed))
        assertNotNull(signal.btcAres)
        assertNull("crypto compression evidence must not be produced for BTC", signal.cryptoBreakout)
        assertEquals(6, signal.btcAres!!.engines.size)
        // And the other crypto pair still goes to its own model.
        val eth = SignalEngine.evaluate(refreshed, Interval.M5, 85.0, "ETH/USDT",
            com.aurum.edge.core.CategoryStrategy.HYBRID, frames(refreshed))
        assertNull(eth.btcAres)
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun sampleRecord(): BtcAresRecord {
        val engine = BtcAresEngineReport(
            engine = "ENGINE_A_TREND",
            labelFa = "موتور A · ادامهٔ روند",
            state = "READY",
            direction = SignalAction.BUY,
            score = 78,
            structuralLevel = 59_900.0,
            invalidationLevel = 59_700.0,
            entry = 60_000.0,
            stopLoss = 59_300.0,
            takeProfit = 61_400.0,
            rewardToRisk = 2.0,
        )
        return BtcAresRecord(
            regime = "TREND_UP",
            regimeLabelFa = "روند صعودی",
            volatilityState = "NORMAL",
            atr = 300.0,
            engines = listOf(engine),
            chosenEngine = "ENGINE_A_TREND",
            action = SignalAction.BUY,
            score = 78,
            structuralLevel = 59_900.0,
            invalidationLevel = 59_700.0,
            entry = 60_000.0,
            stopLoss = 59_300.0,
            takeProfit = 61_400.0,
            rewardToRisk = 2.0,
            netRewardRisk = 1.51,
            positionQty = 0.05713,
            riskUsd = 25.0,
            setupKey = "BTC-ARES|BTC/USDT|ENGINE_A_TREND|BUY|59900.00|$now",
            barTime = now,
        )
    }

    private fun sampleSignal(record: BtcAresRecord): Signal {
        val names = listOf("رژیم و نوسان · BTC-ARES", "ساختار و سطح · BTC-ARES",
            "تأیید و موتور · BTC-ARES", "ریسک، هزینه و اجرا · BTC-ARES")
        val items = names.map {
            com.aurum.edge.core.ConfluenceItem(it, true, "ok",
                com.aurum.edge.core.ConfluenceStatus.CONFIRMED, 25)
        }
        return Signal(
            action = SignalAction.BUY,
            confidence = 100.0,
            entry = record.entry,
            stopLoss = record.stopLoss,
            takeProfit = record.takeProfit,
            riskReward = record.rewardToRisk,
            confluence = items,
            interval = Interval.M5,
            barTime = record.barTime,
            btcAres = record,
        )
    }
}
