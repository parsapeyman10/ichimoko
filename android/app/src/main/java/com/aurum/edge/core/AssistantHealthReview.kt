package com.aurum.edge.core

/** Evidence-only operational warnings, never treated as a trading signal or automatic entry veto. */
object AssistantHealthReview {
    fun inspect(trades: List<PaperTrade>, ticks: Map<String, PriceTick>, now: Long): List<String> {
        val open = trades.filter { it.isOpen && !it.isBacktest }
        val out = mutableListOf<String>()
        val stale = open.filter { t ->
            val tick = ticks[V1Universe.normalize(t.symbol)]
            tick == null || !TradeQuotePolicy.accepts(tick, now)
        }.map { it.symbol }.distinct()
        if (stale.isNotEmpty()) out += "قیمت تازهٔ معاملاتی موجود نیست: ${stale.joinToString("، ")}؛ قفل هنگام نبود تیک تضمین نمی‌شود."
        val badStop = open.filter { !it.stopLoss.isFinite() || it.stopLoss <= 0 || !it.entry.isFinite() || it.entry <= 0 }
        if (badStop.isNotEmpty()) out += "${badStop.size} معامله با قیمت ورود یا حد فعال نامعتبر نیاز به بررسی دارد."
        val badRisk = open.filter { !it.fixedRiskUsd.isFinite() || it.fixedRiskUsd <= 0 }
        if (badRisk.isNotEmpty()) out += "ریسک دلاری ${badRisk.size} معامله نامعتبر یا نامعلوم است؛ R و قفل ۱٫۵R قابل اتکا نیست."
        return out
    }
}
