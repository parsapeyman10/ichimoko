package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * افت سرمایه باید نسبت به **سرمایهٔ حساب** سنجیده شود، نه نسبت به سود انباشته.
 *
 * این تست از یک عدد واقعی متولد شد: روی دادهٔ واقعی `BTC/USD` (۲۰۲۵-۰۱-۰۱ تا ۲۰۲۶-۱۱-۰۹،
 * ۶۴٬۳۶۳ کندل ۱۵دقیقه) بک‌تست گزارش داد «حداکثر افت سرمایه = ۵۲۷٪» در حالی که مجموع
 * معامله‌ها فقط −۲۴٫۸R بود (با ریسک ۰٫۵٪ یعنی حدود −۱۲٪ حساب). علت: منحنی از صفر شروع
 * می‌شد و مخرج کسر «بیشترین سود انباشته» بود؛ با سود انباشتهٔ کوچک، افت نسبی منفجر می‌شد.
 * حالت بدتر: منحنی‌ای که فقط ضرر می‌دهد هرگز سقف تازه نمی‌سازد و «افت ۰٪» گزارش می‌شد.
 */
class BacktestDrawdownTest {

    private fun trade(netUsd: Double, exitAt: Long) = BacktestReplayEngine.Trade(
        index = 0,
        strategyId = "base",
        strategyTitleFa = "بیس",
        action = SignalAction.BUY,
        entryTime = 1L,
        entryPrice = 100.0,
        stopLoss = 99.0,
        takeProfit = 102.0,
        confidence = 90.0,
        plannedRiskReward = 2.0,
        exitTime = exitAt,
        exitPrice = 100.0,
        exitReason = BacktestReplayEngine.ExitReason.TAKE_PROFIT,
        grossPnlUsd = netUsd,
        feeUsd = 0.0,
        netPnlUsd = netUsd,
        rMultiple = netUsd / 10.0,
        barsHeld = 1,
        equityAfterUsd = 0.0,
    )

    @Test
    fun `curve that only loses reports real drawdown not zero`() {
        val trades = listOf(trade(-100.0, 1L), trade(-100.0, 2L), trade(-100.0, 3L))
        val stats = BacktestReplayEngine.buildStats("base", "بیس", trades, startEquityUsd = 1_000.0)

        // ۱۰۰۰ → ۹۰۰ → ۸۰۰ → ۷۰۰ : افت ۳۰۰ دلار از سقف ۱۰۰۰ = ۳۰٪
        assertEquals(300.0, stats.maxDrawdownUsd, 0.01)
        assertEquals(30.0, stats.maxDrawdownPct, 0.01)
    }

    @Test
    fun `drawdown is measured from equity peak including starting balance`() {
        val trades = listOf(trade(100.0, 1L), trade(-250.0, 2L), trade(-250.0, 3L))
        val stats = BacktestReplayEngine.buildStats("base", "بیس", trades, startEquityUsd = 1_000.0)

        // ۱۰۰۰ → ۱۱۰۰ (سقف) → ۸۵۰ → ۶۰۰ : افت ۵۰۰ از ۱۱۰۰ = ۴۵٫۴۵٪
        assertEquals(500.0, stats.maxDrawdownUsd, 0.01)
        assertEquals(45.45, stats.maxDrawdownPct, 0.02)
    }

    @Test
    fun `small peak profit cannot explode drawdown percentage`() {
        // همان الگویی که «۵۲۷٪» می‌ساخت: سود انباشتهٔ کوچک و بعد ضرر بزرگ‌تر.
        val trades = listOf(trade(5.0, 1L), trade(-120.0, 2L))
        val stats = BacktestReplayEngine.buildStats("base", "بیس", trades, startEquityUsd = 1_000.0)

        // ۱۰۰۰ → ۱۰۰۵ (سقف) → ۸۸۵ : افت ۱۲۰ از ۱۰۰۵ = ۱۱٫۹۴٪  (قبلاً: ۲۴۰۰٪)
        assertEquals(120.0, stats.maxDrawdownUsd, 0.01)
        assertEquals(11.94, stats.maxDrawdownPct, 0.02)
    }

    @Test
    fun `no closed trades means no drawdown`() {
        val stats = BacktestReplayEngine.buildStats("base", "بیس", emptyList(), startEquityUsd = 1_000.0)
        assertEquals(0.0, stats.maxDrawdownUsd, 1e-9)
        assertEquals(0.0, stats.maxDrawdownPct, 1e-9)
        assertEquals(0, stats.trades)
    }
}
