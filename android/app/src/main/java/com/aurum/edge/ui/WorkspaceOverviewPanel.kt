package com.aurum.edge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.MarketHours
import com.aurum.edge.data.EquityBoardStatus
import com.aurum.edge.data.ForexPreviewStatus
import com.aurum.edge.data.NobitexScanState
import com.aurum.edge.data.NoticesStatus
import com.aurum.edge.data.PublicCryptoStatus
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

/** Foreground chooser: observations from independent read-only endpoints, NOT four engines. */
@Composable
internal fun WorkspaceOverviewPanel(viewModel: AurumViewModel, space: Workspace, now: Long) {
    Text(MarketHours.labelForWorkspace(space.id, now),
        style = MaterialTheme.typography.labelSmall,
        color = if ((space == Workspace.FOREX && MarketHours.forexWeekendClosed(now)) ||
            (space == Workspace.IRAN_STOCKS && !MarketHours.iranStockSessionScheduled(now)))
            AurumColors.Gold else AurumColors.TextSecondary)
    when (space) {
        Workspace.FOREX -> ForexOverview(viewModel, now)
        Workspace.CRYPTO -> CryptoOverview(viewModel, now)
        Workspace.NOBITEX -> NobitexOverview(viewModel, now)
        Workspace.IRAN_STOCKS -> IranOverview(viewModel, now)
    }
}

@Composable
private fun ForexOverview(viewModel: AurumViewModel, now: Long) {
    val preview by viewModel.forexPreview.collectAsStateWithLifecycle()
    val calendar by viewModel.forexCalendar.collectAsStateWithLifecycle()
    val publishers by viewModel.publicWebNews.collectAsStateWithLifecycle()
    if (MarketHours.forexWeekendClosed(now)) {
        OverviewLine("نمونهٔ قیمت Twelve Data و تقویم Forex Factory در تعطیلی معمول درخواست نمی‌شوند.")
        return
    }
    OverviewLine(when {
        preview.recent(now) -> "Twelve Data · نمونهٔ OHLC XAU/USD ${formatPrice(preview.sample)} USD · شروع کندل M5: ${formatDateTime(preview.barAt)} · دریافت گوشی ${formatDateTime(preview.receivedAt)}؛ قیمت زنده/اجرایی نیست."
        preview.status == ForexPreviewStatus.NEEDS_KEY -> "نمونهٔ طلا: کلید خواندنی Twelve Data لازم است (تنظیمات فضای فارکس)."
        preview.status == ForexPreviewStatus.LOADING -> "در حال دریافت نمونهٔ طلا؛ قیمت قبلی معتبر نیست."
        preview.status == ForexPreviewStatus.IDLE -> "نمونهٔ طلا هنوز بررسی نشده است."
        else -> "نمونهٔ طلا نامعتبر/قدیمی است: ${preview.error ?: "دریافت قیمت تأیید نشد"}"
    })
    OverviewLine(when {
        calendar.loading -> "Forex Factory · در حال بررسی تقویم این هفته؛ نتیجهٔ قبلی تأیید نیست."
        calendar.online(now) -> "Forex Factory · تقویم دریافت ${formatDateTime(calendar.checkedAt)} · ${calendar.events.count { it.country == "USD" }} رویداد USD؛ برنامهٔ خبر است، نه نتیجه/جهت معامله."
        calendar.checkedAt == null && calendar.error == null -> "Forex Factory · تقویم هنوز بررسی نشده است."
        else -> "Forex Factory · وضعیت تقویم نامشخص/قدیمی؛ نبود خبر یا ریسک کم تأیید نمی‌شود."
    })
    OverviewNewsLine(publishers, now)
}

@Composable
private fun CryptoOverview(viewModel: AurumViewModel, now: Long) {
    val prices by viewModel.publicCrypto.collectAsStateWithLifecycle()
    val publishers by viewModel.cryptoWebNews.collectAsStateWithLifecycle()
    val bitcoin = prices.quotes.firstOrNull { it.id == "bitcoin" }
    OverviewLine(when {
        prices.recent(now) && bitcoin != null && now - bitcoin.providerAt in 0L..600_000L ->
            "CoinGecko · BTC ${formatPrice(bitcoin.priceUsd)} USD (تک‌منبعی) · زمان قیمت منبع ${formatDateTime(bitcoin.providerAt)} · دریافت گوشی ${formatDateTime(prices.receivedAt)}؛ نه قیمت Binance/سفارش."
        prices.status == PublicCryptoStatus.LOADING -> "CoinGecko · در حال بررسی قیمت؛ دادهٔ قبلی تازه نیست."
        prices.status == PublicCryptoStatus.IDLE -> "CoinGecko · هنوز بررسی نشده است."
        else -> "CoinGecko · قیمت قابل اتکای اخیر نداریم${prices.error?.let { ": $it" } ?: "؛ زمان منبع/دریافت قدیمی است"}."
    })
    OverviewNewsLine(publishers, now)
}

@Composable
private fun NobitexOverview(viewModel: AurumViewModel, now: Long) {
    val scan by viewModel.nobitexScan.collectAsStateWithLifecycle()
    val notices by viewModel.nobitexNotices.collectAsStateWithLifecycle()
    OverviewLine(when (val item = scan) {
        NobitexScanState.Idle -> "API عمومی /market/stats نوبیتکس · هنوز بررسی نشده است."
        NobitexScanState.Loading -> "آمار رسمی نوبیتکس · در حال بررسی جفت‌های USDT/ریال."
        is NobitexScanState.Failed -> "آمار رسمی نوبیتکس ناموجود: ${item.message}؛ نه «هیچ نامزدی نیست»."
        is NobitexScanState.Done -> if (item.snapshot.fresh(now)) {
            val btc = item.snapshot.pairs.firstOrNull { it.base == "BTC" && it.quote == "USDT" }
            "آمار رسمی نوبیتکس · BTC/USDT ${formatPrice(btc?.latest)} · دریافت گوشی ${formatDateTime(item.snapshot.receivedAt)}؛ زمان آخرین معامله در stats نیست."
        } else "آمار نوبیتکس ناقص/قدیمی؛ قیمت تازه یا نامزد فعال تأیید نمی‌شود."
    })
    OverviewLine(when {
        notices.status == NoticesStatus.LOADING -> "صفحهٔ رسمی اطلاعیه‌ها · در حال بررسی HTML؛ تاریخ انتشار هنوز تأیید نیست."
        notices.recentReceipt(now) -> "صفحهٔ رسمی اطلاعیه‌ها · ${notices.items.size} عنوان خوانده شد · دریافت گوشی ${formatDateTime(notices.receivedAt)}؛ فید API/RSS مستند نیست."
        notices.status == NoticesStatus.IDLE -> "صفحهٔ رسمی اطلاعیه‌ها · هنوز بررسی نشده است."
        else -> "اطلاعیه‌های نوبیتکس نامشخص/قدیمی؛ صفحهٔ رسمی را در فضای نوبیتکس ببینید."
    })
    if (notices.recentReceipt(now)) notices.items.firstOrNull()?.let { item ->
        OverviewLine("عنوان صفحه: ${item.title} · تاریخ درج‌شده ${item.dateLabel ?: "نامشخص"}؛ خبر فوری/اعلان خودکار نیست.")
    }
}

@Composable
private fun IranOverview(viewModel: AurumViewModel, now: Long) {
    val board by viewModel.equities.collectAsStateWithLifecycle()
    val publishers by viewModel.iranWebNews.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    OverviewLine(when {
        !MarketHours.iranStockSessionScheduled(now) -> "BrsApi · خارج از ساعت معمول، تابلو درخواست نمی‌شود؛ تعطیلی رسمی/توقف نماد هم ممکن است."
        settings.stockDataKey.isBlank() -> "BrsApi · برای تابلوی TSETMC کلید دادهٔ خواندنی لازم است (فضای بورس)؛ کلید آگاه نه."
        board.status == EquityBoardStatus.LOADING -> "BrsApi · در حال دریافت snapshot؛ قیمت قبلی تازه نیست."
        board.recentReceipt(now) -> "BrsApi · ${board.rows.size} ردیف تابلو · دریافت گوشی ${formatDateTime(board.receivedAt)}؛ زمان قیمت فقط HH:mm:ss بدون تاریخ مستقل است."
        board.status == EquityBoardStatus.UNCONFIGURED -> "BrsApi · تابلو هنوز بررسی نشده؛ کلید خواندنی در فضای بورس قابل مدیریت است."
        else -> "BrsApi · تابلو ناموجود/قدیمی: ${board.error ?: "تاریخ قیمت تأیید نیست"}"
    })
    OverviewNewsLine(publishers, now)
    OverviewLine("سنا/اقتصاد ایران ≠ افشای کدال؛ گزارش‌های TTM فقط از سند رسمی قابل بررسی‌اند.")
}

@Composable
private fun OverviewNewsLine(state: PublicWebNewsState, now: Long) {
    val online = if (state.loading) emptyList() else state.feeds.filter { it.online(now) }
    OverviewLine(when {
        state.loading -> "خبر ناشران · در حال بررسی RSS؛ کش قبلی خبر تازه نیست."
        state.lastAttemptAt == null -> "خبر ناشران · هنوز بررسی نشده است."
        online.isEmpty() -> "خبر ناشران · هیچ خوراک تازهٔ تأییدشده‌ای نیست؛ قطعی منبع به معنی نبود خبر نیست. آخرین تلاش ${relativeTime(state.lastAttemptAt, now)}."
        else -> "خبر ناشران · ${online.size}/${state.feeds.size} خوراک دریافت شد · آخرین بررسی ${relativeTime(state.lastAttemptAt, now)}."
    })
    val article = state.headlines.firstOrNull { item -> online.any { it.feed.id == item.feed.id } &&
        now - item.publishedAt in 0L..86_400_000L }
    if (article != null) OverviewLine("${article.feed.title} · ${article.title.take(95)} · انتشار ${formatDateTime(article.publishedAt)}؛ دریافت ${formatDateTime(article.receivedAt)} (نه سیگنال).")
}

@Composable
private fun OverviewLine(value: String) {
    Text(value, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
}
