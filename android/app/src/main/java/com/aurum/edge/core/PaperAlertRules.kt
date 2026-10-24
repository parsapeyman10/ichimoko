package com.aurum.edge.core

import com.aurum.edge.engine.MtfAnalyzer

/** Exact pre-alert checks. No alert is an entry, and none of these checks sends an order. */
object PaperAlertRules {
    fun blocker(market: MarketState, settings: AppSettings,
                trades: List<PaperTrade>, mtf: MtfAnalyzer.Snapshot?,
                now: Long = System.currentTimeMillis(),
                allowedSymbols: List<String>? = null, barAgeGraceMs: Long = 90_000L): String? {
        if (!settings.notifyOnSignal) return "هشدار آموزشی خاموش است"
        PaperAutoRules.opportunityBlocker(market, settings, now, allowedSymbols, barAgeGraceMs)?.let { return it }
        val signal = market.signal ?: return "سیگنال در دسترس نیست"
        val isAls5Gold = V1Universe.normalize(market.symbol) == "XAU/USD"
        if (isAls5Gold && signal.als5Opportunity?.matches(signal) != true)
            return "فقط فرصت تأییدشدهٔ ALS-5 برای هشدار/ورود طلا مجاز است"
        val orderRiskPct = if (isAls5Gold) settings.riskPercent.coerceAtMost(0.25) else settings.riskPercent
        val draft = runCatching { PaperOrderRules.preview(signal.action, market.symbol,
            market.lastPrice ?: 0.0, signal.stopLoss ?: 0.0, signal.takeProfit ?: 0.0,
            settings.accountBalance, orderRiskPct) }.getOrElse { return "ریسک برگهٔ کاغذی معتبر نیست" }
        if (isAls5Gold) {
            var losses = 0
            val goldClosed = trades.filter { it.symbol == market.symbol && !it.isOpen &&
                (it.closedAt ?: Long.MAX_VALUE) <= now }.sortedByDescending { it.closedAt ?: 0L }
            for (closedTrade in goldClosed) {
                val pnl = closedTrade.pnlUsd?.takeIf { it.isFinite() }
                    ?: return "سابقهٔ PnL طلای بسته‌شده ناقص است؛ قفل ALS-5 بازنشانی نمی‌شود"
                if (pnl >= 0.0) break
                losses++
            }
            if (losses >= 3) return "سقف ۳ ضرر متوالی طلا فعال است"
        }
        PaperPortfolioPolicy.blocker(trades, market.symbol, settings.accountBalance,
            draft.actualRiskUsd + draft.commissionUsd + draft.spreadCostUsd, signal.barTime,
            maxRiskPerTradePct = if (isAls5Gold) 0.0025 else PaperPortfolioPolicy.MAX_RISK_PER_TRADE,
            maxDailyLossPct = if (isAls5Gold) 0.01 else PaperPortfolioPolicy.MAX_DAILY_LOSS,
            als5SetupKey = signal.als5Opportunity?.setupKey?.takeIf { isAls5Gold })?.let { return it }
        // Only technical evidence and risk caps gate a paper alert.
        return null
    }
}
