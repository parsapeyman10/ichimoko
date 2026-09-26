package com.aurum.edge.data

import kotlin.math.abs
import kotlin.math.max

/** Explanations of *observed* publisher data; never NewsGate, AI evidence or a trade direction. */
enum class ResearchState { SCHEDULED, AWAITING_RESULT, PUBLISHED, CONTEXT, UNKNOWN }
data class ResearchNote(val state: ResearchState, val title: String, val detail: String)
enum class ResearchSpace { FOREX, CRYPTO, NOBITEX, IRAN_STOCKS }

object NewsResearch {
    private val number = Regex("^([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)\\s*(%|[kmb]|bp)?$", RegexOption.IGNORE_CASE)
    private data class Metric(val amount: Double, val kind: String)

    private fun metric(raw: String?): Metric? {
        val match = number.matchEntire(raw?.trim().orEmpty()) ?: return null
        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (!amount.isFinite() || abs(amount) > 1e15) return null
        val suffix = match.groupValues[2].lowercase()
        val scale = when (suffix) { "k" -> 1_000.0; "m" -> 1_000_000.0; "b" -> 1_000_000_000.0; else -> 1.0 }
        val scaled = amount * scale
        if (!scaled.isFinite() || abs(scaled) > 1e15) return null
        return Metric(scaled, if (suffix in setOf("k", "m", "b")) "count" else suffix)
    }

    /** An FF calendar event is not an FF article. No result is claimed before its time or
     * if the week's download is missing/stale. Differences never become a gold BUY/SELL.
     */
    fun gold(event: ForexEvent, calendar: ForexCalendarState, now: Long): ResearchNote {
        if (event.country != "USD" || !calendar.online(now) || event !in calendar.events) {
            return ResearchNote(ResearchState.UNKNOWN, "وضعیت خبر نامشخص",
                "تقویم Forex Factory برای رویداد USD فعلاً معتبر نیست؛ نتیجه یا جهت طلا استنتاج نمی‌شود.")
        }
        val risk = if (event.impact == "High") "رویداد پراثر: نوسان/اسپرد ممکن است زیاد شود. " else ""
        if (now < event.at) return ResearchNote(ResearchState.SCHEDULED, "رویداد برنامه‌ریزی‌شده، نه خبر منتشرشده",
            "$risk${event.forecast?.let { "پیش‌بینی $it؛ " } ?: ""}نتیجه هنوز منتشر نشده؛ جهت طلا معلوم نیست.")
        if (calendar.checkedAt?.let { it >= event.at } != true || event.actual.isNullOrBlank()) {
            return ResearchNote(ResearchState.AWAITING_RESULT, "زمان رویداد گذشته؛ نتیجه در این خروجی نیست",
                "${risk}Forecast و Previous نتیجهٔ واقعی نیستند. سایت ناشر/واکنش قیمت را بررسی کنید؛ هیچ جهت یا تأییدی نداریم.")
        }
        val actual = metric(event.actual)
        val forecast = metric(event.forecast)
        val comparison = when {
            actual == null -> "نتیجهٔ منتشرشده متن/واحد غیرعددی دارد؛ مقایسهٔ عددی ممکن نیست. "
            forecast == null || actual.kind != forecast.kind -> "مبنای پیش‌بینی هم‌واحد/عددی در خروجی نیست؛ غافلگیری قابل محاسبه نیست. "
            abs(actual.amount - forecast.amount) <= max(1.0, abs(forecast.amount)) * 1e-8 ->
                "نتیجهٔ عددی برابر پیش‌بینی ثبت شده است؛ اثر بازار از این برابری معلوم نیست. "
            actual.amount > forecast.amount -> "نتیجهٔ عددی بالاتر از پیش‌بینی است؛ «بالاتر» لزوماً به‌معنی رشد طلا نیست. "
            else -> "نتیجهٔ عددی پایین‌تر از پیش‌بینی است؛ «پایین‌تر» لزوماً به‌معنی افت طلا نیست. "
        }
        return ResearchNote(ResearchState.PUBLISHED, "مقایسهٔ محتاطانهٔ نتیجهٔ منتشرشده",
            "$risk${comparison}برای اثر بر XAU/USD واکنش هم‌زمان دلار، بازده اوراق و قیمت واقعی لازم است؛ این خروجی فقط تقویم است، نه تحلیل مدل/سیگنال.")
    }

    /** A headline alone proves neither its full article nor the market's reaction. Explicit
     * feed-category and receipt checks prevent using a cached headline as breaking news.
     */
    fun headline(item: PublicHeadline, state: PublicWebNewsState, space: ResearchSpace, now: Long): ResearchNote {
        val categoryAllowed = when (space) {
            ResearchSpace.FOREX -> item.feed.category in setOf(PublicNewsCategory.MARKETS, PublicNewsCategory.ECONOMY)
            ResearchSpace.CRYPTO, ResearchSpace.NOBITEX -> item.feed.category == PublicNewsCategory.CRYPTO
            ResearchSpace.IRAN_STOCKS -> item.feed.category == PublicNewsCategory.IRAN
        }
        val health = state.feeds.singleOrNull { it.feed.id == item.feed.id }
        val receiptRecent = health?.checkedAt?.let { item.receivedAt in (it - 30_000L)..(it + 30_000L) } == true
        if (!categoryAllowed || state.loading || health?.online(now) != true || !receiptRecent ||
            now - item.publishedAt !in 0L..(24 * 3_600_000L)) {
            return ResearchNote(ResearchState.UNKNOWN, "تیتر قبلی/نامرتبط، نه خبر تازه",
                "تازگی خوراک/انتشار یا تعلق آن به این فضا تأیید نیست؛ از تیتر نتیجهٔ بازار استخراج نمی‌شود.")
        }
        val text = item.title.lowercase()
        val theme = when (space) {
            ResearchSpace.FOREX -> when {
                listOf("fed", "fomc", "rate", "yield", "cpi", "pce", "payroll", "inflation", "gold", "xau", "usd")
                    .any(text::contains) -> "عنوان به سیاست پولی، دادهٔ اقتصاد آمریکا یا طلا اشاره می‌کند؛ تقویم Forex Factory/عدد رسمی را جدا بررسی کنید. "
                else -> "ارتباط مستقیم عنوان با XAU/USD تأیید نشده است. "
            }
            ResearchSpace.CRYPTO, ResearchSpace.NOBITEX -> when {
                listOf("hack", "exploit", "breach", "attack", "security").any(text::contains) ->
                    "عنوان به موضوع امنیت اشاره دارد؛ دامنه/زیان یا اثر روی جفت معاملاتی هنوز تأیید نشده است. "
                listOf("etf", "regulation", "sec ", "regulator").any(text::contains) ->
                    "عنوان به صندوق یا مقررات اشاره دارد؛ اثر قیمت و قوانین بازار محلی هنوز تأیید نشده است. "
                else -> "دامنهٔ دارایی و تأثیر قیمت از این تیتر به‌تنهایی معلوم نیست. "
            }
            ResearchSpace.IRAN_STOCKS -> "این تیتر اقتصاد عمومی است؛ گزارش رسمی نماد/صورت مالی کدال و تاریخ مستقل تابلو از آن تأیید نمی‌شود. "
        }
        val exchange = if (space == ResearchSpace.NOBITEX)
            "CoinDesk اطلاعیهٔ رسمی نوبیتکس یا تأیید بازار ریالی/USDT نیست. " else ""
        return ResearchNote(ResearchState.CONTEXT, "برداشت محدود از تیتر ناشر",
            "$theme${exchange}متن کامل ناشر و واکنش قیمت/حجم را جدا بررسی کنید؛ نه AI، نه پیش‌بینی و نه مجوز معامله.")
    }
}
