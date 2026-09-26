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
fun IranPricesScreen(viewModel: AurumViewModel, onOpenSettings: (() -> Unit)? = null) {
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
        SectionCard("طلا، سکه و دلار", "TGJU: جدول وب · Navasan: آینهٔ عمومی با زمان قیمت مستقل") {
            Text("ارقام ریالی TGJU ÷۱۰ و به تومان نمایش داده می‌شوند. قیمت دریافت‌شده لزوماً تأیید دومنبعی نیست: ساعت TGJU تاریخ کامل ندارد؛ Navasan هم ممکن است قدیمی باشد. تعطیلی بازار از این داده‌ها قابل اثبات نیست؛ این تابلو مجوز معامله نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshWatch, enabled = !state.refreshing,
                    modifier = Modifier.weight(1f)) { Text("دریافت دوباره") }
                if (onOpenSettings != null) {
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("منابع") }
                }
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
            val displayAssessment = SourceComparison.assess(symbol, display.sourceId, quote, now)
            val tone = when (verified.status) {
                VerificationStatus.CONFIRMED -> AurumColors.Green
                VerificationStatus.CONFLICT -> AurumColors.Red
                VerificationStatus.UNVERIFIED -> AurumColors.Gold
                VerificationStatus.NO_DATA -> AurumColors.TextMuted
            }
            SectionCard("${symbol.label}", "${source?.title ?: "بدون منبع"}${if (display.fallback) " · جایگزین نمایشی" else ""}",
                trailing = { Pill(verified.badge, tone) }) {
                Text(quote?.price?.let { "${formatPrice(it)} تومان" } ?: "قیمت موجود نیست",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (displayAssessment.readable) AurumColors.TextPrimary else AurumColors.TextMuted)
                Text(buildString {
                    append("وضعیت قیمت نمایشی: ${displayAssessment.detail}")
                    if (quote?.providerAt != null) append(" · زمان قیمت ${formatDateTime(quote.providerAt)}")
                    if (quote != null) append(" · دریافت ${formatDateTime(quote.ts)}")
                }, style = MaterialTheme.typography.labelSmall,
                    color = if (displayAssessment.readable) AurumColors.Gold else AurumColors.TextMuted)
                Text("راستی‌آزمایی: ${verified.reason}" + (verified.spreadPct?.let { " · اختلاف ${String.format("%.2f", it)}%" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = tone,
                    modifier = Modifier.padding(top = 3.dp))
                selected.enabledSources.forEach { sourceId ->
                    val other = quotes[sourceId]
                    val assessment = SourceComparison.assess(symbol, sourceId, other, now)
                    val shown = other?.let { q -> q.price?.takeIf { q.sourceId == sourceId && q.unit == symbol.unit && it.isFinite() && it > 0 } }
                    Text(buildString {
                        append("${SourceCatalog.find(sourceId)?.title ?: sourceId}: ${formatPrice(shown)} تومان · ${assessment.detail}")
                        if (other?.providerAt != null) append(" · زمان قیمت ${formatDateTime(other.providerAt)}")
                        if (other != null) append(" · دریافت ${formatDateTime(other.ts)}")
                        if (other?.error != null) append(" · ${other.error}")
                    }, style = MaterialTheme.typography.labelSmall,
                        color = if (assessment.verifiable) AurumColors.Green else AurumColors.TextSecondary,
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
