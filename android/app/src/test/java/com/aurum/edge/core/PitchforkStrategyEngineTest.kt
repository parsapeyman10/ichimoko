package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * موتور «Trading with the Pitchfork» (پورت `pitchfork_strategy.py`):
 * ۱) هندسهٔ چنگال و قانون «عبور» عیناً کتابی باشد.
 * ۲) سوینگ‌ها بدون نگاه به آینده تأیید شوند.
 * ۳) قرارداد سرمایه همیشه برقرار بماند (۱٫۵ ≤ R ≤ ۳٫۰، جهت درست حد ضرر و حد سود).
 * ۴) استراتژی هیچ شرط یا گیتی از مدل‌های قبلی به ارث نبرد (فقط لایهٔ سرمایه مشترک است).
 */
class PitchforkStrategyEngineTest {

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double, stepMs: Long = 4 * 3_600_000L) =
        Candle(index.toLong() * stepMs, open, high, low, close, 1_000.0, true)

    /** سری با رژیم‌های متناوب (مثل دادهٔ نمایشی فایل مرجع) تا هر دو استراتژی فرصت داشته باشند. */
    private fun regimeSeries(bars: Int, amplitude: Double = 0.006, seedShift: Double = 0.0): List<Candle> {
        val out = mutableListOf<Candle>()
        var close = 100.0
        var previous = 100.0
        for (i in 0 until bars) {
            val drift = sin(i * PI / 37.0 + seedShift) * amplitude
            val wave = sin(i * PI / 11.0) * amplitude * 0.5
            close = previous * (1.0 + drift + wave)
            val open = previous
            val high = maxOf(open, close) * 1.002
            val low = minOf(open, close) * 0.998
            out.add(candle(i, open, high, low, close))
            previous = close
        }
        return out
    }

    private fun point(index: Int, price: Double) = PitchforkPoint(index, price, index)

    @Test
    fun forkGeometryFollowsTheBook() {
        val p1 = point(0, 100.0)
        val p2 = point(10, 120.0)
        val p3 = point(20, 110.0)
        val slope = ((120.0 + 110.0) / 2 - 100.0) / ((10 + 20) / 2.0 - 0.0)
        val fork = PitchforkFork(p1, p2, p3, slope)

        assertEquals(115.0, fork.median(15.0), 1e-9)
        assertEquals(120.0, fork.upper(10.0), 1e-9)
        assertEquals(110.0, fork.lower(20.0), 1e-9)
        // هر سه خط هم‌شیب‌اند.
        assertEquals(fork.median(30.0) - fork.median(29.0), fork.upper(30.0) - fork.upper(29.0), 1e-12)
    }

    @Test
    fun portionBeyondMeasuresBreakthrough() {
        // نیمی از دامنه بالای خط → ۰٫۵ ، کل دامنه بالای خط → ۱ ، هیچ → ۰
        assertEquals(0.5, PitchforkStrategyEngine.portionBeyond(110.0, 100.0, 105.0), 1e-9)
        assertEquals(1.0, PitchforkStrategyEngine.portionBeyond(110.0, 100.0, 95.0), 1e-9)
        assertEquals(0.0, PitchforkStrategyEngine.portionBeyond(110.0, 100.0, 115.0), 1e-9)
    }

    @Test
    fun swingsAreConfirmedWithoutLookAhead() {
        val candles = regimeSeries(200)
        val high = DoubleArray(candles.size) { candles[it].high }
        val low = DoubleArray(candles.size) { candles[it].low }
        val (highs, lows) = PitchforkStrategyEngine.findSwings(high, low, 3)

        assertTrue(highs.isNotEmpty() && lows.isNotEmpty())
        highs.forEach { pivot ->
            assertEquals(pivot.index + 3, pivot.confirmIndex)
            assertEquals(high[pivot.index], pivot.price, 1e-12)
            for (j in pivot.index - 3..pivot.index + 3) {
                if (j == pivot.index) continue
                if (j < pivot.index) assertTrue("چپ سقف باید اکیداً پایین‌تر باشد", high[j] < pivot.price)
                else assertTrue("راست سقف باید پایین‌تر-یا-مساوی باشد", high[j] <= pivot.price)
            }
        }
        lows.forEach { pivot ->
            for (j in pivot.index - 3..pivot.index + 3) {
                if (j == pivot.index) continue
                if (j < pivot.index) assertTrue("چپ کف باید اکیداً بالاتر باشد", low[j] > pivot.price)
                else assertTrue("راست کف باید بالاتر-یا-مساوی باشد", low[j] >= pivot.price)
            }
        }
    }

    @Test
    fun rsiAndSmaBehaveAsExpected() {
        val rising = DoubleArray(60) { 100.0 + it }
        val rsiRising = PitchforkStrategyEngine.rsi(rising, 14)
        assertTrue(rsiRising.last() > 99.0)
        assertTrue(rsiRising.all { it in 0.0..100.0 })

        val sma = PitchforkStrategyEngine.sma(DoubleArray(10) { it.toDouble() }, 5)
        assertTrue(sma[3].isNaN())
        assertEquals(2.0, sma[4], 1e-12)
        assertEquals(7.0, sma[9], 1e-12)
    }

    @Test
    fun insufficientDataIsReported() {
        val result = PitchforkStrategyEngine.evaluate(regimeSeries(30))
        assertEquals(StrategyReadinessState.INSUFFICIENT_DATA, result.readiness)
        assertEquals(SignalAction.NO_TRADE, result.action)
        assertNull(result.entry)
    }

    @Test
    fun everySignalKeepsTheRiskContract() {
        for (shift in listOf(0.0, 1.3, 2.7, 4.1)) {
            val candles = regimeSeries(420, amplitude = 0.007, seedShift = shift)
            val signals = PitchforkStrategyEngine.generateSignals(candles)
            signals.forEach { signal ->
                if (signal.side == 1) assertTrue("SL خرید پایین‌تر", signal.stop < signal.entry)
                else assertTrue("SL فروش بالاتر", signal.stop > signal.entry)
            }
            val evaluation = PitchforkStrategyEngine.evaluate(candles)
            if (!evaluation.isActionable) {
                assertNull(evaluation.takeProfit)
                continue
            }
            val entry = evaluation.entry!!
            val stop = evaluation.stopLoss!!
            val target = evaluation.takeProfit!!
            val rr = evaluation.riskReward!!
            if (evaluation.action == SignalAction.BUY) {
                assertTrue(stop < entry)
                assertTrue(target > entry)
            } else {
                assertTrue(stop > entry)
                assertTrue(target < entry)
            }
            assertTrue("R خارج از قرارداد: $rr", rr in 1.5..3.0)
            assertEquals(rr, abs(target - entry) / abs(entry - stop), 1e-6)
        }
    }

    @Test
    fun strategyIsIndependentFromPreviousModels() {
        val model = BitcoinStrategyModel.fromId("pitchfork_strategy")
        assertEquals(BitcoinStrategyModel.PITCHFORK_DEROOS, model)
        assertEquals("pitchfork_strategy", model.id)
        assertEquals(0, model.combinationCount)
        assertFalse(model.neuralLearningEnabled)
        assertTrue(model.requiresExplicitOptIn)

        val candles = regimeSeries(420, amplitude = 0.007, seedShift = 2.7)
        val direct = PitchforkStrategyEngine.evaluate(candles)
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.PITCHFORK_DEROOS,
            config = BaseStrategyConfig(
                // هر اورراید ایچیموکویی باید بی‌اثر باشد
                minRiskReward = 2.6,
                maxKijunExtensionAtr = 2.1,
                minEfficiencyRatio = 0.44,
                minDriveBooksConsensus = 9,
            ),
            macroCandles = candles,
            strategyCandles = candles,
            triggerCandles = candles,
            symbolInput = "BTC/USDT",
        )

        assertEquals("pitchfork_strategy", report.strategyId)
        assertEquals(Interval.H4, report.macroInterval)
        assertFalse(report.conditions.any { it.titleFa.contains("کومو") || it.titleFa.contains("چیکو") })
        // همان ورودی/حد ضرر/هدف موتور مستقل؛ یعنی هیچ گیتی بیرون از موتور دخالت نکرده است.
        assertEquals(direct.entry, report.suggestedEntry)
        assertEquals(direct.stopLoss, report.suggestedStopLoss)
        assertEquals(direct.takeProfit, report.primaryTakeProfit)
        assertEquals(direct.riskReward, report.riskReward)
        assertNotNull(report.summaryFa)
        assertTrue(report.summaryFa.isNotBlank())
    }

    @Test
    fun engineHandlesFlatAndTinySeriesWithoutCrashing() {
        val flat = (0 until 120).map { candle(it, 100.0, 100.0, 100.0, 100.0) }
        val flatResult = PitchforkStrategyEngine.evaluate(flat)
        assertEquals(SignalAction.NO_TRADE, flatResult.action)
        assertNull(flatResult.entry)

        val tiny = (0 until 5).map { candle(it, 1.0, 1.1, 0.9, 1.0) }
        assertEquals(SignalAction.NO_TRADE, PitchforkStrategyEngine.evaluate(tiny).action)
    }
}
