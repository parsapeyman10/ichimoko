package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence

/** Exact pre-alert checks. No alert is an entry, and none of these checks sends an order. */
object PaperAlertRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                trades: List<PaperTrade>, mtf: MtfAnalyzer.Snapshot?,
                now: Long = System.currentTimeMillis()): String? {
        if (!settings.notifyOnSignal) return "هشدار آموزشی خاموش است"
        PaperAutoRules.opportunityBlocker(market, settings, news, now)?.let { return it }
        val signal = market.signal ?: return "سیگنال در دسترس نیست"
        if (mtf == null || mtf.frames.isEmpty() || mtf.veto || mtf.barTime != signal.barTime || mtf.baseInterval != signal.interval)
            return "تراز چندتایم‌فریم برای همین کندل تأیید نشده است"
        if (trades.any { it.isOpen && it.symbol == market.symbol }) return "پوزیشن کاغذی این نماد باز است"
        if (trades.any { it.symbol == market.symbol && it.signalBarTime == signal.barTime })
            return "این کندل پیش‌تر در ژورنال معامله شده است"
        val draft = runCatching { PaperOrderRules.preview(signal.action, market.symbol,
            market.lastPrice ?: 0.0, signal.stopLoss ?: 0.0, signal.takeProfit ?: 0.0,
            settings.accountBalance, settings.riskPercent) }.getOrElse { return "ریسک برگهٔ کاغذی معتبر نیست" }
        val totalRisk = trades.filter { it.isOpen }.sumOf { it.riskPerOz * it.positionOz }
        if (totalRisk + draft.actualRiskUsd > settings.accountBalance * 0.05 + 1e-8)
            return "مجموع ریسک پوزیشن‌های کاغذی از سقف ۵٪ می‌گذرد"
        if (NewsConfluence.record(news) == null) return "شواهد خبر در دسترس نیست"
        return null
    }
}
