package com.aurum.edge.core

import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.domain.FeedMode

/** Read-only, independent checks. Green checks are prerequisites, NOT a forecast or an entry. */
enum class AlertCheckKind(val label: String) {
    KEY("منبع بازار"), MARKET("قیمت واقعی تازه"), HISTORY("کندل بسته"),
    MONITOR("سرویس پایش"), APP_ALERT("هشدار در اپ"), ANDROID_ALERT("اعلان اندروید"),
    STORAGE("فایل‌های هشدار/ژورنال"), NINE_WAY("چهار لایهٔ فنی و هفت بازه"),
    SCAN("اسکن پیوستهٔ نمادها"),
    TREND("روند کلی بازار"),
}

data class AlertCheck(val kind: AlertCheckKind, val ready: Boolean, val detail: String)

object AlertDiagnostics {
    fun checks(
        market: MarketState, settings: AppSettings,
        monitorRunning: Boolean, androidNotificationsReady: Boolean,
        trades: List<PaperTrade>, mtf: MtfAnalyzer.Snapshot?,
        opportunityError: String? = null, journalError: String? = null,
        /** Continuous universe radar state; scanning itself must be monitored, not assumed. */
        scan: com.aurum.edge.data.PairScanState? = null,
        now: Long = System.currentTimeMillis(),
    ): List<AlertCheck> {
        val lastSweepAt = scan?.lastSweepAt
        val scanAgeMs = if (lastSweepAt != null) now - lastSweepAt else null
        val priceFresh = !market.showingCachedData &&
            market.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            FeedLiveness.hasRecentReceipt(market.feed, now)
        val minBars = HistoryPolicy.LIVE_MIN_CANDLES
        val signal = market.signal
        val entryBlocker = if (signal?.isActionable == true)
            PaperAlertRules.blocker(market, settings, trades, mtf, now)
        else market.evaluationError ?: signal?.blockers?.firstOrNull()
            ?: "برای این کندل سیگنال فنی تأییدشده موجود نیست"

        // ── «روند کلی بازار» به‌عنوان یک پیش‌نیازِ دیده‌شدنی ──────────────────────────
        val trendSymbol = runCatching {
            MarketTrend.symbolTrend(market.symbol, market.candles, market.interval)
        }.getOrNull()
        val trendOverall = scan?.marketTrend
        val trendSummary = buildString {
            append(trendSymbol?.let { "روند ${market.symbol}: ${it.shortFa} روی ${it.higherLabel ?: it.intervalLabel}" }
                ?: "روند ${market.symbol}: اندازه گرفته نشد (کندل بستهٔ کافی نیست)")
            append(trendOverall?.let {
                " · روند کلی بازار: ${it.bias.label} ${it.strength}٪ (${it.breadthFa}) · دلار " +
                    "${it.dollarBias.label} · جوّ ${it.riskTone.label}"
            } ?: " · روند کلی بازار: هنوز از پویشِ نمادها اندازه گرفته نشد")
        }
        // Breadth/trend is context, not a fifth V1 gate or a secret threshold increase.
        val trendCheck = AlertCheck(AlertCheckKind.TREND, true,
            "زمینهٔ بازار (نه قفل ورود): $trendSummary")

        return listOf(
            AlertCheck(AlertCheckKind.KEY, settings.hasKey || market.closedCount >= minBars,
                if (settings.hasKey) "کلید Twelve روی همین نصب موجود است؛ اعتبار آن از اتصال داده مشخص می‌شود" else
                    "حالت بدون کلید فعال است؛ تاریخچهٔ عمومی/فید رایگان باید حداقل $minBars کندل بستهٔ واقعی بدهد"),
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
                    "خاموش است؛ گزینهٔ هشدار کاندیدای چهارلایهٔ فنی را در تنظیمات فعال کنید"),
            AlertCheck(AlertCheckKind.ANDROID_ALERT, androidNotificationsReady,
                if (androidNotificationsReady) "مجوز و کانال باز هستند؛ نمایش/صدا و مزاحم‌نشدن را با اعلان آزمایشی روی گوشی بررسی کنید" else
                    "مجوز اعلان یا کانال هشدار بسته است؛ در تنظیمات اعلان آزمایشی بفرستید"),
            AlertCheck(AlertCheckKind.STORAGE, opportunityError == null && journalError == null,
                opportunityError ?: journalError ?: "خطای فایل محلی گزارش نشده؛ فرصت‌ها باید قبل از اعلان ثبت شوند"),
            AlertCheck(AlertCheckKind.SCAN,
                scan != null && scanAgeMs != null && scanAgeMs in 0L..180_000L && scan.lastError == null,
                when {
                    scan == null -> "اسکنر نمادها در این فرایند هنوز وضعیت ندارد"
                    scanAgeMs == null -> "هنوز هیچ پاس اسکنی کامل نشده است؛ فید/تاریخچهٔ نمادها را بررسی کنید"
                    scan.sweeping -> "اسکن پیوسته در جریان است؛ آخرین پاس کامل ${ago(scanAgeMs)}"
                    scanAgeMs > 180_000L -> "اسکن پیوسته متوقف شده است: آخرین پاس ${ago(scanAgeMs)} — " +
                        "اپ را در پیش‌زمینه نگه دارید یا پایش پس‌زمینه را روشن کنید"
                    scan.lastError != null -> "آخرین پاس اسکن با خطا: ${scan.lastError}"
                    else -> "آخرین پاس ${ago(scanAgeMs)}؛ ${scan.statuses.size} نماد پایش شد، " +
                        "${scan.statuses.count { it.state == "candidate" }} کاندیدا، " +
                        "${scan.statuses.count { it.state == "error" }} خطای دریافت داده"
                }),
            AlertCheck(AlertCheckKind.NINE_WAY, signal?.isActionable == true && entryBlocker == null,
                entryBlocker ?: "شرایط این لحظه تأییدند؛ این به‌تنهایی وقوع هشدار، معامله یا سود را تضمین نمی‌کند"),
            trendCheck,
        )
    }

    /** «چقدر پیش» به فارسی — برای اینکه توقف اسکن پیوسته با عدد واقعی دیده شود. */
    private fun ago(ageMs: Long): String {
        val seconds = (ageMs / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60 -> "$seconds ثانیه پیش"
            seconds < 3_600 -> "${seconds / 60} دقیقه پیش"
            else -> "${seconds / 3_600} ساعت پیش"
        }
    }
}
