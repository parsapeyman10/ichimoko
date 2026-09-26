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
        val action = when (news.ai.direction) { "BUY" -> SignalAction.BUY; "SELL" -> SignalAction.SELL; else -> null }
        val aligned = if (!closed && action != null) {
            NewsConfluence.alignment(market.symbol, action, news, now).status == ConfluenceStatus.CONFIRMED
        } else false
        val blocker = if (closed) "بازار فارکس طبق برنامهٔ معمول بسته است" else
            PaperAutoRules.blocker(market, settings, news, now)
        val allowed = blocker == null && aligned
        return ForexNewsDecision(
            context,
            modelOpinion = if (aligned) "مدل ${news.ai.model} با شاهد معتبر بررسی شد؛ این برداشت تضمین حرکت قیمت نیست"
                else "نظر AI معتبر نداریم؛ خروجی تقویم به‌تنهایی تحلیل معامله‌گر نیست",
            direction = if (aligned) { if (action == SignalAction.BUY) "LONG / خرید" else "SHORT / فروش" }
                else "نامعلوم؛ جهت از تیتر/پیش‌بینی ساخته نمی‌شود",
            paperEntry = if (allowed) "شرایط لحظه‌ای ورود کاغذی هم‌جهت است؛ فقط پس از ثبت موفق ژورنال، معامله محسوب می‌شود"
                else "ورود خودکار کاغذی: خیر · ${blocker ?: "شواهد مدل/جهت تأیید نشده"}",
            canEnterPaper = allowed,
        )
    }
}
