package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * دیباگر مدیریت سرمایه باید با **همان موتور واقعی** کار کند و ناورداها را واقعاً بسنجد.
 *
 * این تست‌ها هم مسیر خوش (طلا/فارکس/بیت‌کوین) و هم مسیر بد (کارمزد سنگین کریپتو، معاملهٔ دست‌کاری‌شده)
 * را می‌سنجند تا مشخص شود چراغ سبز/قرمز معنا دارد و همیشه سبز نیست.
 */
class CapitalDebugTraceTest {

    private val goldPolicy = CapitalPolicy(
        accountBalance = 100_000.0,
        useFixedDollarRisk = true,
        fixedRiskUsd = 5.0,
    )

    private fun check(trace: CapitalTrace, titleFragment: String): CapitalTraceCheck? =
        trace.checks.firstOrNull { it.titleFa.contains(titleFragment) }

    @Test
    fun `gold trace walks ten steps and every invariant is green`() {
        val trace = CapitalDebugTrace.simulate(
            policy = goldPolicy,
            symbol = "XAU/USD",
            referencePrice = 2_600.0,
            stopPercentOfPrice = 0.5,
            targetR = 2.0,
        )
        assertNotNull("پلن باید ساخته شود", trace)
        trace!!
        assertEquals("برای ۱۰ مرحله شمارش‌شده", 10, trace.steps.size)
        assertTrue("هر مرحله باید عنوان و مقدار داشته باشد", trace.steps.all { it.titleFa.isNotBlank() && it.valueFa.isNotBlank() })
        assertTrue("همهٔ ناورداها باید سبز باشند: " + trace.checks.filter { !it.passed }.joinToString { it.titleFa }, trace.isHealthy)
        assertTrue("دست‌کم ۹ ناوردا", trace.checks.size >= 9)
        assertEquals(trace.checks.size, trace.passedCount)

        // همان اعدادی که موتور واقعی می‌دهد، نه عدد دستیِ تست.
        assertEquals(5.0, trace.result.riskBudgetUsd, 0.01)
        assertTrue("زیان خالص نباید از بودجه بیشتر شود", trace.result.netLossAtSlUsd <= 5.02)
        assertTrue("زیان خالص نباید یک گام بیشتر عقب باشد", trace.result.netLossAtSlUsd >= 4.85)
        assertTrue(trace.result.netProfitAtTpUsd in 7.5..15.0)
    }

    @Test
    fun `net reward stays inside the one point five to three R band for every preset`() {
        listOf(1.5, 2.0, 2.5, 3.0).forEach { r ->
            val trace = CapitalDebugTrace.simulate(
                policy = goldPolicy,
                symbol = "XAU/USD",
                referencePrice = 2_600.0,
                stopPercentOfPrice = 0.5,
                targetR = r,
            )
            assertNotNull("R=$r باید پلن بسازد", trace)
            val profit = trace!!.result.netProfitAtTpUsd
            assertTrue("با $r R سود خالص باید بین ۷٫۵ و ۱۵ دلار باشد، بود $profit", profit in 7.4..15.05)
            assertTrue("ناوردای دالان باید سبز باشد (R=$r)", check(trace, "دالان")?.passed == true)
        }
    }

    @Test
    fun `the trace is honest about heavy crypto fees instead of showing all green`() {
        // BNB با حد ضرر ۰٫۲۵٪: کارمزد رفت‌وبرگشت ۱٫۴۷ دلار در هر واحد، یعنی بیش از یک‌چهارم ریسک.
        val trace = CapitalDebugTrace.simulate(
            policy = goldPolicy,
            symbol = "BNB/USDT",
            referencePrice = 700.0,
            stopPercentOfPrice = 0.25,
            targetR = 2.5,
        )
        assertNotNull(trace)
        trace!!
        val feeCheck = check(trace, "کارمزد کمتر از یک‌چهارم")
        assertNotNull("ناوردای کارمزد باید وجود داشته باشد", feeCheck)
        assertFalse("کارمزد سنگین باید قرمز شود", feeCheck!!.passed)
        assertTrue("با یک قرمز، گزارش سالم نیست", !trace.isHealthy)
        // ولی پول قانون است: سود خالص دقیقاً ۲٫۵R می‌ماند و هدف قیمتی برای پوشش کارمزد دورتر می‌رود.
        assertEquals(12.5, trace.result.netProfitAtTpUsd, 0.05)
        assertTrue("دالان ۱٫۵–۳R باید سبز بماند", check(trace, "دالان")?.passed == true)
        assertTrue("سود خالص باید دقیقاً R × مبلغ ریسک باشد", check(trace, "R × مبلغ ریسک")?.passed == true)
    }

    @Test
    fun `sell side trace keeps the directions and the same dollar numbers`() {
        val trace = CapitalDebugTrace.simulate(
            policy = goldPolicy,
            symbol = "EUR/USD",
            referencePrice = 1.0850,
            stopPercentOfPrice = 0.2,
            targetR = 2.0,
            action = SignalAction.SELL,
        )
        assertNotNull(trace)
        trace!!
        val result = trace.result
        assertEquals(SignalAction.SELL, result.action)
        assertTrue("حد ضرر فروش باید بالای ورود باشد", result.stopLoss > result.entry)
        assertTrue("حد سود فروش باید زیر ورود باشد", result.takeProfit < result.entry)
        assertTrue("زیان خالص نزدیک ۵ دلار", result.netLossAtSlUsd in 4.9..5.02)
        assertTrue("ناوردای جهت باید سبز باشد", check(trace, "جهت حد ضرر")?.passed == true)
        assertTrue(trace.checks.all { it.passed } || trace.checks.count { !it.passed } <= 1)
    }

    @Test
    fun `a stored trade is recomputed and matches itself`() {
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 2_650.0,
            stopLoss = 2_640.0,
            takeProfit = 2_670.0,
            preferredAction = SignalAction.BUY,
            fixedRiskUsdOverride = 5.0,
        )
        assertNotNull(calc)
        val trade = calc!!.toPaperTrade(id = "TRACE-AUDIT-0001", entryInterval = com.aurum.edge.domain.Interval.M15)

        val trace = CapitalDebugTrace.auditTrade(trade, goldPolicy)
        assertNotNull("بازرسی باید نتیجه بدهد", trace)
        trace!!
        val comparisons = trace.checks.filter {
            it.titleFa.contains("ذخیره‌شده") || it.titleFa.contains("R ذخیره")
        }
        assertTrue("چک‌های مقایسه‌ای باید ساخته شوند", comparisons.size >= 4)
        val failed = comparisons.filter { !it.passed }
        assertTrue(
            "معاملهٔ سالم نباید اختلاف نشان دهد: " + failed.joinToString { it.titleFa + " → " + it.detailFa },
            failed.isEmpty(),
        )
        assertTrue(trace.headlineFa.contains("TRACE-AU"))
    }

    @Test
    fun `a tampered stored trade is caught`() {
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 2_650.0,
            stopLoss = 2_640.0,
            takeProfit = 2_670.0,
            preferredAction = SignalAction.BUY,
            fixedRiskUsdOverride = 5.0,
        )!!
        val honest = calc.toPaperTrade(id = "TRACE-AUDIT-0002", entryInterval = com.aurum.edge.domain.Interval.M15)
        val tampered = honest.copy(positionOz = honest.positionOz * 0.5)

        val trace = CapitalDebugTrace.auditTrade(tampered, goldPolicy)
        assertNotNull(trace)
        val unitsCheck = trace!!.checks.first { it.titleFa.contains("حجم ذخیره‌شده") }
        assertFalse("حجم نصف‌شده باید قرمز شود", unitsCheck.passed)
        assertTrue("گزارش باید ناسالم اعلام شود", !trace.isHealthy)
    }

    @Test
    fun `nonsense inputs return null instead of a fabricated plan`() {
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 0.0, 0.5, 2.0))
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 2_600.0, 0.0, 2.0))
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 2_600.0, 70.0, 2.0))
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 2_600.0, 0.5, 0.0))
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 2_600.0, 0.5, 12.0))
        assertNull(CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", Double.NaN, 0.5, 2.0))
    }

    @Test
    fun `every symbol preset combination yields finite numbers`() {
        val symbols = listOf("XAU/USD", "EUR/USD", "BTC/USDT", "BNB/USDT", "US30", "ETH/USDT")
        val stops = listOf(0.25, 0.5, 1.0, 2.0)
        val targets = listOf(1.5, 2.0, 3.0)
        symbols.forEach { symbol ->
            val price = when (symbol) {
                "XAU/USD" -> 2_600.0
                "EUR/USD" -> 1.0850
                "BTC/USDT" -> 65_000.0
                "BNB/USDT" -> 700.0
                "US30" -> 42_000.0
                else -> 3_000.0
            }
            stops.forEach { stop ->
                targets.forEach { r ->
                    val trace = CapitalDebugTrace.simulate(goldPolicy, symbol, price, stop, r)
                    if (trace != null) {
                        val result = trace.result
                        assertTrue(
                            "NaN در $symbol/$stop٪/$r R",
                            listOf(
                                result.netLossAtSlUsd, result.netProfitAtTpUsd, result.riskReward,
                                result.grossRiskReward, result.positionUnits, result.takeProfit,
                            ).all { it.isFinite() },
                        )
                        assertTrue(
                            "ناوردای NaN باید سبز باشد ($symbol)",
                            check(trace, "NaN")?.passed != false,
                        )
                        assertTrue(
                            "زیان خالص هرگز از بودجه بیشتر نمی‌شود ($symbol/$stop٪)",
                            result.netLossAtSlUsd <= 5.02,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `clipboard text carries the whole story`() {
        val trace = CapitalDebugTrace.simulate(goldPolicy, "XAU/USD", 2_600.0, 0.5, 2.0)!!
        val text = trace.toTextFa()
        assertTrue(text.contains("دیباگ مدیریت سرمایه"))
        assertTrue(text.contains("مراحل محاسبه"))
        assertTrue(text.contains("ناورداها"))
        assertTrue(text.contains("زیان خالص"))
        assertTrue(text.contains("XAU/USD"))
        assertEquals(text, trace.toTextFa()) // پایدار و بدون تصادف
    }
}
