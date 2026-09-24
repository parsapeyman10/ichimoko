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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.Quote
import com.aurum.edge.data.QuoteDisplayState
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

/** Separate read-only watchlist and publisher web news. No quote is sent as an order. */
@Composable
fun MarketWatchScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = page == 0, onClick = { page = 0 }, label = { Text("منابع بازار") })
            FilterChip(selected = page == 1, onClick = { page = 1 }, label = { Text("طلا و ارز") })
            FilterChip(selected = page == 2, onClick = { page = 2 }, label = { Text("اخبار وب") })
        }
        when (page) {
            1 -> IranPricesScreen(viewModel, onOpenSettings)
            2 -> PersianNewsScreen(viewModel, onOpenSettings)
            else -> WatchPricesScreen(viewModel, onOpenSettings)
        }
    }
}

@Composable
private fun WatchPricesScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.watch.collectAsStateWithLifecycle()
    val selections by viewModel.watchSettings.collectAsStateWithLifecycle()
    val history by viewModel.watchHistory.collectAsStateWithLifecycle()
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
        SectionCard("دیده‌بان چندمنبعی", "قیمت نمایشی ≠ تأیید دومنبعی؛ زمان دریافت وب جای زمان قیمت ناشر را نمی‌گیرد") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshWatch, enabled = !state.refreshing, modifier = Modifier.weight(1f)) {
                    Text(if (state.refreshing) "در حال دریافت…" else "دریافت دوباره")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("منابع نمادها") }
            }
            if (state.refreshing) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), strokeWidth = 2.dp)
            Text(
                "آخرین تلاش: ${relativeTime(state.lastAttemptAt, now)} · فقط مشاهدات واقعی پس از فعال‌سازی در تاریخچه ذخیره می‌شوند. «تأیید» تضمین صحت یا مجوز سفارش نیست.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.error?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall) }
        }
        WatchCatalog.symbols.forEach { symbol ->
            val selected = selections[symbol.id] ?: return@forEach
            val quotes = state.quotes[symbol.id].orEmpty()
            val verification = SourceComparison.verify(symbol, selected.enabledSources, quotes, now)
            val display = WatchDisplay.choose(symbol, selected, quotes, now)
            val preferred = display.quote
            val tone = when (verification.status) {
                VerificationStatus.CONFIRMED -> AurumColors.Green
                VerificationStatus.CONFLICT -> AurumColors.Red
                VerificationStatus.UNVERIFIED -> AurumColors.Gold
                VerificationStatus.NO_DATA -> AurumColors.TextMuted
            }
            SectionCard(
                title = "${symbol.label} · ${symbol.id}",
                subtitle = "منبع نمایشی: ${SourceCatalog.find(display.sourceId)?.title ?: "انتخاب نشده"}${if (display.fallback) " · جایگزین" else ""} · ${symbol.unit}",
                trailing = { Pill(verification.badge, tone) },
            ) {
                Text(
                    preferred?.price?.let { "${formatPrice(it)} ${symbol.unit}" } ?: "قیمت موجود نیست",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (SourceComparison.assess(symbol, display.sourceId, preferred, now).readable)
                        AurumColors.TextPrimary else AurumColors.TextMuted,
                )
                Text(
                    verification.reason + (verification.spreadPct?.let { " · اختلاف ${String.format("%.2f", it)}%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = tone,
                    modifier = Modifier.padding(top = 4.dp, bottom = 7.dp),
                )
                if (selected.enabledSources.isEmpty()) {
                    Text("منبعی برای این نماد فعال نیست؛ از تنظیمات انتخاب کنید.", color = AurumColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                selected.enabledSources.forEach sourceLoop@{ sourceId ->
                    val source = SourceCatalog.find(sourceId) ?: return@sourceLoop
                    val quote = quotes[sourceId]
                    val assessment = SourceComparison.assess(symbol, sourceId, quote, now)
                    val shown = quote?.let { q -> q.price?.takeIf { q.sourceId == sourceId && q.unit == symbol.unit && it.isFinite() && it > 0 } }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${source.title}: ${formatPrice(shown)} ${symbol.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (assessment.readable) AurumColors.TextPrimary else AurumColors.TextMuted)
                            Text(buildString {
                                append(assessment.detail)
                                if (assessment.state == QuoteDisplayState.ERROR && quote?.error != null) append(" · ${quote.error}")
                                if (quote?.providerAt != null) append(" · زمان قیمت ${formatDateTime(quote.providerAt)}")
                                if (quote != null) append(" · دریافت ${formatDateTime(quote.ts)}")
                            }, style = MaterialTheme.typography.labelSmall,
                                color = when (assessment.state) {
                                    QuoteDisplayState.DATED -> AurumColors.Green
                                    QuoteDisplayState.UNDATED -> AurumColors.Gold
                                    else -> AurumColors.TextMuted
                                })
                        }
                        OutlinedButton(onClick = { viewModel.showWatchHistory(symbol.id, sourceId) }) {
                            Text("تاریخچه", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (history.symbolId == symbol.id && history.sourceId == sourceId) {
                        Text("${history.total} مشاهدهٔ ذخیره‌شده · جدیدترین ابتدا", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                        history.entries.forEach { entry -> HistoryRow(entry) }
                        if (history.loading) Text("در حال خواندن تاریخچه…", color = AurumColors.TextMuted)
                        if (!history.loading && history.entries.size < history.total) {
                            OutlinedButton(onClick = viewModel::moreWatchHistory) { Text("مشاهدات قدیمی‌تر") }
                        }
                    }
                }
            }
        }
        SectionCard("منابع محدود", "وضعیت شفاف اتصال‌های دیگر") {
            Text("TSETMC: اتصال رسمی و شناسهٔ نماد تأیید نشده؛ هیچ قیمت بورسی از آن نمایش داده نمی‌شود. Nobitex/MT5: سفارش واقعی در اپ فعال نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
    }
}

@Composable
private fun HistoryRow(quote: Quote) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDateTime(quote.ts), style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        Text("${formatPrice(quote.price)} ${quote.unit}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
    }
}
