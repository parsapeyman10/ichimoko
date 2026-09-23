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
import com.aurum.edge.data.CryptoScanStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun CryptoScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.crypto.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(settings.newsBaseUrl) { viewModel.refreshCrypto() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        NobitexTrainingSection(viewModel)

        SectionCard(
            title = "رادار رمزارز · فقط نامزدهای غربالگری",
            subtitle = "دادهٔ واقعی CoinGecko + Binance SPOT؛ نه پیش‌بینی پامپ و نه توصیه/سفارش خرید",
            trailing = { Pill(if (state.status == CryptoScanStatus.ONLINE) {
                if (state.cached) "نتیجهٔ کش‌شده" else "دادهٔ تازه"
            } else "نامعتبر/در انتظار",
                if (state.status == CryptoScanStatus.ONLINE) AurumColors.Green else AurumColors.Gold) },
        ) {
            Text("فقط ۲۰۰ دارایی اول CoinGecko بررسی و حداکثر ۱۲ نماد با دادهٔ مستقل Binance تأیید می‌شود. نتیجهٔ خالی، ادعای نبود فرصت در کل بازار نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("وضعیت: ${state.provider} · ${formatDateTime(state.checkedAt)} · نمونه ${state.scanned} · پیش‌گزین ${state.preselected} · عبوری ${state.candidates.size}",
                modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            state.error?.let { Text(it, modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshCrypto, enabled = state.status != CryptoScanStatus.LOADING,
                    modifier = Modifier.weight(1f)) {
                    Text(if (state.status == CryptoScanStatus.LOADING) "در حال بررسی…" else "بررسی دوباره")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("تنظیم سرور") }
            }
            Text("فقط تحلیل خواندنی است. شورت در Spot پشتیبانی نمی‌شود؛ لانگ/شورت این اپ فقط در ژورنال کاغذی ثبت می‌شوند.",
                modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        }

        when (state.status) {
            CryptoScanStatus.UNCONFIGURED -> SectionCard("اتصال لازم است", "هیچ نماد ساختگی نمایش داده نمی‌شود") {
                Text("یک سرور HTTPS با بک‌اند پروژه راه‌اندازی و نشانی آن را در تنظیمات ثبت کنید. دادهٔ CoinGecko عمومی ممکن است محدود شود؛ کلید Demo را فقط روی سرور تنظیم کنید.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
            CryptoScanStatus.LOADING -> SectionCard("در حال راستی‌آزمایی", "ابتدا بازار، سپس دفتر سفارش و کندل‌های بسته بررسی می‌شوند") {
                Text("نتیجهٔ قبلی هنگام تازه‌سازی قابل اتکا نیست؛ تا دریافت پاسخ، نامزدی نمایش داده نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            }
            CryptoScanStatus.UNAVAILABLE -> SectionCard("دادهٔ معتبر موجود نیست", "خطای شبکه یا منبع، معادل «هیچ نامزدی نیست» نیست") {
                Text("اتصال بک‌اند، محدودیت دسترسی CoinGecko و Binance و زمان دستگاه را بررسی کنید. خروجی قبلی عمداً پنهان شده است.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            }
            CryptoScanStatus.ONLINE -> {
                if (state.candidates.isEmpty()) {
                    SectionCard("نامزدی از فیلترها عبور نکرد", "فقط در دامنه و زمان ذکرشده") {
                        Text("فیلترهای نقدشوندگی، عرضه، شتاب حجم، قیمت مستقل و تازگی سخت‌گیرانه‌اند؛ ممکن است هیچ موردی مطابق نباشد.",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                    }
                }
                state.candidates.forEach { item ->
                    SectionCard("${item.name} · ${item.symbol}", "CoinGecko + Binance Spot · نه سیگنال معامله",
                        trailing = { Pill("نامزد", AurumColors.Cyan) }) {
                        Text("قیمت Binance: ${formatPrice(item.priceUsd)} USD · ارزش بازار: ${formatPrice(item.marketCapUsd / 1_000_000)}M USD",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        Text("حجم ۲۴ساعت: ${formatPrice(item.volume24hUsd / 1_000_000)}M USD (CG) · ${formatPrice(item.binanceVolumeUsdt / 1_000_000)}M USDT (Binance)",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                        Text("تغییر ۱ساعت ${String.format("%.2f", item.change1hPct)}٪ · ۲۴ساعت ${String.format("%.2f", item.change24hPct)}٪ · ۷روز ${String.format("%.2f", item.change7dPct)}٪",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                        Text("۳ساعت بسته ${String.format("%.2f", item.change3hPct)}٪ · جهش حجم ${String.format("%.2f", item.volumeRatio3h)}× · خرید تیکر ${(item.takerBuyRatio3h * 100).toInt()}٪",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                        Text("اسپرد ${String.format("%.3f", item.spreadPct)}٪ · سهم عرضه ${(item.supplyRatio * 100).toInt()}٪",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Text("شناسهٔ دارایی + جفت Binance با CoinGecko تأیید شد: ${formatDateTime(item.coingeckoPairAt)}",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
                            modifier = Modifier.padding(top = 4.dp))
                        Text("زمان بازار: CoinGecko ${formatDateTime(item.coingeckoAt)} · Binance ${formatDateTime(item.binanceAt)} · آخرین کندل بسته ${formatDateTime(item.candleAt)}",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                            modifier = Modifier.padding(top = 4.dp))
                        item.link?.let { url ->
                            OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } },
                                modifier = Modifier.padding(top = 6.dp)) { Text("اطلاعات منبع CoinGecko") }
                        }
                    }
                }
            }
        }

        SectionCard("شرط‌های ثابت و قابل بررسی", "همه باید برقرار باشند؛ نبود داده = رد، نه تخمین") {
            if (state.filters.isEmpty()) Text("پس از پاسخ معتبر سرور نمایش داده می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            state.filters.forEach { (name, rule) ->
                Text("• $name: $rule", modifier = Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
            Text("قیمت و حجم گذشته احتمال پامپ بعدی را اندازه نمی‌گیرند؛ دستکاری، ریسک نقدشوندگی و زیان ممکن است.",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold)
        }
    }
}
