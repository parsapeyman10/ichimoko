package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.engine.MtfAnalyzer

/** Exact pre-alert checks. No alert is an entry, and none of these checks sends an order. */
object PaperAlertRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                trades: List<PaperTrade>, mtf: MtfAnalyzer.Snapshot?,
                now: Long = System.currentTimeMillis(),
                allowedSymbols: List<String>? = null, barAgeGraceMs: Long = 90_000L): String? {
        if (!settings.notifyOnSignal) return "هشدار آموزشی خاموش است"
        PaperAutoRules.opportunityBlocker(market, settings, news, now, allowedSymbols, barAgeGraceMs)?.let { return it }
        val signal = market.signal ?: return "سیگنال در دسترس نیست"
        val draft = runCatching { PaperOrderRules.preview(signal.action, market.symbol,
            market.lastPrice ?: 0.0, signal.stopLoss ?: 0.0, signal.takeProfit ?: 0.0,
            settings.accountBalance, settings.riskPercent) }.getOrElse { return "ریسک برگهٔ کاغذی معتبر نیست" }
        PaperPortfolioPolicy.blocker(trades, market.symbol, settings.accountBalance,
            draft.actualRiskUsd + draft.commissionUsd + draft.spreadCostUsd, signal.barTime)?.let { return it }
        // News never blocks a paper alert/entry; it is attached later as journal-mining evidence.
        return null
    }
}
