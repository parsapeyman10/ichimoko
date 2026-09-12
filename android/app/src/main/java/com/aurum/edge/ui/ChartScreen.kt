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
import com.aurum.edge.core.Interval
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.EmptyState
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun ChartScreen(viewModel: AurumViewModel, market: MarketState, onOpenSettings: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
