package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.ResearchState
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicNewsCategory
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun PersianNewsScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val server by viewModel.news.collectAsStateWithLifecycle()
    val web by viewModel.publicWebNews.collectAsStateWithLifecycle()
    val calendar by viewModel.forexCalendar.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var category by remember { mutableStateOf<PublicNewsCategory?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var showFeeds by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showServerArticles by remember { mutableStateOf(false) }

    LaunchedEffect(settings.newsBaseUrl) { viewModel.refreshNews() }
    LaunchedEffect(Unit) {
        viewModel.refreshPublicWebNews() // no backend URL or news API key needed
        while (true) { delay(900_000L); viewModel.refreshPublicWebNews() }
    }
    LaunchedEffect(Unit) {
        while (true) { viewModel.refreshForexCalendar(); delay(60_000L) } // repository throttles outside release windows
    }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000L); now = System.currentTimeMillis() }
    }
    val filtered = web.headlines.filter { (category == null || it.feed.category == category) &&
        now - it.publishedAt in -15 * 60_000L..it.feed.maxAgeHours * 3_600_000L }
    val onlineFeeds = web.feeds.count { it.online(now) }
    val serverTone = when (server.gate) {
        NewsGate.CLEAR -> AurumColors.Green
        NewsGate.BLOCKED -> AurumColors.Red
        NewsGate.UNKNOWN -> AurumColors.Gold
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("Forex Factory · مرجع اصلی خبر فارکس", "تقویم اقتصادی هفتگی · وب عمومی مستقیم · تمرکز روی USD/XAU",
            trailing = { Pill(when {
                !calendar.online(now) -> "نامشخص"
                calendar.highImpactUsdWindow(now) -> "بازهٔ خبر پراثر"
                else -> "تقویم دریافت شد"
            }, when {
                !calendar.online(now) -> AurumColors.Gold
                calendar.highImpactUsdWindow(now) -> AurumColors.Red
                else -> AurumColors.Cyan
            }) }) {
            Text("ابتدا رویدادهای USD؛ ● قرمز = اثر زیاد · ساعت به وقت گوشی · آخرین بررسی ${relativeTime(calendar.checkedAt, now)}. برنامه/پیش‌بینی لزوماً نتیجهٔ خبر نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text(if (calendar.highImpactUsdWindow(now)) "رویداد High برای USD از ۳۰ دقیقه پیش تا ۴۵ دقیقه پس از آن: فقط هشدار پژوهشی؛ ورود خودکار تنها با گیت مستقل سرور بررسی می‌شود."
                 else if (calendar.online(now)) "بازهٔ High/USD در این تقویم مشاهده نشد؛ این عبارت تأیید نبود خبر یا مجوز معامله نیست."
                 else "تقویم نامعتبر/قطع است؛ وضعیت ریسک خبر نامشخص است، نه امن.",
                style = MaterialTheme.typography.labelSmall,
                color = if (calendar.highImpactUsdWindow(now)) AurumColors.Red else AurumColors.Gold,
                modifier = Modifier.padding(top = 5.dp))
            calendar.error?.let { Text("تقویم در دسترس نیست: $it · نبود داده به معنی نبود رویداد نیست",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            val upcoming = if (calendar.online(now)) calendar.events.filter {
                it.at in (now - 6 * 3_600_000L)..(now + 7 * 86_400_000L)
            }.take(90) else emptyList()
            if (upcoming.isEmpty()) Text("رویداد قابل نمایش دریافت نشده؛ وضعیت خبر برای ورود تأیید نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            else {
                val preview = upcoming.filter { it.country == "USD" }
                    .sortedBy { abs(it.at - now) }.take(6).ifEmpty { upcoming.take(6) }
                (if (showCalendar) upcoming else preview).forEach { event ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).background(
                            if (event.impact == "High") AurumColors.Red else AurumColors.TextMuted,
                            RoundedCornerShape(2.dp)))
                        Text("${event.country} · ${formatDateTime(event.at)} · ${event.title}" +
                            (if (event.impact == "High") " · پراثر" else ""),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (event.impact == "High") AurumColors.Red else AurumColors.TextSecondary)
                    }
                    if (event.forecast != null || event.previous != null || event.actual != null) Text(
                        "پیش‌بینی ${event.forecast ?: "—"} · قبل ${event.previous ?: "—"} · منتشرشده ${event.actual ?: "—"}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                    if (event.country == "USD") {
                        val note = NewsResearch.gold(event, calendar, now)
                        Text("${note.title} · ${note.detail}", style = MaterialTheme.typography.labelSmall,
                            color = if (note.state == ResearchState.PUBLISHED) AurumColors.Cyan else AurumColors.Gold,
                            modifier = Modifier.padding(start = 16.dp, top = 3.dp))
                    }
                    OutlinedButton(onClick = {
                        TranslateLink.englishToPersian(event.title)?.let { url ->
                            runCatching { uriHandler.openUri(url) }
                        }
                    }) { Text("ترنسلیت عنوان ↗") }
                }
                if (upcoming.size > preview.size) OutlinedButton(onClick = { showCalendar = !showCalendar }) {
                    Text(if (showCalendar) "فقط USD" else "نمایش تمام ${upcoming.size} رویداد")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refreshForexCalendar, enabled = !calendar.loading, modifier = Modifier.weight(1f)) {
                    Text(if (calendar.loading) "دریافت…" else "بررسی تقویم")
                }
                OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://www.forexfactory.com/calendar") } },
                    modifier = Modifier.weight(1f)) { Text("وب‌سایت منبع") }
            }
            TextButton(onClick = { runCatching { uriHandler.openUri("https://www.forexfactory.com/news") } }) {
                Text("متن خبرهای Forex Factory در مرورگر ↗")
            }
            Text("تحلیل بالا فقط مقایسهٔ قاعده‌ایِ اعدادِ منتشرشدهٔ تقویم است؛ متن مقالهٔ Forex Factory در خوراک عمومیِ این برنامه نیست. نتیجهٔ غایب جعل نمی‌شود و از آن سیگنال طلا استخراج نمی‌کنیم.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            Text("سرور، اگر جداگانه تنظیم شده باشد، توقف ورود جدید برای رویداد پراثر USD را مستقل بررسی می‌کند. تقویم عمومی کامل‌بودن خبرها را ثابت نمی‌کند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }


        SectionCard("تیترهای مکمل فارکس", "FXStreet و آمار رسمی BLS · RSS/Atom مستقل از تقویم Forex Factory",
            trailing = { Pill(if (web.loading) "در حال دریافت" else "$onlineFeeds/${web.feeds.size} خوراک",
                if (onlineFeeds > 0 && !web.loading) AurumColors.Cyan else AurumColors.Gold) }) {
            Text("عنوان، چکیده و زمان از ناشرند. گزینهٔ ترنسلیت فقط با لمس شما متن کوتاه را در Google Translate باز می‌کند؛ ترجمه در گیت AI/سیگنال استفاده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("آخرین تلاش: ${relativeTime(web.lastAttemptAt, now)} · قطع یک ناشر، خبرهای دیگر را پنهان نمی‌کند. به‌روزرسانی حداکثر هر ۶۰ ثانیه.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 5.dp))
            Text("صرفاً برای مطالعه؛ این تیترها هرگز شرط نهم AI، تأیید خبر برای معامله یا مجوز سفارش نیستند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshPublicWebNews, enabled = !web.loading, modifier = Modifier.weight(1f)) {
                    Text(if (web.loading) "دریافت…" else "تازه‌سازی تیترها")
                }
                OutlinedButton(onClick = { showFeeds = !showFeeds }, modifier = Modifier.weight(1f)) {
                    Text("وضعیت خوراک‌ها")
                }
            }
            if (showFeeds || onlineFeeds == 0) web.feeds.forEach { feed ->
                val label = when {
                    feed.online(now) -> "دریافت شد"
                    feed.state == PublicFeedState.PENDING -> "هنوز بررسی نشده"
                    feed.state == PublicFeedState.OUTDATED -> "خبر تازه ندارد"
                    feed.state == PublicFeedState.ONLINE -> "دریافت قدیمی"
                    else -> "قطع/خطا"
                }
                Text("${feed.feed.title}: $label · ${feed.detail} · بررسی ${relativeTime(feed.checkedAt, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (feed.online(now)) AurumColors.Cyan else AurumColors.Gold,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "همه", PublicNewsCategory.MARKETS to "فارکس/طلا",
                PublicNewsCategory.ECONOMY to "آمار رسمی").forEach { (id, label) ->
                FilterChip(selected = category == id, onClick = { category = id; showAll = false }, label = { Text(label) })
            }
        }
        if (filtered.isEmpty()) {
            SectionCard("تیتر قابل نمایش نیست", "نبود تیتر به معنی نبود خبر یا امن‌بودن بازار نیست") {
                Text(if (web.loading) "در حال خواندن خوراک‌های ناشران…" else
                    "به وضعیت هر خوراک در بالا نگاه کنید و با اینترنت دوباره تلاش کنید؛ برای نمایش خبر نیازی به واردکردن نشانی سرور نیست.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            }
        }
        filtered.take(if (showAll) 45 else 8).forEach { item ->
            val feedStatus = web.feeds.firstOrNull { it.feed.id == item.feed.id }
            val fromRecentResponse = !web.loading && feedStatus?.online(now) == true &&
                item.receivedAt >= (feedStatus.checkedAt ?: 0L) - 30_000L
            val periodic = item.feed.category == PublicNewsCategory.ECONOMY
            val receiptLabel = when {
                !fromRecentResponse -> "کش/دریافت پیشین"
                periodic -> "گزارش دوره‌ای"
                else -> "از خوراک ناشر"
            }
            SectionCard(item.title,
                "${item.feed.title} · ${if (item.feed.language == "en") "EN · زبان اصلی" else "FA"} · انتشار ${formatDateTime(item.publishedAt)}" +
                    (if (item.publishedAt > now) " · ساعت ناشر جلوتر است" else " · ${relativeTime(item.publishedAt, now)}"),
                trailing = { Pill(receiptLabel, if (fromRecentResponse) AurumColors.Cyan else AurumColors.Gold) }) {
                if (item.excerpt.isNotBlank()) Text(item.excerpt,
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                val context = NewsResearch.headline(item, web, ResearchSpace.FOREX, now)
                Text("${context.title}: ${context.detail}", style = MaterialTheme.typography.labelSmall,
                    color = if (context.state == ResearchState.CONTEXT) AurumColors.Cyan else AurumColors.Gold)
                Text("دریافت در گوشی: ${formatDateTime(item.receivedAt)} · ${if (fromRecentResponse) "وضعیت خوراک بالا" else "قدیمی/کش؛ تازگی مجدد تأیید نشده"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(item.url) } },
                        modifier = Modifier.weight(1f)) { Text("سایت ناشر") }
                    if (item.feed.language == "en") OutlinedButton(onClick = {
                        TranslateLink.englishToPersian(item.title, item.excerpt)?.let { url ->
                            runCatching { uriHandler.openUri(url) }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("ترنسلیت ↗") }
                }
            }
        }
        if (filtered.size > 8 && !showAll) {
            OutlinedButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("نمایش ${minOf(filtered.size - 8, 37)} تیتر دیگر")
            }
        }

        SectionCard("شرط نهم AI · جدا از تیترهای نمایشی", "فقط XAU/USD · نیازمند سرور HTTPS، مدل و شواهد معتبر",
            trailing = { Pill(server.gate.name, serverTone) }) {
            Text(if (settings.newsBaseUrl.isBlank())
                "برای دیدن تیترها سرور لازم نیست؛ اما گیت معامله و AI بدون سرور تنظیم نشده و UNKNOWN است."
                else server.reason, style = MaterialTheme.typography.bodySmall, color = serverTone)
            Text("سرور: ${server.provider ?: "تنظیم نشده"} · آخرین دریافت ${relativeTime(server.lastCheckedAt, now)}" +
                (if (server.cached) " · دادهٔ قبلی؛ گیت UNKNOWN" else ""),
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            server.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            val ready = server.ai.status == "AVAILABLE" && server.gate == NewsGate.CLEAR && !server.cached && !server.loading
            Text(if (ready) "جهت پیشنهادی مدل: ${server.ai.direction} · اطمینان ${server.ai.confidence.toInt()}٪"
                else "AI: UNKNOWN · ${server.ai.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) AurumColors.Green else AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
            Text("مدل: ${server.ai.model ?: "فعال نیست"} · بررسی ${relativeTime(server.ai.checkedAt, now)} · تیترهای مستقیم گوشی هرگز شاهد این تحلیل نیستند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            server.ai.evidenceIds.mapNotNull { id -> server.articles.singleOrNull { it.id == id } }.forEach { source ->
                Text("شاهدِ سرور: ${source.source} · ${source.headline}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                source.link?.let { url ->
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                        Text("شاهد در منبع", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refreshNews, enabled = !server.loading && settings.newsBaseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)) { Text("بررسی گیت") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("تنظیم سرور") }
            }
            Text("وتوی خبر برای ورود دستی کاغذی ${if (settings.pauseOnNews) "روشن" else "خاموش"} است؛ برای ورود سیگنالی/خودکار شرط مدل همیشه الزامی است. CLEAR تضمین یا مجوز سفارش واقعی نیست.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            if (server.sources.isNotEmpty() || server.articles.isNotEmpty()) {
                OutlinedButton(onClick = { showServerArticles = !showServerArticles }) {
                    Text(if (showServerArticles) "جمع کردن شواهد سرور" else "جزئیات خبر سرور")
                }
            }
        }
        if (showServerArticles && server.sources.isNotEmpty()) {
            SectionCard("وضعیت خوراک‌های سرور", "مستقل از خوراک‌های مستقیم بالا") {
                server.sources.forEach { source ->
                    Text("${source.name}: ${if (source.state == "online") "دریافت شد" else "ناموجود"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.state == "online") AurumColors.Green else AurumColors.Red)
                }
            }
        }
        if (showServerArticles) server.articles.forEach { item ->
            SectionCard(item.headline, "${item.source} · انتشار ${formatDateTime(item.publishedAt)}",
                trailing = { Pill(item.impact, if (item.impact == "HIGH") AurumColors.Red else AurumColors.TextMuted) }) {
                if (item.summary.isNotBlank()) Text(item.summary,
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                Text("برچسب قاعده‌ای: ${item.direction} · ${item.analysisSource} · نه AI و نه سیگنال",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                item.link?.let { url -> OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                    Text("خبر در منبع")
                } }
            }
        }
    }
}
