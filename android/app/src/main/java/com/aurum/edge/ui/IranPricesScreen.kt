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
import androidx.compose.material3.CircularProgressIndicator
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
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchDisplay
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/** Read-only Iranian board. A time-only HTML row cannot approve a trade. */
@Composable
fun IranPricesScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.watch.collectAsStateWithLifecycle()
    val selections by viewModel.watchSettings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        viewModel.refreshWatch()
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
            if (now - (state.lastAttemptAt ?: 0L) >= 180_000L) viewModel.refreshWatch()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("طلا، سکه و دلار", "TGJU: جدول وب · آینهٔ عمومی Navasan: مسیر جایگزین (به‌روزرسانی تقریبی ۳۰ دقیقه)") {
            Text("همهٔ اعداد ریالی TGJU ÷۱۰ و با واحد تومان نمایش داده می‌شوند. ساعتِ ردیف TGJU تاریخ کامل ندارد؛ دریافت صفحه تأیید تازگی معامله یا اجازهٔ سفارش نیست. هر نماد و منبع جداگانه برچسب می‌خورد.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshWatch, enabled = !state.refreshing,
                    modifier = Modifier.weight(1f)) { Text("دریافت دوباره") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("منابع") }
            }
            if (state.refreshing) CircularProgressIndicator(modifier = Modifier.padding(top = 6.dp))
            Text("آخرین تلاش: ${relativeTime(state.lastAttemptAt, now)} · قطع اینترنت = نمایش کش با زمان اصلی، نه قیمت آنلاین.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
            state.error?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall) }
        }
        WatchCatalog.symbols.filter { it.id.endsWith("/IRT") }.forEach { symbol ->
            val selected = selections[symbol.id] ?: return@forEach
            val quotes = state.quotes[symbol.id].orEmpty()
            val display = WatchDisplay.choose(symbol, selected, quotes, now)
            val quote = display.quote
            val verified = SourceComparison.verify(symbol, selected.enabledSources, quotes, now)
            val source = SourceCatalog.find(display.sourceId)
            val healthy = quote != null && quote.price != null && quote.error == null && !quote.stale &&
                quote.ts in (now - symbol.maxAgeMillis)..(now + 60_000L) &&
                (quote.providerAt == null || quote.providerAt in (now - symbol.maxAgeMillis)..(now + 60_000L))
            SectionCard("${symbol.label}", "${source?.title ?: "بدون منبع"}${if (display.fallback) " · جایگزین خودکار" else ""}",
                trailing = { Pill(if (healthy) "دریافت شد" else "قدیمی/ناموجود",
                    if (healthy) AurumColors.Gold else AurumColors.Red) }) {
                Text(quote?.price?.let { "${formatPrice(it)} تومان" } ?: "قیمت موجود نیست",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (healthy) AurumColors.TextPrimary else AurumColors.TextMuted)
                Text(when {
                    quote == null -> "هنوز دریافت نشده"
                    quote.error != null -> "${quote.error} · مشاهدهٔ قبلی: ${formatDateTime(quote.ts)}"
                    !healthy -> "کش/قدیمی · آخرین دریافت: ${formatDateTime(quote.ts)}"
                    quote.providerAt == null -> "وبِ TGJU خوانده شد؛ تاریخ کاملِ آخرین معامله منتشر نشده · دریافت: ${formatDateTime(quote.ts)}"
                    else -> "زمان قیمت منبع: ${formatDateTime(quote.providerAt)} · دریافت: ${formatDateTime(quote.ts)}"
                }, style = MaterialTheme.typography.labelSmall,
                    color = if (healthy) AurumColors.Gold else AurumColors.Red)
                Text("راستی‌آزمایی دو منبع: ${verified.status.name} · ${verified.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (verified.status == VerificationStatus.CONFIRMED) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 3.dp))
                selected.enabledSources.forEach { sourceId ->
                    val other = quotes[sourceId]
                    Text("${SourceCatalog.find(sourceId)?.title ?: sourceId}: ${formatPrice(other?.price)} تومان" +
                        (other?.error?.let { " · $it" } ?: if (other?.stale == true) " · کش‌شده" else ""),
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp))
                }
                symbol.providerCodes[SourceCatalog.tgju.id]?.let { code ->
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://www.tgju.org/profile/$code") } },
                        modifier = Modifier.padding(top = 5.dp)) { Text("منبع TGJU") }
                }
            }
        }
        Text("این تابلو فقط برای مشاهده است؛ قیمت آن به فید XAU/USD، ژورنال یا اجرای واقعی تزریق نمی‌شود.",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}
