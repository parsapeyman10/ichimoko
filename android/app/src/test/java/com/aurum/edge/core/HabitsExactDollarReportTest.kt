package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * گزارش لایهٔ «عادت‌های معاملاتی» باید همان **دلاری** را بگوید که معامله می‌کند.
 *
 * قبلاً پولِ گزارش از درصد موجودی بازمحاسبه می‌شد؛ روی حساب کوچک عددی مثل `$0.60` در
 * یادداشت معامله دیده می‌شد، در حالی که ریسک واقعی `$5.00` بود — کاربر همان `$0.60` را
 * «حد ضرر» می‌خواند.
 */
class HabitsExactDollarReportTest {

    @Test
    fun `habits report repeats the traded dollars even on a small account`() {
        val equity = 120.0
        val riskUsd = 5.0
        val decision = TradingHabitsPolicy.evaluate(
            TradingHabitsPolicy.EntryRequest(
                symbol = "XAU/USD",
                action = SignalAction.BUY,
                entry = 2_600.0,
                stopLoss = 2_592.2,
                takeProfit = 2_615.6,
                atr = 4.0,
                equityUsd = equity,
                peakEquityUsd = equity,
                requestedRiskPct = riskUsd / equity * 100.0,
                riskUsdOverride = riskUsd,
                setupScorePct = 90.0,
                hasQuantifiedSignal = true,
                limits = TradingHabitsPolicy.Limits(enabled = true),
            ),
        )
        assertEquals("گزارش باید ۵٫۰۰ دلار بگوید، نه ۰٫۶۰", riskUsd, decision.riskUsd, 1e-9)
        assertEquals(
            "متن خلاصه هم باید دلار واقعی را داشته باشد",
            true,
            decision.summaryFa.contains("5.00"),
        )
    }

    @Test
    fun `without an override the percent fallback still works for other callers`() {
        val decision = TradingHabitsPolicy.evaluate(
            TradingHabitsPolicy.EntryRequest(
                symbol = "XAU/USD",
                action = SignalAction.BUY,
                entry = 2_600.0,
                stopLoss = 2_592.2,
                takeProfit = 2_615.6,
                atr = 4.0,
                equityUsd = 1_000.0,
                requestedRiskPct = 1.0,
                setupScorePct = 90.0,
                limits = TradingHabitsPolicy.Limits(enabled = true),
            ),
        )
        assertEquals(10.0, decision.riskUsd, 1e-9)
    }
}
