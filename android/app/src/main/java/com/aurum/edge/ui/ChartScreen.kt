package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.Interval
import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.IctRangeAnalyzer
import com.aurum.edge.ui.components.EmptyState
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun ChartScreen(viewModel: AurumViewModel, market: MarketState, onOpenSettings: () -> Unit,
                onOpenJournal: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    var crosshair by remember { mutableStateOf<Candle?>(null) }

    if (!settings.hasKey) {
        KeyOnboarding(onSave = viewModel::saveApiKey, onOpenSettings = onOpenSettings)
        return
    }
    if (market.candles.isEmpty()) {
        EmptyState(
            title = "هیچ کندل واقعی‌ای در دسترس نیست",
            message = market.feed.detail.ifBlank {
                "در حال تلاش برای دریافت داده واقعی از Twelve Data. این اپ در نبود اینترنت هیچ داده ساختگی نمی‌سازد."
            },
        )
        return
    }

    val last = market.candles.last()
    val shown = crosshair ?: last
    val previousClose = market.candles.dropLast(1).lastOrNull()?.close
    val change = previousClose?.let { shown.close - it }
    val structure = remember(market.candles, market.interval) {
        IctRangeAnalyzer.analyze(market.candles, market.interval)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Interval.entries.forEach { interval ->
                FilterChip(
                    selected = market.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                    label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        CandleChart(
            candles = market.candles,
            interval = market.interval,
            signal = market.signal,
            structure = structure,
            onCrosshairChange = { crosshair = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .padding(horizontal = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Legend("Tenkan", AurumColors.Cyan)
            Legend("Kijun", AurumColors.Purple)
            Legend("VWAP", AurumColors.Gold)
            Legend("EMA200", AurumColors.TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Legend("S حمایت", AurumColors.Green)
            Legend("R مقاومت", AurumColors.Red)
            Legend("FVG", AurumColors.Cyan)
            Legend("OB احتمالی", AurumColors.Purple)
        }

        SectionCard(
            title = "حمایت/مقاومت و پرایس‌اکشن ICT",
            subtitle = "تقریب آموزشی بر پایهٔ OHLC بسته؛ خطوط، سفارش یا معاملهٔ ثبت‌شده نیستند",
        ) {
            val level = structure.range
            if (level == null) {
                Text("رنجِ دوطرفهٔ تأییدشده یافت نشد؛ سطح قابل اتکا ترسیم/استفاده نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            } else {
                Text("حمایت ${formatPrice(level.support)} (${level.supportTouches} برخورد جداگانه) · مقاومت ${formatPrice(level.resistance)} (${level.resistanceTouches} برخورد جداگانه)",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Purple)
                Text("میانهٔ رنج ${formatPrice(level.midpoint)}؛ لمس حمایت یا خرید در میانهٔ رنج، مجوز ورود نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            }
            val selected = listOf(structure.buy, structure.sell).maxByOrNull { it.sweepAt ?: 0L }
            Text("پنجره: ${structure.window?.label ?: "داده/بازهٔ ناکافی"} · ${structure.window?.localTime ?: "—"} به وقت نیویورک (ساعت رویدادها: دستگاه)",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("BUY: ${ictStatus(structure.buy.state)} · SELL: ${ictStatus(structure.sell.state)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (structure.buy.ready || structure.sell.ready) AurumColors.Gold else AurumColors.TextSecondary)
            selected?.takeIf { it.sweepAt != null }?.let { setup ->
                Text("${if (setup.side == IctRangeAnalyzer.Side.BUY) "کف" else "سقف"} جاروب/بازپس‌گرفته: ${setup.sweepAt?.let(::formatTime)} · شکست ساختار MSS: ${setup.shiftAt?.let(::formatTime) ?: "هنوز نه"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                Text("FVG سه‌کندلی: ${setup.fvg?.let { "${formatPrice(it.low)}–${formatPrice(it.high)}" } ?: "تأیید نشده"} · اردربلاک احتمالی: ${setup.orderBlock?.let { "${formatPrice(it.low)}–${formatPrice(it.high)}" } ?: "یافت نشد"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                Text("بازآزمایی: ${setup.retestAt?.let(::formatTime) ?: "هنوز نه"} · پاداش/ریسک تا سمت مقابل: ${setup.rewardRisk?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            if (market.showingCachedData || market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING)) {
                Text("نمایش تحلیل تاریخی/کش؛ ورود یا اعلان زنده از آن مجاز نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            Text("حتی «آماده» فقط یک الگوی تقریبی است؛ ورود خودکار paper به قیمت زنده، ۹/۹ از جمله خبر AI، و گیت رنجِ همین کندل نیاز دارد. معاملهٔ واقعی وجود ندارد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("O ${formatPrice(shown.open)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("H ${formatPrice(shown.high)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("L ${formatPrice(shown.low)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text(
                "C ${formatPrice(shown.close)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (change != null && change < 0) AurumColors.Red else AurumColors.Green,
            )
            Text(formatTime(shown.time), style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        SignalSummaryCard(
            signal = market.signal,
            onOpenPaperTrade = { market.signal?.let(viewModel::openPaperTrade) },
        )

        SectionCard("معاملهٔ ثبت‌شده یا فقط خطوط سیگنال؟", "خط‌های «طرح ورود/SL/TP» معامله نیستند و به‌تنهایی ژورنال نمی‌سازند") {
            val sameSymbol = trades.filter { it.symbol == market.symbol }
            val openCount = sameSymbol.count { it.isOpen }
            Text("${sameSymbol.count { !it.isOpen }} معاملهٔ کاغذی بسته · $openCount باز، ثبت‌شده در ژورنال برای ${market.symbol}",
                style = MaterialTheme.typography.bodySmall,
                color = if (sameSymbol.isEmpty()) AurumColors.Gold else AurumColors.Green)
            if (settings.autoPaperTrading) {
                Text("خودکار کاغذی: $autoStatus", style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary)
            } else {
                Text("خودکار خاموش است؛ با تأیید خودت در تنظیمات می‌توانی ورود خودکار کاغذی ۹/۹ را روشن کنی. سفارش واقعی وجود ندارد.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            sameSymbol.firstOrNull()?.let { trade ->
                Text("آخرین ثبت: ${trade.id.take(8)} · ${if (trade.autoOpened) "خودکار کاغذی" else "کاغذی"} · ${if (trade.isOpen) "باز" else "بسته"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            }
            Button(onClick = onOpenJournal, modifier = Modifier.padding(top = 5.dp)) {
                Text("دیدن رکوردهای ژورنال")
            }
        }

        SectionCard(
            title = "وضعیت دیتا",
            subtitle = "فقط منبع واقعی — بدون هیچ fallback ساختگی",
        ) {
            Text("منبع: ${market.feed.provider}", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("حالت: ${market.feed.mode.label}", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            Text(
                "کندل‌های واقعی دریافت‌شده: ${market.candles.size} (بسته: ${market.closedCount})",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "کندل جاری: ${formatTime(last.time)} · آخرین قیمت واقعی: ${formatPrice(market.lastPrice)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "نکته: اگر اینترنت قطع شود، همان کندل‌های واقعیِ ذخیره‌شده نمایش داده می‌شود و اپ هرگز قیمت مصنوعی نمی‌سازد.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun ictStatus(state: IctRangeAnalyzer.State): String = when (state) {
    IctRangeAnalyzer.State.INVALID_DATA -> "داده/بازه ناکافی یا نامعتبر"
    IctRangeAnalyzer.State.NO_RANGE -> "رنج تأیید نشده"
    IctRangeAnalyzer.State.WAIT_SWEEP -> "در انتظار جاروب و بازپس‌گیری"
    IctRangeAnalyzer.State.BROKEN_RANGE -> "خروج از محدوده"
    IctRangeAnalyzer.State.WAIT_MSS -> "در انتظار شکست ساختار با حرکت قوی"
    IctRangeAnalyzer.State.WAIT_FVG -> "در انتظار FVG"
    IctRangeAnalyzer.State.WAIT_RETEST -> "در انتظار بازآزمایی نزدیک لبهٔ رنج"
    IctRangeAnalyzer.State.OUTSIDE_SESSION -> "بیرون جلسهٔ مجاز"
    IctRangeAnalyzer.State.POOR_REWARD_RISK -> "فضای ناکافی تا سطح مقابل"
    IctRangeAnalyzer.State.READY -> "الگوی تأییدشده؛ نه مجوز معامله"
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .background(color, androidx.compose.foundation.shape.CircleShape)
                .padding(3.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}

@Composable
fun KeyOnboarding(onSave: (String) -> Unit, onOpenSettings: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        SectionCard(
            title = "برای شروع، کلید دیتای واقعی لازم است",
            subtitle = "اپ فقط با دیتای واقعی کار می‌کند و بدون کلید چیزی نمایش نمی‌دهد",
        ) {
            Text(
                "کلید رایگان Twelve Data را از twelvedata.com دریافت کن و اینجا وارد کن. این کلید فقط برای خواندن دیتای بازار است و دسترسی معاملاتی ندارد.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Twelve Data API Key") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Button(
                onClick = { onSave(key) },
                enabled = key.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Text("ذخیره و دریافت دیتای واقعی", fontWeight = FontWeight.Bold)
            }
            Text(
                "بدون اینترنت یا بدون کلید معتبر، وضعیت «آفلاین» نمایش داده می‌شود — هیچ کندل یا سیگنال ساختگی ساخته نمی‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("تنظیمات پیشرفته")
        }
    }
}
