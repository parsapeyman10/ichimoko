package com.aurum.edge.data

/** A research/verification priority is NOT Forex Factory's impact rating or a trade signal.
 * Direct RSS feeds carry headlines, not verified full articles, event-aligned OHLC or AI evidence.
 */
data class HeadlineInsight(
    val note: ResearchNote,
    val priority: String,
    val relevance: String,
    val possibleDirection: String,
    val observedEffect: String,
)

object HeadlineImpactResearch {
    private val macro = Regex("\\b(fed|fomc|cpi|pce|payrolls|inflation|yield|gold|xau|usd)\\b", RegexOption.IGNORE_CASE)
    private val bitcoin = Regex("\\b(bitcoin|btc)\\b", RegexOption.IGNORE_CASE)
    private val ethereum = Regex("\\b(ethereum|ether|eth)\\b", RegexOption.IGNORE_CASE)
    private val cryptoRisk = Regex("\\b(hack|exploit|breach|etf|sec|regulation|regulator)\\b", RegexOption.IGNORE_CASE)
    private val iranFiling = Regex("کدال|افشای اطلاعات|صورت.?مالی|سود هر سهم|مجمع")

    fun assess(item: PublicHeadline, state: PublicWebNewsState, space: ResearchSpace, now: Long): HeadlineInsight {
        val note = NewsResearch.headline(item, state, space, now)
        val noObserved = when (space) {
            ResearchSpace.FOREX -> "تیتر RSS به زمان کندل یک رویداد متصل نیست؛ فقط برای ردیف تقویم USD با کندل پیوسته، تغییر پیش/پس طلا سنجیده می‌شود."
            ResearchSpace.CRYPTO -> "قیمت/تغییر ۲۴ساعته، واکنش به این تیتر را ثابت نمی‌کند؛ کندل پیش/پسِ رویداد اینجا نداریم."
            ResearchSpace.NOBITEX -> "برای جفت ریالی یا USDT کندل رویداد و تأیید صرافی نداریم؛ تغییر ۲۴ساعته اثر این تیتر نیست."
            ResearchSpace.IRAN_STOCKS -> "تابلوی لحظه‌ای و TTM بدون تاریخچهٔ هم‌زمانِ همان نماد، اثر این تیتر را ثابت نمی‌کنند."
        }
        if (note.state != ResearchState.CONTEXT) return HeadlineInsight(note,
            "اولویت نامعلوم (کش/منبع نامعتبر)", "منبع، تاریخ یا تعلق تیتر به این فضا تازه تأیید نشد.",
            "جهت احتمالی نامعلوم؛ از خبر کهنه/نامرتبط نتیجه نگیرید.", noObserved)

        val title = item.title
        return when (space) {
            ResearchSpace.FOREX -> {
                val official = item.feed.id in setOf("bls_cpi", "bls_jobs")
                val related = macro.containsMatchIn(title)
                HeadlineInsight(note,
                    if (official) "اولویت بررسی منبع اولیهٔ BLS" else if (related) "اولویت بررسی موضوعی" else "اولویت نامعلوم",
                    if (official) "خوراک رسمی BLS؛ اصل گزارش و عدد را بخوانید. این درجه‌بندی Forex Factory نیست." else
                        if (related) "واژهٔ کلیدی اقتصاد آمریکا/طلا در عنوان ناشر؛ درجهٔ FF یا اثر روی XAU/USD نیست." else
                            "عنوان رابطهٔ مستقیمی با XAU/USD ثابت نمی‌کند؛ درجهٔ FF ندارد.",
                    if (related) "اگر دلار/بازده تغییر کند، فشار بر طلا ممکن است؛ جهت واقعی از این تیتر معلوم نیست."
                        else "جهت طلا نامعلوم است؛ Actual/Forecast یا واکنش بازار از تیتر استخراج نمی‌شود.", noObserved)
            }
            ResearchSpace.CRYPTO, ResearchSpace.NOBITEX -> {
                val asset = when { bitcoin.containsMatchIn(title) -> "BTC"; ethereum.containsMatchIn(title) -> "ETH"; else -> null }
                val sensitive = cryptoRisk.containsMatchIn(title)
                val local = space == ResearchSpace.NOBITEX
                HeadlineInsight(note,
                    if (sensitive) "اولویت بررسی موضوعی (نه درجهٔ تأثیر)" else "اولویت نامعلوم (درجهٔ ناشر موجود نیست)",
                    (if (asset == null) "نماد مشخصی از عنوان تأیید نشد." else "فقط نام $asset در عنوان آمده است.") +
                        if (local) " CoinDesk جهانی است، نه اطلاعیهٔ نوبیتکس یا دادهٔ جفت ریالی/USDT." else
                            " نام‌بردن از دارایی، اثر واقعی بر قیمت را ثابت نمی‌کند.",
                    if (sensitive && asset != null) "اگر محتوای کامل و ارتباط خبر با $asset تأیید شود، واکنش مثبت یا منفی ممکن است؛ جهت فعلی نامعلوم است."
                        else "جهت این دارایی/جفت از تیتر معلوم نیست.", noObserved)
            }
            ResearchSpace.IRAN_STOCKS -> {
                val filing = iranFiling.containsMatchIn(title)
                HeadlineInsight(note,
                    if (filing) "اولویت بررسی سند کدال" else "اولویت نامعلوم (خبر عمومی)",
                    if (filing) "اشارهٔ عنوان به گزارش/رویداد شرکتی؛ اصل سند، شناسهٔ نماد و دوره را در کدال بررسی کنید."
                        else "خوراک اقتصاد عمومی است؛ ناشر، اهمیت رویداد یک نماد را درجه‌بندی نکرده است.",
                    "برای همان نماد، ارقام نسبت به انتظار و تاریخ معامله می‌توانند سناریو را عوض کنند؛ جهت احتمالی نامعلوم است.", noObserved)
            }
        }
    }
}
