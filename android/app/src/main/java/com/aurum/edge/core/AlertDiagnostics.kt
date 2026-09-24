package com.aurum.edge.core

import com.aurum.edge.data.FOREX_CALENDAR_SOURCE_URL
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.SignalEngine

/** Read-only, independent checks. Green checks are prerequisites, NOT a forecast or an entry. */
enum class AlertCheckKind(val label: String) {
    KEY("کلید بازار"), MARKET("قیمت واقعی تازه"), HISTORY("کندل بسته"),
    MONITOR("سرویس پایش"), APP_ALERT("هشدار در اپ"), ANDROID_ALERT("اعلان اندروید"),
    STORAGE("فایل‌های هشدار/ژورنال"), AI_NEWS("آمادگی فید/مدل AI"), NINE_WAY("۹/۹، ICT و ریسک"),
}

data class AlertCheck(val kind: AlertCheckKind, val ready: Boolean, val detail: String)

object AlertDiagnostics {
    fun checks(
        market: MarketState, settings: AppSettings, news: PersianNewsState,
        monitorRunning: Boolean, androidNotificationsReady: Boolean,
        trades: List<PaperTrade>, mtf: MtfAnalyzer.Snapshot?,
        opportunityError: String? = null, journalError: String? = null,
        now: Long = System.currentTimeMillis(),
    ): List<AlertCheck> {
        val priceFresh = !market.showingCachedData &&
            market.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            market.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true
        val minBars = SignalEngine.minBars(market.interval)
        val aiFresh = news.lastCheckedAt?.let { now - it in 0L..180_000L } == true
        // Preliminary feed/model availability only; directional alignment is checked below.
        val calendarFresh = news.calendarCheckedAt?.let { now - it in 0L..1_200_000L } == true
        val modelFresh = news.ai.checkedAt?.let { at ->
            now - at in 0L..180_000L && news.lastCheckedAt?.let { reviewed -> at <= reviewed } == true
        } == true
        val sourcesOnline = news.sources.isNotEmpty() && news.sources.all { it.state == "online" } &&
            news.sources.any { it.feed == FOREX_CALENDAR_SOURCE_URL }
        val newsReady = settings.newsBaseUrl.isNotBlank() && !news.cached && !news.loading &&
            news.error == null && aiFresh && calendarFresh && sourcesOnline &&
            news.gate == NewsGate.CLEAR && news.ai.status == "AVAILABLE" && modelFresh &&
            news.ai.symbol == market.symbol && news.ai.confidence in 80.0..100.0 &&
            !news.ai.model.isNullOrBlank() && news.ai.model != "deterministic-fallback" &&
            news.ai.evidenceIds.isNotEmpty()
        val signal = market.signal
        val entryBlocker = if (signal?.isActionable == true)
            PaperAlertRules.blocker(market, settings, news, trades, mtf, now)
        else signal?.blockers?.firstOrNull() ?: "برای این کندل سیگنال تأییدشدهٔ ۹/۹ موجود نیست"
        return listOf(
            AlertCheck(AlertCheckKind.KEY, settings.hasKey,
                if (settings.hasKey) "روی همین نصب موجود است؛ اعتبار کلید از اتصال داده مشخص می‌شود" else
                    "روی این نصب کلیدی نیست؛ در تنظیمات، کلید تازهٔ خواندنی وارد کنید"),
            AlertCheck(AlertCheckKind.MARKET, priceFresh,
                if (priceFresh) "${market.feed.mode.label}؛ قیمت در ۹۰ ثانیهٔ اخیر دریافت شده" else
                    "${market.feed.mode.label}؛ ${market.feed.detail.ifBlank { "زمان قیمت/اتصال معتبر نیست" }}"),
            AlertCheck(AlertCheckKind.HISTORY, market.closedCount >= minBars,
                "${market.closedCount}/$minBars کندل بستهٔ واقعی؛ تاریخچهٔ ناکافی سیگنال تولید نمی‌کند"),
            AlertCheck(AlertCheckKind.MONITOR, settings.backgroundMonitor && monitorRunning,
                when {
                    !settings.backgroundMonitor -> "خاموش است؛ در تنظیمات پایش پس‌زمینه را روشن کنید"
                    !monitorRunning -> "در تنظیمات روشن است اما سرویس اکنون اجرا نمی‌شود؛ وضعیت اعلان دائمی/محدودیت باتری را بررسی کنید"
                    else -> "سرویس در همین فرایند اجرا می‌شود؛ محدودیت اندروید/باتری ممکن است آن را بعداً متوقف کند"
                }),
            AlertCheck(AlertCheckKind.APP_ALERT, settings.notifyOnSignal,
                if (settings.notifyOnSignal) "هشدار کاندیدا فعال است؛ ورود خودکار کاغذی برای آن لازم نیست" else
                    "خاموش است؛ گزینهٔ هشدار کاندیدای ۹/۹ را در تنظیمات فعال کنید"),
            AlertCheck(AlertCheckKind.ANDROID_ALERT, androidNotificationsReady,
                if (androidNotificationsReady) "مجوز و کانال باز هستند؛ نمایش/صدا و مزاحم‌نشدن را با اعلان آزمایشی روی گوشی بررسی کنید" else
                    "مجوز اعلان یا کانال هشدار بسته است؛ در تنظیمات اعلان آزمایشی بفرستید"),
            AlertCheck(AlertCheckKind.STORAGE, opportunityError == null && journalError == null,
                opportunityError ?: journalError ?: "خطای فایل محلی گزارش نشده؛ فرصت‌ها باید قبل از اعلان ثبت شوند"),
            AlertCheck(AlertCheckKind.AI_NEWS, newsReady,
                when {
                    settings.newsBaseUrl.isBlank() -> "سرور HTTPS/مدل تنظیم نشده؛ تیترهای RSS تب خبر، تأیید AI نیستند"
                    news.error != null -> "خطای سرور خبر: ${news.error}"
                    news.loading -> "سرور در حال بررسی است؛ پاسخ قبلی مجوز هشدار نیست"
                    news.gate != NewsGate.CLEAR -> "${news.gate}: ${news.reason}"
                    news.ai.status != "AVAILABLE" -> "مدل AI در دسترس نیست: ${news.ai.reason}"
                    !newsReady -> "پاسخ AI/تقویم/ناشران ناقص یا قدیمی است؛ جزئیات در تب خبر"
                    else -> "فید و مدل پاسخ داده‌اند؛ هم‌جهتی با سیگنال همین کندل هنوز جداگانه بررسی می‌شود"
                }),
            AlertCheck(AlertCheckKind.NINE_WAY, signal?.isActionable == true && entryBlocker == null,
                entryBlocker ?: "شرایط این لحظه تأییدند؛ این به‌تنهایی وقوع هشدار، معامله یا سود را تضمین نمی‌کند"),
        )
    }
}
