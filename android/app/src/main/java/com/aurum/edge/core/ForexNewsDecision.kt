package com.aurum.edge.core

import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.engine.NewsConfluence

/** Compact readout. A calendar item is NOT a full Forex Factory article or a model verdict. */
data class ForexNewsDecision(
    val context: String,
    val modelOpinion: String,
    val direction: String,
    val paperEntry: String,
    val canEnterPaper: Boolean,
)

object ForexNewsDecisions {
    fun assess(market: MarketState, settings: AppSettings, calendar: ForexCalendarState,
               news: PersianNewsState, now: Long = System.currentTimeMillis()): ForexNewsDecision {
        val closed = MarketHours.forexWeekendClosed(now)
        val highlight = calendar.events.filter { it.country == "USD" && it.impact == "High" &&
            it.at in (now - 6 * 3_600_000L)..(now + 7 * 86_400_000L) }
            .minByOrNull { kotlin.math.abs(it.at - now) }
        val context = if (!calendar.online(now)) "تقویم Forex Factory تازه نیست؛ اثر خبر نامعلوم" else
            highlight?.let { "${it.title}: ${NewsResearch.gold(it, calendar, now).title}" }
                ?: "در تقویم دریافتی، رویداد High/USD نزدیک نیست؛ نبود خبر ثابت نشده"
        // News is journal context, not a hidden replacement or blocker for the eight technical rules.
        val verdict = news.aiBySymbol[market.symbol] ?: news.ai.takeIf { it.symbol == market.symbol }
        val action = when (verdict?.direction) { "BUY" -> SignalAction.BUY; "SELL" -> SignalAction.SELL; else -> null }
        val aligned = if (!closed && action != null) {
            NewsConfluence.alignment(market.symbol, action, news, now).status == ConfluenceStatus.CONFIRMED
        } else false
        val blocker = if (closed) "بازار فارکس طبق برنامهٔ معمول بسته است" else
            PaperAutoRules.blocker(market, settings, news, now)
        val allowed = blocker == null
        val hardVeto = news.gate.name == "BLOCKED" || market.symbol in news.vetoedSymbols
        return ForexNewsDecision(
            context,
            modelOpinion = if (aligned) "مدل ${verdict?.model} با شاهد معتبر هم‌جهت شد؛ این فقط زمینهٔ ژورنال است و تضمین حرکت قیمت نیست"
                else if (hardVeto) "خبر/تقویم پرریسک دیده شده؛ دلیل در تب خبر آمده اما معاملهٔ کاغذی را شرطی/مسدود نمی‌کند"
                else "AI خبر معیار سبز کامل ندارد؛ این وضعیت فقط زرد است و شرط ورود کاغذی نیست",
            direction = if (aligned) { if (action == SignalAction.BUY) "LONG / خرید" else "SHORT / فروش" }
                else "نامعلوم؛ جهت از تیتر/پیش‌بینی ساخته نمی‌شود و جهت اصلی از ۸ شرط فنی می‌آید",
            paperEntry = if (allowed) "ورود خودکار کاغذی وابسته به ۸ شرط فنی، آپشن‌های فعال، قیمت زنده، ICT/MTF و ریسک است؛ خبر فقط در ژورنال تحلیل می‌شود"
                else "ورود خودکار کاغذی: خیر · $blocker",
            canEnterPaper = allowed,
        )
    }
}
