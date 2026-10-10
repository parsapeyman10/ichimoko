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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.Quote
import com.aurum.edge.data.QuoteDisplayState
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchDisplay
import com.aurum.edge.data.WatchSelection
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

@Composable
fun MarketWatchScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit,
                      onOpenChart: (String) -> Unit) {
    val state by viewModel.watch.collectAsStateWithLifecycle()
    val selections by viewModel.watchSettings.collectAsStateWithLifecycle()
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
        SectionCard("دیده‌بان بازار", "طلای ایران و دلار ایران بالای لیست؛ بعد طلا و جفت‌ارزهای فارکس · بدون API Key اجباری") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshWatch, enabled = !state.refreshing, modifier = Modifier.weight(1f)) {
                    Text(if (state.refreshing) "در حال دریافت…" else "به‌روزرسانی")
                }
                OutlinedButton(onClick = onOpenSettings) { Text("تنظیمات منبع") }
            }
            if (state.refreshing) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), strokeWidth = 2.dp)
            Text(
                "آخرین تلاش: ${relativeTime(state.lastAttemptAt, now)} · منابع عمومی: TGJU برای ایران و Yahoo Finance برای فارکس/طلا.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.error?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall) }
        }

        WatchCatalog.symbols.forEach { symbol ->
            val selected = selections[symbol.id] ?: WatchSelection(symbol.defaultSources, symbol.defaultSources.firstOrNull().orEmpty())
            val quotes = state.quotes[symbol.id].orEmpty()
            val verification = SourceComparison.verify(symbol, selected.enabledSources, quotes, now)
            val display = WatchDisplay.choose(symbol, selected, quotes, now)
            val quote = display.quote
            val sourceTitle = SourceCatalog.find(display.sourceId)?.title ?: "منبع عمومی"
            val assessment = SourceComparison.assess(symbol, display.sourceId, quote, now)
            val tone = when {
                assessment.readable -> AurumColors.Green
                verification.status == VerificationStatus.CONFLICT -> AurumColors.Red
                else -> AurumColors.TextMuted
            }
            val badge = if (assessment.readable) "یک منبع معتبر" else verification.badge
            SectionCard(
                title = "${symbol.label} · ${symbol.id}",
                subtitle = "منبع: $sourceTitle${if (display.fallback) " · جایگزین" else ""}",
                trailing = { Pill(badge, tone) },
            ) {
                QuoteMainLine(quote, symbol.unit, tone)
                if (symbol.id in WatchCatalog.chartSymbols) {
                    OutlinedButton(onClick = { onOpenChart(symbol.id) }) {
                        Text("چارت و سیگنال ${symbol.id}")
                    }
                }
                Text(
                    quote?.changePct?.let { change -> "تغییر: ${String.format(java.util.Locale.US, "%.2f", change)}٪" }
                        ?: "تغییر روزانه از منبع دریافت نشد",
                    style = MaterialTheme.typography.labelSmall,
                    color = quote?.changePct?.let { if (it >= 0) AurumColors.Green else AurumColors.Red } ?: AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    when (assessment.state) {
                        QuoteDisplayState.DATED -> "زمان قیمت ناشر: ${formatDateTime(quote?.providerAt)}"
                        QuoteDisplayState.UNDATED -> "پاسخ تازه خوانده شد، ولی ساعت دقیق ناشر برای این ردیف کامل نیست"
                        QuoteDisplayState.OLD -> "قیمت قدیمی است"
                        QuoteDisplayState.ERROR -> quote?.error ?: assessment.detail
                        else -> assessment.detail
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuoteMainLine(quote: Quote?, unit: String, tone: androidx.compose.ui.graphics.Color) {
    Text(
        quote?.price?.let { "${formatPrice(it)} $unit" } ?: "قیمت موجود نیست",
        style = MaterialTheme.typography.titleLarge,
        color = tone,
        fontWeight = FontWeight.Bold,
    )
}
