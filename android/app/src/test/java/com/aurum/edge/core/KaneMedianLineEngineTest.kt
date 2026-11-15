package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * استراتژی «خط میانه و هم‌افزایی فیبوناچی» (کین):
 * ۱) هندسهٔ فورک باید دقیقاً کتابی باشد (خط میانه از وسط P2–P3، موازی‌ها هم‌شیب).
 * ۲) انتخاب پیوت باید قطعی و متناوب باشد؛ دو اجرا روی یک داده باید نتیجهٔ یکسان بدهد.
 * ۳) قرارداد R باید همیشه برقرار باشد: حد سود و حد ضرر در جهت درست و ۱٫۵ ≤ R ≤ ۳٫۰.
 * ۴) این مدل نباید هیچ شرط ایچیموکویی را به ارث ببرد و در دادهٔ کم باید صریح «داده ناکافی» بدهد.
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

    /** ساختار موجی با دریفت صعودی: پیوت‌های متناوب و فاصلهٔ کافی برای فورک. */
    private fun trendingStructure(bars: Int, start: Double = 100.0, drift: Double = 0.30, swing: Double = 8.0): List<Candle> {
        val candles = mutableListOf<Candle>()
        var previous = start
        for (i in 0 until bars) {
            val mid = start + drift * i + swing * sin(i * PI / 12.0)
            val open = previous
            val close = mid
            val high = maxOf(open, close) + swing * 0.25
            val low = minOf(open, close) - swing * 0.25
            candles.add(candle(i, open, high, low, close))
            previous = close
        }
        return candles
    }

    @Test
    fun medianLinePassesThroughMidpointOfP2P3() {
        val p1 = KanePivot(index = 0, time = 0L, price = 100.0, isHigh = false)
        val p2 = KanePivot(index = 10, time = 0L, price = 120.0, isHigh = true)
        val p3 = KanePivot(index = 20, time = 0L, price = 110.0, isHigh = false)

        val fork = KaneMedianLineEngine.buildFork(p1, p2, p3)
        assertNotNull(fork)
        fork!!

        assertEquals(115.0, fork.midPrice, 1e-9)
        assertEquals(15.0, fork.midIndex, 1e-9)
        assertEquals(0.5, fork.slopePerBar, 1e-9)
        // خط میانه دقیقاً از وسط P2–P3 می‌گذرد.
        assertEquals(fork.midPrice, fork.medianAt(15), 1e-9)
        assertEquals(100.0, fork.medianAt(0), 1e-9)
        // شیب خط میانه و هر دو موازی یکسان است.
        assertEquals(fork.slopePerBar, fork.medianAt(40) - fork.medianAt(39), 1e-9)
        assertEquals(fork.medianAt(40) - fork.medianAt(39), fork.upperAt(40) - fork.upperAt(39), 1e-9)
        assertEquals(fork.medianAt(40) - fork.medianAt(39), fork.lowerAt(40) - fork.lowerAt(39), 1e-9)
    }

    @Test
    fun buildForkRejectsNonAlternatingTriples() {
        val high1 = KanePivot(index = 0, time = 0L, price = 100.0, isHigh = true)
        val high2 = KanePivot(index = 5, time = 0L, price = 105.0, isHigh = true)
        val low = KanePivot(index = 10, time = 0L, price = 90.0, isHigh = false)
        assertNull(KaneMedianLineEngine.buildFork(high1, high2, low))
    }

    @Test
    fun pivotDetectionIsDeterministicAndAlternating() {
        val candles = trendingStructure(150)
        val atr = KaneMedianLineEngine.averageTrueRange(candles)
        assertTrue("ATR باید مثبت باشد", atr > 0.0)

        val first = KaneMedianLineEngine.detectPivots(candles, KaneConfig(), atr)
        val second = KaneMedianLineEngine.detectPivots(candles, KaneConfig(), atr)

        assertEquals(first, second)
        assertTrue("حداقل سه پیوت لازم است، پیدا شد: ${first.size}", first.size >= 3)
        first.zipWithNext().forEach { (a, b) ->
            assertNotEquals("پیوت‌ها باید متناوب باشند", a.isHigh, b.isHigh)
            assertTrue("ترتیب زمانی پیوت‌ها باید حفظ شود", b.index > a.index)
        }
    }

    @Test
    fun efficiencyRatioSeparatesTrendFromNoise() {
        val trend = (0 until 60).map { i -> candle(i, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i) }
        val noisy = (0 until 60).map { i ->
            val base = if (i % 2 == 0) 100.0 else 101.0
            candle(i, base, base + 1.0, base - 1.0, base)
        }
        assertTrue(KaneMedianLineEngine.efficiencyRatio(trend) > 0.9)
        assertTrue(KaneMedianLineEngine.efficiencyRatio(noisy) < 0.1)
    }

    @Test
    fun insufficientDataIsReportedExplicitly() {
        val short = trendingStructure(20)
        val result = KaneMedianLineEngine.evaluate(short, short)
        assertEquals(StrategyReadinessState.INSUFFICIENT_DATA, result.readiness)
        assertEquals(SignalAction.NO_TRADE, result.action)
        assertNull(result.entry)
        assertNull(result.stopLoss)
        assertNull(result.takeProfit)
        assertTrue(result.summaryFa.isNotBlank())
    }

    @Test
    fun rejectionAtUpperParallelYieldsShortWithRiskInsideContract() {
        val structure = trendingStructure(150)
        val atrMacro = KaneMedianLineEngine.averageTrueRange(structure)
        val pivots = KaneMedianLineEngine.detectPivots(structure, KaneConfig(), atrMacro)
        assertTrue(pivots.size >= 3)
        val fork = KaneMedianLineEngine.buildFork(
            pivots[pivots.size - 3],
            pivots[pivots.size - 2],
            pivots[pivots.size - 1],
        )
        assertNotNull(fork)
        val upper = fork!!.upperAt(structure.lastIndex)

        // ۱ ساعته: قیمت به موازی بالا می‌رسد، رد می‌شود و با سایهٔ بالایی بلند بسته می‌شود.
        val entry = mutableListOf<Candle>()
        val approach = upper - 4.0
        for (i in 0 until 50) {
            entry.add(candle(i, approach, approach + 0.45, approach - 0.45, approach + 0.1, 3_600_000L))
        }
        for (i in 50 until 59) {
            val level = approach + (i - 50) * 0.6
            entry.add(candle(i, level, level + 0.45, level - 0.45, level + 0.2, 3_600_000L))
        }
        val open = upper - 1.6
        val close = upper - 1.9
        entry.add(candle(59, open, upper + 0.30, upper - 2.2, close, symbolStep()))

        val result = KaneMedianLineEngine.evaluate(
            macroCandles = structure,
            entryCandles = entry,
            config = KaneConfig(minEfficiencyRatio = 0.0, maxStopAtr = 4.0, minFibConfluence = 0),
        )

        assertEquals(SignalAction.SELL, result.action)
        assertEquals(StrategyReadinessState.SIGNAL_SELL, result.readiness)
        assertEquals(StrategyTriggerType.MEDIAN_LINE_REJECTION, result.triggerType)

        val entryPrice = result.entry!!
        val stop = result.stopLoss!!
        val target = result.takeProfit!!
        val rr = result.riskReward!!

        assertTrue("حد ضرر باید بالای ورود باشد", stop > entryPrice)
        assertTrue("حد سود باید پایین ورود باشد", target < entryPrice)
        assertTrue("R باید حداقل ۱٫۵ باشد، بود: $rr", rr >= 1.5 - 1e-9)
        assertTrue("R باید حداکثر ۳ باشد، بود: $rr", rr <= 3.0 + 1e-9)
        // حد سود و R گزارش‌شده باید با هم بخوانند.
        val implied = kotlin.math.abs(target - entryPrice) / kotlin.math.abs(entryPrice - stop)
        assertEquals(rr, implied, 1e-6)
        assertTrue(result.summaryFa.isNotBlank())
        assertTrue(result.entryPathFa.contains("Kane"))
    }

    private fun symbolStep(): Long = 3_600_000L

    @Test
    fun everyEvaluationRespectsTheRiskContract() {
        val configs = listOf(
            KaneConfig(minEfficiencyRatio = 0.0, minFibConfluence = 0),
            KaneConfig(minEfficiencyRatio = 0.0, minFibConfluence = 0, entryMode = KaneEntryMode.MEDIAN_CONTINUATION),
            KaneConfig(minEfficiencyRatio = 0.0, minFibConfluence = 0, entryMode = KaneEntryMode.PARALLEL_REJECTION),
            KaneConfig(minEfficiencyRatio = 0.0),
        )
        for (drift in listOf(-0.35, 0.0, 0.30, 0.6)) {
            for (swing in listOf(4.0, 8.0, 14.0)) {
                val structure = trendingStructure(150, drift = drift, swing = swing)
                val entry = trendingStructure(80, drift = drift * 0.5, swing = swing * 0.4)
                    .map { c -> c.copy(time = c.time / 4) }
                for (config in configs) {
                    val result = KaneMedianLineEngine.evaluate(structure, entry, config)
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
                        assertTrue("SL خرید باید پایین‌تر باشد", stop < entryPrice)
                        assertTrue("TP خرید باید بالاتر باشد", target > entryPrice)
                        assertEquals(StrategyReadinessState.SIGNAL_BUY, result.readiness)
                    } else {
                        assertTrue("SL فروش باید بالاتر باشد", stop > entryPrice)
                        assertTrue("TP فروش باید پایین‌تر باشد", target < entryPrice)
                        assertEquals(StrategyReadinessState.SIGNAL_SELL, result.readiness)
                    }
                    assertTrue("R خارج از قرارداد: $rr", rr in 1.5..3.0)
                    val implied = kotlin.math.abs(target - entryPrice) / kotlin.math.abs(entryPrice - stop)
                    assertEquals(rr, implied, 1e-6)
                }
            }
        }
    }

    @Test
    fun strategyIsRegisteredUnderTheRequestedNameAndBypassesIchimoku() {
        val model = BitcoinStrategyModel.fromId("kane_median_line_fibonacci_synergy")
        assertEquals(BitcoinStrategyModel.KANE_ML_FIB_SYNERGY, model)
        assertEquals("kane_median_line_fibonacci_synergy", model.id)
        assertEquals(0, model.combinationCount)
        assertFalse("پارامترهای این مدل نباید دست تیونینگ عصبی بیفتد", model.neuralLearningEnabled)
        assertTrue(model.requiresExplicitOptIn)

        val structure = trendingStructure(150)
        val entry = trendingStructure(80).map { c -> c.copy(time = c.time / 4) }
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
        assertTrue(report.conditions.isNotEmpty())
        // هیچ شرط ایچیموکویی نباید در چک‌لیست این مدل باشد.
        assertFalse(report.conditions.any { it.titleFa.contains("کومو") || it.titleFa.contains("چیکو") })
    }

    @Test
    fun engineRefusesToGuessOnWrongTimeframes() {
        val structure = trendingStructure(150)
        val entry = trendingStructure(80)
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
    fun fibConfluenceFindsTheRetracementBandAndExtensions() {
        val p1 = KanePivot(index = 0, time = 0L, price = 90.0, isHigh = false)
        val p2 = KanePivot(index = 10, time = 0L, price = 120.0, isHigh = true)
        val p3 = KanePivot(index = 20, time = 0L, price = 100.0, isHigh = false)
        val fork = KaneMedianLineEngine.buildFork(p1, p2, p3)!!

        // وسط موج P2-P3 (۱۱۰) داخل باند اصلاح است.
        val midHits = KaneMedianLineEngine.fibConfluence(fork, 110.0, 0.5)
        assertTrue(midHits.isNotEmpty())
        assertTrue(midHits.any { it.contains("باند اصلاح") })

        // اکستنشن ۱۲۷٫۲٪ بالای موج = ۱۲۰ + ۰٫۲۷۲×۲۰ ≈ ۱۲۵٫۴۴
        val extension = 120.0 + 20.0 * 0.272
        val extHits = KaneMedianLineEngine.fibConfluence(fork, extension + 0.2, 0.5)
        assertTrue(extHits.any { it.contains("اکستنشن ۱۲۷٫۲٪ بالا") })

        // نقطهٔ دور از هر سطح فیبوناچی هیچ هم‌افزایی ندارد.
        assertTrue(KaneMedianLineEngine.fibConfluence(fork, 300.0, 0.5).isEmpty())
    }

    @Test
    fun priceTextIsTrimmed() {
        assertEquals("4380.25", KaneMedianLineEngine.priceText(4380.25))
        assertEquals("100", KaneMedianLineEngine.priceText(100.0))
    }
}
