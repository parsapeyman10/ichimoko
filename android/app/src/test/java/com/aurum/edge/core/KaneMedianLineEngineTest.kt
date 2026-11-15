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
import kotlin.math.sin

/**
 * موتور «خط میانه و هم‌افزایی فیبوناچی» (پورت `kane_median_line_fibonacci_synergy.py`):
 * ۱) هندسهٔ ست‌ها باید عیناً کتابی باشد (خط میانه از وسط P2–P3، موازی‌ها و division line ها هم‌شیب).
 * ۲) زیگزاگ علّی باشد (بدون نگاه به آینده) و پیوت‌ها متناوب.
 * ۳) گروه‌بندی فیبوناچی فقط با حضور هم‌زمان عدد pattern و position معتبر است.
 * ۴) قرارداد ریسک این اپ همیشه برقرار بماند: جهت حد ضرر/حد سود درست و ۱٫۵ ≤ R ≤ ۳٫۰.
 */
class KaneMedianLineEngineTest {

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double, stepMs: Long = 4 * 3_600_000L) =
        Candle(
            time = index.toLong() * stepMs,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1_000.0,
            closed = true,
        )

    private fun wavyStructure(bars: Int, start: Double = 100.0, drift: Double = 0.25, swing: Double = 9.0): List<Candle> {
        val candles = mutableListOf<Candle>()
        var previous = start
        for (i in 0 until bars) {
            val mid = start + drift * i + swing * sin(i * PI / 14.0)
            val open = previous
            val close = mid
            val high = maxOf(open, close) + swing * 0.30
            val low = minOf(open, close) - swing * 0.30
            candles.add(candle(i, open, high, low, close))
            previous = close
        }
        return candles
    }

    @Test
    fun zigzagIsCausalAndAlternating() {
        val candles = wavyStructure(260)
        val atr = KaneMedianLineEngine.atrSeries(candles, 14)
        val high = DoubleArray(candles.size) { candles[it].high }
        val low = DoubleArray(candles.size) { candles[it].low }

        val pivots = KaneMedianLineEngine.zigzag(high, low, atr, 1.6)
        assertTrue("زیگزاگ باید پیوت بسازد", pivots.size >= 6)
        pivots.zipWithNext().forEach { (a, b) ->
            assertFalse("پیوت‌ها باید متناوب باشند", a.isHigh == b.isHigh)
            assertTrue("ترتیب زمانی", b.index > a.index)
            assertTrue("تأیید هر پیوت بعد از خودش", b.confirmIndex >= b.index)
        }
        pivots.forEach { pivot ->
            val expected = if (pivot.isHigh) high[pivot.index] else low[pivot.index]
            assertEquals(expected, pivot.price, 1e-9)
        }
        // دو اجرا روی یک داده باید خروجی یکسان بدهد.
        assertEquals(pivots, KaneMedianLineEngine.zigzag(high, low, atr, 1.6))
    }

    @Test
    fun medianSetMatchesTheBookConstruction() {
        val p1 = KanePivot(index = 0, price = 100.0, isHigh = false, confirmIndex = 3)
        val p2 = KanePivot(index = 10, price = 120.0, isHigh = true, confirmIndex = 13)
        val p3 = KanePivot(index = 20, price = 110.0, isHigh = false, confirmIndex = 23)

        val set = KaneMedianLineEngine.medianSet(p1, p2, p3, "ABCD_ML", 0)
        val ml = set.first { it.name == "ML" }
        val up = set.first { it.name == "UP" }
        val lo = set.first { it.name == "LO" }

        // شیب = (وسط قیمت P2-P3 منهای P1) تقسیم بر فاصلهٔ زمانی = (۱۱۵−۱۰۰)/۱۵.
        assertEquals(1.0, ml.slope, 1e-9)
        // خط میانه دقیقاً از وسط P2–P3 می‌گذرد.
        assertEquals((120.0 + 110.0) / 2.0, ml.y(15.0), 1e-9)
        // موازی‌ها از خود P2 و P3 می‌گذرند.
        assertEquals(120.0, up.y(10.0), 1e-9)
        assertEquals(110.0, lo.y(20.0), 1e-9)
        // همه هم‌شیب.
        assertEquals(ml.slope, up.slope, 1e-12)
        assertEquals(ml.slope, lo.slope, 1e-12)
        // division line ها و warning line ها با وزن‌های کتاب ساخته می‌شوند.
        assertEquals(11, set.size)
        assertEquals(0.7, set.first { it.name == "DIV_U" }.weight, 1e-9)
        assertEquals(0.4, set.first { it.name == "WARN_U2" }.weight, 1e-9)
    }

    @Test
    fun modifiedSchiffAnchorsAtMidpointOfP1P2() {
        val p1 = KanePivot(index = 0, price = 100.0, isHigh = false, confirmIndex = 3)
        val p2 = KanePivot(index = 10, price = 130.0, isHigh = true, confirmIndex = 13)
        val p3 = KanePivot(index = 20, price = 110.0, isHigh = false, confirmIndex = 23)

        val standard = KaneMedianLineEngine.medianSet(p1, p2, p3, "ABCD_ML", 0).first { it.name == "ML" }
        val schiff = KaneMedianLineEngine.medianSet(p1, p2, p3, "ABCD_MS", 1, schiff = true).first { it.name == "ML" }

        // مبدأ Schiff وسط P1-P2 است (هم در زمان هم در قیمت).
        assertEquals(115.0, schiff.y(5.0), 1e-9)
        assertTrue("شیب Schiff از استاندارد کمتر است", schiff.slope < standard.slope)
    }

    @Test
    fun adjustedSetLocksTheNewSwingOnTheDistalLine() {
        val r2 = KanePivot(index = 10, price = 120.0, isHigh = true, confirmIndex = 13)
        val r3 = KanePivot(index = 20, price = 100.0, isHigh = false, confirmIndex = 23)
        val c = KanePivot(index = 30, price = 130.0, isHigh = true, confirmIndex = 33)

        val set = KaneMedianLineEngine.adjustedSet(r2, r3, c, "distal", 5)
        assertTrue(set.isNotEmpty())
        val up = set.first { it.name == "UP" }
        assertEquals("قفل distal یعنی سوینگ C روی موازی دور", 130.0, up.y(30.0), 1e-9)
    }

    @Test
    fun fibonacciGroupingNeedsBothPatternAndPosition() {
        val patternOnly = listOf(
            KaneFibNumber(100.0, "AB=CD", "pat", false),
            KaneFibNumber(100.2, "AB=CD", "pat", false),
        )
        assertNull(KaneMedianLineEngine.bestGroup(patternOnly, 1.0))

        val mixed = patternOnly + KaneFibNumber(100.1, "pos 0.447", "pos", true)
        val group = KaneMedianLineEngine.bestGroup(mixed, 1.0)
        assertNotNull(group)
        assertTrue(group!!.size >= 3)
        assertTrue(group.any { it.preferred })
    }

    @Test
    fun lineHitFindsZoneCrossings() {
        val line = KaneLine(slope = 0.0, intercept = 100.0, name = "ML", family = "BIG", weight = 1.0, setId = 0)
        assertNotNull(KaneMedianLineEngine.lineHit(line, 95.0, 105.0, 0.0, 10.0, 0.5))
        assertNull(KaneMedianLineEngine.lineHit(line, 200.0, 210.0, 0.0, 10.0, 0.5))
    }

    @Test
    fun insufficientDataIsReportedExplicitly() {
        val short = wavyStructure(40)
        val result = KaneMedianLineEngine.evaluate(short, short)
        assertEquals(StrategyReadinessState.INSUFFICIENT_DATA, result.readiness)
        assertEquals(SignalAction.NO_TRADE, result.action)
        assertNull(result.entry)
        assertTrue(result.summaryFa.isNotBlank())
    }

    @Test
    fun evaluationNeverBreaksTheRiskContract() {
        for (drift in listOf(-0.45, 0.0, 0.25, 0.7)) {
            for (swing in listOf(6.0, 9.0, 15.0)) {
                val structure = wavyStructure(320, drift = drift, swing = swing)
                val entry = wavyStructure(120, drift = drift * 0.6, swing = swing * 0.5)
                    .map { c -> c.copy(time = c.time / 4) }
                val result = KaneMedianLineEngine.evaluate(structure, entry)
                if (result.action == SignalAction.NO_TRADE) {
                    assertNull(result.entry)
                    assertNull(result.stopLoss)
                    assertNull(result.takeProfit)
                    assertNull(result.riskReward)
                    continue
                }
                val entryPrice = result.entry!!
                val stop = result.stopLoss!!
                val target = result.takeProfit!!
                val rr = result.riskReward!!
                if (result.action == SignalAction.BUY) {
                    assertTrue("SL خرید پایین‌تر", stop < entryPrice)
                    assertTrue("TP خرید بالاتر", target > entryPrice)
                    assertEquals(StrategyReadinessState.SIGNAL_BUY, result.readiness)
                } else {
                    assertTrue("SL فروش بالاتر", stop > entryPrice)
                    assertTrue("TP فروش پایین‌تر", target < entryPrice)
                    assertEquals(StrategyReadinessState.SIGNAL_SELL, result.readiness)
                }
                assertTrue("R خارج از قرارداد: $rr", rr in 1.5..3.0)
                val implied = kotlin.math.abs(target - entryPrice) / kotlin.math.abs(entryPrice - stop)
                assertEquals(rr, implied, 1e-6)
            }
        }
    }

    @Test
    fun strategyIsRegisteredUnderTheRequestedNameAndHasNoIchimokuConditions() {
        val model = BitcoinStrategyModel.fromId("kane_median_line_fibonacci_synergy")
        assertEquals(BitcoinStrategyModel.KANE_ML_FIB_SYNERGY, model)
        assertEquals("kane_median_line_fibonacci_synergy", model.id)
        assertEquals(0, model.combinationCount)
        assertFalse("پارامترهای این مدل نباید به تیونینگ عصبی بروند", model.neuralLearningEnabled)
        assertTrue(model.requiresExplicitOptIn)

        val structure = wavyStructure(320)
        val entry = wavyStructure(120).map { c -> c.copy(time = c.time / 4) }
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.KANE_ML_FIB_SYNERGY,
            config = BaseStrategyConfig(),
            macroCandles = structure,
            strategyCandles = entry,
            triggerCandles = entry,
            symbolInput = "BTC/USDT",
        )
        assertEquals("kane_median_line_fibonacci_synergy", report.strategyId)
        assertEquals(Interval.H4, report.macroInterval)
        assertEquals(Interval.H1, report.strategyInterval)
        assertFalse(report.conditions.any { it.titleFa.contains("کومو") || it.titleFa.contains("چیکو") })
    }

    @Test
    fun engineRefusesToGuessOnWrongTimeframes() {
        val structure = wavyStructure(320)
        val entry = wavyStructure(120)
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.KANE_ML_FIB_SYNERGY,
            config = BaseStrategyConfig(macroIntervalLabel = "1H", strategyIntervalLabel = "15m", triggerIntervalLabel = "5m"),
            macroCandles = structure,
            strategyCandles = entry,
            triggerCandles = entry,
            symbolInput = "ETH/USDT",
        )
        assertEquals(StrategyReadinessState.INSUFFICIENT_DATA, report.readiness)
        assertEquals(SignalAction.NO_TRADE, report.action)
        assertEquals(Interval.H4, report.macroInterval)
    }

    @Test
    fun priceTextIsTrimmed() {
        assertEquals("4380.25", KaneMedianLineEngine.priceText(4380.25))
        assertEquals("100", KaneMedianLineEngine.priceText(100.0))
    }

}
