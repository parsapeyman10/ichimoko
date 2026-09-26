package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlin.math.abs

/** Publisher importance, numerical surprise, a CONDITIONAL macro scenario and observed bars
 * are four different things. Nothing here is fed to NewsConfluence or the paper order engine.
 */
data class GoldCalendarInsight(
    val importance: String,
    val relevance: String,
    val surprise: String,
    val scenario: String,
    val observed: ObservedGoldMove?,
    val observationNote: String,
)

data class ObservedGoldMove(
    val beforeCloseAt: Long,
    val afterCloseAt: Long,
    val before: Double,
    val after: Double,
    val percent: Double,
    val cached: Boolean,
)

object NewsImpactResearch {
    private val inflation = Regex("^(?:core )?(?:cpi|pce price index)(?: [my]/[my])?$", RegexOption.IGNORE_CASE)
    private val jobs = Regex("^non[- ]farm (?:employment change|payrolls)$", RegexOption.IGNORE_CASE)
    private val unemployment = Regex("^unemployment rate$", RegexOption.IGNORE_CASE)
    private const val MINUTE = 60_000L

    fun gold(event: ForexEvent, calendar: ForexCalendarState, market: MarketState, now: Long): GoldCalendarInsight {
        val importance = when (event.impact) {
            "High" -> "زیاد (High)"
            "Medium" -> "متوسط (Medium)"
            "Low" -> "کم (Low)"
            "Holiday" -> "تعطیلی (Holiday؛ نقدشوندگی را جدا بررسی کنید)"
            "Non-Economic" -> "غیراقتصادی (Non-Economic)"
            else -> "نامشخص"
        }
        val valid = calendar.online(now) && event in calendar.events
        val relevant = valid && event.country == "USD" && event.impact in setOf("High", "Medium", "Low")
        val relevance = when {
            !valid -> "تقویم تازه/معتبر نیست؛ درجهٔ ثبت‌شده تأیید جاری ندارد."
            event.country != "USD" -> "درجه برای ${event.country} است؛ ارتباط مستقیم با XAU/USD از تقویم ثابت نیست."
            !relevant -> "برچسب تعطیلی/غیراقتصادی، رتبهٔ غافلگیری یا پیش‌بینی قیمت طلا نیست."
            else -> "رویداد USD ممکن است بر طلا اثر بگذارد؛ این درجه انتظار ناشر است، نه شدت حرکت ثبت‌شده."
        }
        val note = if (relevant) NewsResearch.gold(event, calendar, now) else null
        val surprise = when (note?.state) {
            ResearchState.SCHEDULED -> "هنوز Actual منتشر نشده؛ Forecast فقط انتظار است."
            ResearchState.AWAITING_RESULT -> "زمان گذشت اما Actual در خروجی نیست؛ نتیجه معلوم نیست."
            ResearchState.PUBLISHED -> when (NewsResearch.surprise(event, calendar, now)) {
                NumericSurprise.ABOVE_FORECAST -> "Actual بالاتر از Forecast، با واحد یکسان."
                NumericSurprise.BELOW_FORECAST -> "Actual پایین‌تر از Forecast، با واحد یکسان."
                NumericSurprise.AS_FORECAST -> "Actual برابر Forecast، با واحد یکسان."
                null -> "غافلگیری عددی نامعتبر: مقدار یا واحد هم‌خوان نیست."
            }
            else -> "نتیجهٔ قابل‌مقایسه برای XAU/USD نداریم."
        }
        val scenario = if (!relevant) "جهت احتمالی طلا از این ردیف معلوم نیست." else
            macroScenario(event.title, note?.state, NewsResearch.surprise(event, calendar, now))
        val move = if (relevant) observedGold(event, calendar, market, now) else null
        val observationNote = when {
            !relevant -> "تغییر قیمت این رویداد برای XAU/USD سنجیده نشده است."
            note?.state != ResearchState.PUBLISHED -> "بدون Actual تأییدشده، حرکت قیمت به نتیجهٔ این رویداد نسبت داده نمی‌شود."
            move == null -> "تغییر پیش/پس در دسترس نیست: نتیجه/پیش‌بینی هم‌واحد، زمان معتبر و کندل‌های بستهٔ پیوستهٔ M1/M5 طلا لازم‌اند."
            else -> "فقط تغییر کندل‌های واقعیِ پیش/پس رویداد؛ هم‌زمانی، علیت یا واکنش دلار/بازده را ثابت نمی‌کند."
        }
        return GoldCalendarInsight(importance, relevance, surprise, scenario, move, observationNote)
    }

    private fun macroScenario(title: String, state: ResearchState?, surprise: NumericSurprise?): String {
        val indicator = when {
            inflation.matches(title.trim()) -> "تورم"
            jobs.matches(title.trim()) -> "اشتغال"
            unemployment.matches(title.trim()) -> "بیکاری"
            else -> return "برای این شاخص نگاشت مطمئن Actual به جهت طلا نداریم؛ متن رسمی و بازار را بررسی کنید."
        }
        if (state == ResearchState.SCHEDULED) return "سناریو فقط پیش از انتشار: دادهٔ قوی‌تر/ضعیف‌تر $indicator شاید از مسیر دلار و بازده بر طلا اثر بگذارد؛ جهت واقعی هنوز نامعلوم است."
        if (state != ResearchState.PUBLISHED || surprise == null) return "جهت نامعلوم؛ تا نتیجهٔ عددی هم‌واحد و واکنش بازار نیاید، سناریوی جهت‌دار نداریم."
        if (surprise == NumericSurprise.AS_FORECAST) return "غافلگیری عددی صفر؛ از برابری Actual/Forecast جهت طلا نتیجه نمی‌شود."
        // A higher unemployment rate is typically weaker employment; do not confuse it with higher payrolls.
        val dollarCouldStrengthen = if (indicator == "بیکاری") surprise == NumericSurprise.BELOW_FORECAST
            else surprise == NumericSurprise.ABOVE_FORECAST
        return if (dollarCouldStrengthen)
            "سناریوی مشروط: اگر دلار/بازده آمریکا تقویت شود، فشار کاهشی بر XAU/USD ممکن است؛ واکنش معکوس هم ممکن است، نه دستور فروش."
        else
            "سناریوی مشروط: اگر دلار/بازده آمریکا تضعیف شود، رشد XAU/USD ممکن است؛ واکنش معکوس هم ممکن است، نه دستور خرید."
    }

    /** No ticker quote, daily % change or open bar can stand in for a reaction to a release.
     * Compare closed provider/cache bars ~5-10 min BEFORE vs ~10-20 min AFTER the listed time.
     * Demand the complete intervening series; missing data means NO measured move.
     */
    fun observedGold(event: ForexEvent, calendar: ForexCalendarState, market: MarketState, now: Long): ObservedGoldMove? {
        if (market.symbol != "XAU/USD" || market.interval !in setOf(Interval.M1, Interval.M5) ||
            event.country != "USD" || event.impact !in setOf("High", "Medium", "Low") ||
            !calendar.online(now) || event !in calendar.events ||
            calendar.checkedAt?.let { it >= event.at } != true ||
            NewsResearch.surprise(event, calendar, now) == null ||
            now - event.at !in (20 * MINUTE)..(6 * 60 * MINUTE)) return null
        val interval = market.interval.millis
        fun closeAt(bar: Candle) = bar.time + interval
        val pre = market.candles.filter { it.closed && closeAt(it) in (event.at - 10 * MINUTE)..(event.at - 5 * MINUTE) &&
            closeAt(it) <= now }.maxByOrNull { it.time } ?: return null
        val post = market.candles.filter { it.closed && closeAt(it) in (event.at + 10 * MINUTE)..(event.at + 20 * MINUTE) &&
            closeAt(it) <= now }.minByOrNull { abs(closeAt(it) - (event.at + 15 * MINUTE)) } ?: return null
        val between = market.candles.filter { it.time in pre.time..post.time }
        val distance = post.time - pre.time
        if (distance < interval || distance % interval != 0L ||
            between.size.toLong() != distance / interval + 1 ||
            between.any { !it.closed || it.time < 0 || it.time + interval > now ||
                !it.close.isFinite() || it.close <= 0.0 } ||
            between.map { it.time }.distinct().size != between.size) return null
        val pct = (post.close / pre.close - 1.0) * 100.0
        if (!pct.isFinite() || abs(pct) > 20.0) return null // reject implausible/mismapped prices
        return ObservedGoldMove(closeAt(pre), closeAt(post), pre.close, post.close, pct, market.showingCachedData)
    }
}
