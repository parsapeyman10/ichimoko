package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.NewsGate
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

@Composable
fun PersianNewsScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.news.collectAsStateWithLifecycle()
    val calendar by viewModel.forexCalendar.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(settings.newsBaseUrl) { viewModel.refreshNews() }
    LaunchedEffect(Unit) {
        while (true) { viewModel.refreshForexCalendar(); delay(900_000L) }
    }
    val tone = when (state.gate) {
        NewsGate.CLEAR -> AurumColors.Green
        NewsGate.BLOCKED -> AurumColors.Red
        NewsGate.UNKNOWN -> AurumColors.Gold
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("اخبار واقعی از وب", "RSS رسمی/عمومی ناشران؛ تیتر و چکیدهٔ کوتاه بدون ترجمه یا محتوای ساختگی",
            trailing = { Pill(state.gate.name, tone) }) {
            Text(state.reason, style = MaterialTheme.typography.bodySmall, color = tone)
            Text("منبع: ${state.provider ?: "پیکربندی نشده"} · آخرین دریافت: ${relativeTime(state.lastCheckedAt)}" +
                (if (state.cached) " · داده قبلی/وضعیت نامشخص" else ""),
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 5.dp))
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshNews, enabled = !state.loading, modifier = Modifier.weight(1f)) {
                    Text(if (state.loading) "دریافت…" else "تازه‌سازی")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("تنظیم سرور") }
            }
            Text("وتوی خبر برای ورود دستی کاغذی ${if (settings.pauseOnNews) "روشن" else "خاموش"} است؛ برای ورود خودکار و سیگنالی شرط AI همیشه الزامی است. قطع یک خوراک UNKNOWN می‌کند. CLEAR تقویم کامل یا اجازهٔ سفارش واقعی نیست.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp))
        }
        SectionCard("تقویم اقتصادی Forex Factory", "دریافت مستقیم وب، مستقل از سرور خبر · برنامهٔ رویداد، نه نتیجهٔ خبر یا سیگنال",
            trailing = { Pill(if (calendar.online()) "دریافت شد" else "نامشخص", if (calendar.online()) AurumColors.Green else AurumColors.Gold) }) {
            Text("● نشان قرمز = رویداد پراثر · زمان به وقت گوشی · آخرین دریافت ${relativeTime(calendar.checkedAt)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            calendar.error?.let { Text("تقویم در دسترس نیست: $it · نبود داده به معنی نبود رویداد نیست",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            val now = System.currentTimeMillis()
            val upcoming = if (calendar.online(now)) calendar.events.filter { it.at in (now - 2 * 3_600_000L)..(now + 7 * 86_400_000L) }.take(90)
                else emptyList()
            if (upcoming.isEmpty()) Text("رویدادِ قابل نمایش در بازهٔ پیشِ رو دریافت نشده؛ وضعیت خبر برای ورود تأیید نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            upcoming.forEach { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).background(
                        if (event.impact == "High") AurumColors.Red else AurumColors.TextMuted,
                        RoundedCornerShape(2.dp)))
                    Text("${event.country} · ${formatDateTime(event.at)} · ${event.title}" +
                        (if (event.impact == "High") " · پراثر" else ""),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (event.impact == "High") AurumColors.Red else AurumColors.TextSecondary)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refreshForexCalendar, enabled = !calendar.loading, modifier = Modifier.weight(1f)) {
                    Text(if (calendar.loading) "دریافت…" else "بررسی تقویم")
                }
                OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://www.forexfactory.com/calendar") } },
                    modifier = Modifier.weight(1f)) { Text("وب‌سایت منبع") }
            }
            Text("برای طلا، سرور از ۳۰ دقیقه پیش تا ۴۵ دقیقه پس از رویداد پراثر USD ورود جدید را متوقف می‌کند؛ قطع تقویم نیز UNKNOWN است. تقویم عمومی ممکن است کامل نباشد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        SectionCard("شرط نهم · تحلیل خودکار خبر با AI", "فقط XAU/USD · نتیجهٔ مدلِ سرور؛ قواعد کلیدواژه‌ای AI محسوب نمی‌شوند") {
            val ready = state.ai.status == "AVAILABLE" && state.gate == NewsGate.CLEAR && !state.cached && !state.loading
            Text(if (ready) "جهت پیشنهادی مدل: ${state.ai.direction} · اطمینان ${state.ai.confidence.toInt()}٪"
                else "UNKNOWN · ${state.ai.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) AurumColors.Green else AurumColors.Gold)
            Text("مدل: ${state.ai.model ?: "فعال نیست"} · بررسی ${relativeTime(state.ai.checkedAt)} · برای ورود خودکار باید هشت شرط فنی، تطابق جهت، همهٔ منابع و کنترل خبر هم‌زمان معتبر باشند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            state.ai.evidenceIds.mapNotNull { id -> state.articles.singleOrNull { it.id == id } }.forEach { source ->
                Text("شاهد: ${source.source} · ${source.headline}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                source.link?.let { url ->
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                        Text("خبر در منبع", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (state.sources.isNotEmpty()) {
            SectionCard("وضعیت منبع‌های ناشر", "فقط تیتر، چکیدهٔ کوتاه، زمان و لینک خودِ ناشر") {
                state.sources.forEach { source ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${source.name}: ${if (source.state == "online") "دریافت شد" else "ناموجود"}",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = if (source.state == "online") AurumColors.Green else AurumColors.Red)
                        if (source.feed.startsWith("https://")) {
                            OutlinedButton(onClick = { runCatching { uriHandler.openUri(source.feed) } }) {
                                Text("خوراک", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        if (state.articles.isEmpty()) {
            SectionCard("تیتر موجود نیست", "نبود اتصال یا توقف منبع به معنی نبود خبر نیست") {
                Text("نشانی HTTPS بک‌اند را در تنظیمات وارد کنید؛ فیدهای عمومی شناخته‌شده روی سرور خوانده می‌شوند. برای استفادهٔ تجاری، شرایط هر ناشر را بررسی کنید.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            }
        }
        state.articles.forEach { item ->
            SectionCard(item.headline, "${item.source} · ${if (item.language == "en") "EN · زبان اصلی" else "FA"} · انتشار ${formatDateTime(item.publishedAt)}",
                trailing = { Pill(item.impact, if (item.impact == "HIGH") AurumColors.Red else AurumColors.TextMuted) }) {
                if (item.summary.isNotBlank()) Text(item.summary,
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                Text("برچسب قاعده‌ای برای اثر احتمالی بر طلا (نه سیگنال): ${item.direction} · ${item.analysisSource}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                    modifier = Modifier.padding(top = 5.dp))
                item.link?.let { url ->
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = Modifier.padding(top = 5.dp)) {
                        Text("باز کردن خبر در منبع")
                    }
                }
            }
        }
    }
}
