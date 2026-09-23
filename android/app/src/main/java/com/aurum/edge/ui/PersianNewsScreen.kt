package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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

@Composable
fun PersianNewsScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.news.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(settings.newsBaseUrl) { viewModel.refreshNews() }
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
            Text("توقف بر پایهٔ خبر برای ورودهای کاغذی ${if (settings.pauseOnNews) "روشن" else "خاموش"} است. قطع حتی یکی از خوراک‌ها وضعیت را UNKNOWN می‌کند. CLEAR تقویم کامل یا اجازهٔ سفارش واقعی نیست.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp))
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
