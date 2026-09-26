package com.aurum.edge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsRepository
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/** Practical BYO-server setup. This page observes state only; it never flips a gate or stores a model key. */
@Composable
fun NinthGateGuideScreen(viewModel: AurumViewModel, market: MarketState,
                         onOpenSettings: () -> Unit, onOpenNews: () -> Unit, onOpenSignal: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val news by viewModel.news.collectAsStateWithLifecycle()
    val running by SignalMonitorService.running.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000L); now = System.currentTimeMillis() } }
    val rootValid = NewsRepository.newsUrl(settings.newsBaseUrl) != null
    val action = when (news.ai.direction) {
        "BUY" -> SignalAction.BUY
        "SELL" -> SignalAction.SELL
        else -> null
    }
    val alignment = action?.let { NewsConfluence.alignment(market.symbol, it, news, now) }
    val modelChecked = rootValid && alignment?.status == ConfluenceStatus.CONFIRMED
    val liveTick = market.feed.mode == FeedMode.LIVE && !market.showingCachedData &&
        market.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("راهنمای شرط نهم · فقط XAU/USD", "پیش‌نیاز ورود خودکار paper-only، نه راه‌اندازی سفارش واقعی") {
            Text("بدون سرور HTTPS متعلق به شما + مدل معتبر با کلید و رضایت روی همان سرور، خبرهای مستقیم فقط پژوهشی‌اند؛ شرط نهم UNKNOWN می‌ماند. هیچ کلید AI در APK یا چت وارد نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            Text("وضعیت همین دستگاه، نه آزمایش موفق سرور:", style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
            Text("${if (rootValid) "✓" else "✕"} ریشهٔ HTTPS خبر ${if (rootValid) "ثبت شده؛ اتصال هنوز باید بررسی شود" else "موجود/معتبر نیست"}",
                style = MaterialTheme.typography.labelSmall, color = if (rootValid) AurumColors.Cyan else AurumColors.Gold)
            Text("${if (modelChecked) "✓" else "✕"} گیت/مدل/شاهد: ${if (modelChecked) "پاسخ فعلاً معتبر؛ هم‌جهتی با سیگنال جداست" else alignment?.detail ?: news.ai.reason} · بررسی ${relativeTime(news.lastCheckedAt, now)}",
                style = MaterialTheme.typography.labelSmall, color = if (modelChecked) AurumColors.Cyan else AurumColors.Gold)
            Text("${if (liveTick) "✓" else "✕"} تیک WebSocket تازه: ${market.feed.mode.label}؛ کندل REST/کش برای ورود خودکار کافی نیست.",
                style = MaterialTheme.typography.labelSmall, color = if (liveTick) AurumColors.Cyan else AurumColors.Gold)
            Text("${if (settings.backgroundMonitor && running) "✓" else "✕"} سرویس پایش: ${if (running) "در حال اجرا" else "اجرا نمی‌شود"} · سوییچ ${if (settings.backgroundMonitor) "روشن" else "خاموش"}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("ورود خودکار کاغذی: سوییچ ${if (settings.autoPaperTrading) "روشن است، اما فقط با تمام گیت‌های لحظه‌ای ممکن می‌شود" else "خاموش است"}. ${if (MarketHours.forexWeekendClosed(now)) "طبق ساعت معمول بازار فارکس بسته است؛ ورود مسدود." else "بازبودن واقعی فقط با قیمت تازه مشخص می‌شود."}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("بازکردن تنظیمات سرور و ریسک")
            }
            OutlinedButton(onClick = onOpenNews, modifier = Modifier.fillMaxWidth()) { Text("بازکردن خبر و شاهد") }
            OutlinedButton(onClick = onOpenSignal, modifier = Modifier.fillMaxWidth()) { Text("چرا هشدار نیامده؟") }
        }
        SectionCard("۱ · حساب و حق استفاده", "کلید شخصی؛ سهمیه/دسترسی رایگان را در مستندات فعلی بررسی کنید") {
            Text("در Google AI Studio یک کلید تازه بسازید؛ کلید قبلاً افشاشده را تعویض کنید. قیمت/سهمیه/دسترسی منطقه‌ای مدل پیش‌فرض gemini-2.5-flash-lite ممکن است تغییر کند. پیش از ارسال تیتر/چکیدهٔ ناشران به مدل بیرونی، حق استفاده و شرایط حریم خصوصی پلن را بررسی کنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") } }) {
                Text("Google AI Studio ↗")
            }
            OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://ai.google.dev/gemini-api/docs/pricing") } }) {
                Text("سهمیه و هزینهٔ روز ↗")
            }
        }
        SectionCard("۲ · سرور شخصی HTTPS", "بدون سرور، فعال‌سازی واقعی ممکن نیست") {
            Text("بک‌اند همین پروژه (backend/) را روی رایانه/سرور خود با Python راه‌اندازی کنید، سپس با دامنهٔ تحت کنترل خود و گواهی TLS معتبر روی پورت ۴۴۳ منتشر کنید. localhost گوشی سرور شما نیست؛ IP بدون گواهی معتبر، رمز در URL یا HTTP کار نمی‌کنند. مسیرهای غیرضروری API را در reverse proxy از اینترنت مسدود کنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("متغیرهای سرور: AURUM_GEMINI_API_KEY و، فقط پس از رضایت حقوقی، AURUM_AI_NEWS_EXTERNAL_CONSENT=true. کلید مدل را در secret manager یا فایل خصوصی .env سرور نگه دارید؛ هرگز در APK/گیت‌هاب/گفتگو یا فیلد کلید قیمت گوشی نگذارید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        SectionCard("۳ · آزمایش پاسخ واقعی، نه صرفاً health", "GET https://دامنهٔ خودتان/api/v1/news/web") {
            Text("پاسخ /api/v1/health با status=ok فقط روشن‌بودن برنامهٔ سرور است. در /api/v1/news/web باید status.state=online، منابع RSS و تقویم Forex Factory تازه، guard.state=CLEAR و ai_confluence.status=AVAILABLE با شناسهٔ شاهد، لینک معتبر، زمان تازه و BUY/SELL با اطمینان کافی ببینید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("BLOCKED هنگام رویداد پراثر، یا UNKNOWN هنگام نبود خبر مرتبط، قطعی منبع، سهمیه/رضایت/مدل ناکافی، رفتار درست و محافظه‌کارانه است؛ برای دیدن سبز اجباری نباید داده/وضعیت ساختگی تولید کنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        SectionCard("۴ · اتصال گوشی و حدهای ایمنی", "اول شرایط داده/ریسک را تنظیم کنید، سپس سوییچ کاغذی") {
            Text("در تنظیمات خبر فقط ریشهٔ https://your-domain.example را بدهید (بدون /api/v1/news/web، مسیر، query یا پورت غیر۴۴۳). اپ مسیر را خودش اضافه می‌کند. کلید خواندنی Twelve Data را جداگانه روی گوشی وارد کنید؛ LIVE بودن تیک و کندل‌های بسته را بررسی کنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("موجودی فرضی، سقف ریسک/اسپرد/کمیسیون را مطابق شرایط خود تعیین کنید؛ پایش، اجازهٔ اعلان و تنظیمات باتری را آزمون کنید. سپس در فارکس، در صورت تمایل، «ورود خودکار کاغذی» را روشن کنید. روشن‌کردن سوییچ هرگز ۹/۹، جهت مدل، ICT، MTF، قیمت تازه یا پوزیشن باز را دور نمی‌زند. اعلان ورود فقط پس از ثبت موفق در ژورنال است؛ سفارش واقعی وجود ندارد.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
        SectionCard("اگر فعال نشد", "راه عیب‌یابی کوتاه") {
            Text("در خبر، زمان و خطای هر ناشر/تقویم، guard، شناسهٔ شاهد و زمان مدل را ببینید. در «چرا هشدار نیامده؟» کلید قیمت، تعداد کندل، WebSocket LIVE، سرویس، مجوز اعلان، ۹/۹ و وضعیت ژورنال را جدا بررسی کنید. تعطیلی معمول، کمبود خبر قابل استناد، محدودیت مدل/ناشر یا خبری که معامله را وتو می‌کند، مجوز ورود نیستند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
    }
}
