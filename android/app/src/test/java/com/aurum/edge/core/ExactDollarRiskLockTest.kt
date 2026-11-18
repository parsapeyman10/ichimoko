package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * قانون ثابت کاربر: زیان هر معامله **دقیقاً** مبلغ تعیین‌شده است (پیش‌فرض `$5.00`).
 *
 * چرا این تست لازم شد: روی حساب کوچک (مثلاً `$120`) حالت «درصد موجودی» با ۰٫۵٪ مبلغ
 * `$0.60` می‌سازد و همان عدد روی معامله و حد ضرر دیده می‌شد — درست همان شکایت کاربر که
 * «حد ضرر ۰٫۶ شده به‌جای ۵ دلار». حالا درصد موجودی هرگز مبلغ ثابت را کوچک نمی‌کند.
 */
class ExactDollarRiskLockTest {

    @Test
    fun `percent basis can never shrink the configured dollar risk`() {
        val policy = CapitalPolicy(
            accountBalance = 120.0,
            riskPercent = 0.5,
            useFixedDollarRisk = false,
            fixedRiskUsd = 5.0,
        )
        assertEquals("مبلغ ریسک باید همان $5 بماند", 5.0, policy.riskBudgetUsd, 1e-9)
    }

    @Test
    fun `percent is only a fallback when no dollar amount is configured`() {
        val policy = CapitalPolicy(
            accountBalance = 120.0,
            riskPercent = 0.5,
            useFixedDollarRisk = false,
            fixedRiskUsd = 0.0,
        )
        assertEquals(0.6, policy.riskBudgetUsd, 1e-9)
    }

    @Test
    fun `every entry path hands the configured amount to the engine`() {
        assertEquals(5.0, RiskAndCalendarEngine.fixedRiskUsdOrNull(5.0))
        assertEquals(12.5, RiskAndCalendarEngine.fixedRiskUsdOrNull(12.5))
        assertNull(RiskAndCalendarEngine.fixedRiskUsdOrNull(0.0))
        assertNull(RiskAndCalendarEngine.fixedRiskUsdOrNull(-3.0))
        assertNull(RiskAndCalendarEngine.fixedRiskUsdOrNull(Double.NaN))
    }

    @Test
    fun `a five dollar plan on a small account still loses exactly five dollars`() {
        // حساب $120 با درصد ۰٫۵٪: اگر درصد ملاک بود، زیان $0.60 می‌شد.
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 120.0,
            riskPercent = 0.5,
            entry = 2_600.0,
            stopLoss = 2_592.2,
            takeProfit = 2_615.6,
            preferredAction = SignalAction.BUY,
            fixedRiskUsdOverride = RiskAndCalendarEngine.fixedRiskUsdOrNull(5.0),
        )
        assertNotNull("محاسبهٔ پوزیشن باید نتیجه بدهد", calc)
        calc!!
        assertEquals("مبلغ ریسک", 5.0, calc.riskBudgetUsd, 0.01)
        assertEquals("زیان خالص در حد ضرر", 5.0, calc.netLossAtSlUsd, 0.01)
        assertEquals("سود خالص در ۲R", 10.0, calc.netProfitAtTpUsd, 0.05)
        assertEquals("R خالص", 2.0, calc.riskReward, 0.01)
    }
}
