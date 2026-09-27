package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.CryptoFundamentalStatus
import com.aurum.edge.data.CryptoScanStatus
import com.aurum.edge.data.NobitexCatalogStatus
import com.aurum.edge.data.PublicCryptoStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun CryptoScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.crypto.collectAsStateWithLifecycle()
    val publicFeed by viewModel.publicCrypto.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var baseUrl by remember { mutableStateOf(settings.cryptoBaseUrl) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.cryptoBaseUrl) { baseUrl = settings.cryptoBaseUrl; viewModel.refreshCrypto() }
    LaunchedEffect(Unit) {
        var elapsed = 0
        while (true) {
            if (elapsed % 6 == 0) viewModel.refreshPublicCrypto() // one public request per ~3m
            delay(30_000L)
            now = System.currentTimeMillis()
            elapsed++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        ReadOnlyMonitorCard(viewModel, settings)
        SectionCard("نمای جهانی بی‌کلید", "CoinGecko عمومی · ۲۰ دارایی برتر · فقط یک منبع") {
            Text("قیمت USD و حجم از CoinGecko است؛ قیمت Binance، دفتر سفارش، جفت قابل معامله و احتمال رشد در این نما تأیید نشده‌اند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text(when (publicFeed.status) {
                PublicCryptoStatus.IDLE -> "هنوز درخواستی انجام نشده است"
                PublicCryptoStatus.LOADING -> "در حال دریافت؛ دادهٔ قبلی نمایش داده نمی‌شود"
                PublicCryptoStatus.UNAVAILABLE -> "دادهٔ عمومی ناموجود: ${publicFeed.error ?: "خطای منبع"}"
                PublicCryptoStatus.OBSERVED -> "دریافت ${formatDateTime(publicFeed.receivedAt)} · ${if (publicFeed.recent(now)) "پاسخ اخیر؛ منبع تک‌گانه" else "پاسخ قبلی؛ قیمت تازه نیست"}"
            }, style = MaterialTheme.typography.bodySmall,
                color = if (publicFeed.recent(now)) AurumColors.Cyan else AurumColors.Gold)
            Button(onClick = viewModel::refreshPublicCrypto, enabled = publicFeed.status != PublicCryptoStatus.LOADING,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) { Text("دریافت عمومی CoinGecko") }
            publicFeed.quotes.take(10).forEach { quote ->
                val amount = if (quote.priceUsd < 0.01) String.format(Locale.US, "%.8f", quote.priceUsd)
                    .trimEnd('0').trimEnd('.') else formatPrice(quote.priceUsd)
                Text("${quote.code} · ${quote.name}: $amount USD · ۲۴ساعت ${formatPrice(quote.change24hPct)}٪",
                    style = MaterialTheme.typography.bodySmall, color = if (publicFeed.recent(now)) AurumColors.TextPrimary else AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 7.dp))
                Text("زمان قیمت منبع ${formatDateTime(quote.providerAt)} · گردش ۲۴ساعت ${formatPrice(quote.volume24hUsd / 1_000_000)}M USD",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
        SectionCard("غربال پیشرفتهٔ دو منبع · اختیاری", "سرور پژوهشی رمزارز؛ مستقل از سرور خبر فارکس") {
            Text("برای بررسی جفت دقیق، اسپرد و کندل بسته به بک‌اند HTTPS پروژه نیاز است؛ در نبود آن، فقط نمای عمومی بالا فعال است. کلید Demo CoinGecko را در APK نگذارید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it.take(240) },
                label = { Text("نشانی HTTPS سرور رمزارز (اختیاری)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { viewModel.saveCryptoBaseUrl(baseUrl) }) { Text("ذخیره/حذف نشانی سرور") }
        }
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
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("خبر کریپتو") }
            }
            Text("این فضا فقط پژوهش خواندنی دارد؛ نه سفارش، نه شورت Spot، نه دسترسی به ژورنال فارکس یا تمرین نوبیتکس.",
                modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        }

        when (state.status) {
            CryptoScanStatus.UNCONFIGURED -> SectionCard("اتصال لازم است", "هیچ نماد ساختگی نمایش داده نمی‌شود") {
                Text("برای غربال دو منبعی، نشانی سرور HTTPS رمزارز را در کارت بالا وارد کنید؛ تا آن زمان فقط رصد عمومی تک‌منبعی فعال است. کلید Demo صرفاً روی سرور می‌ماند.",
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

        CryptoFundamentalsSection(viewModel)
    }
}

@Composable
private fun CryptoFundamentalsSection(viewModel: AurumViewModel) {
    val state by viewModel.cryptoFundamentals.collectAsStateWithLifecycle()
    val catalog by viewModel.nobitexCatalog.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.ensureNobitexCatalog() }
    var query by remember { mutableStateOf("") }
    SectionCard("تحلیل بنیادی هر رمزارز نوبیتکس (فراتر از قیمت)", "همهٔ بازارهای تتری زندهٔ نوبیتکس؛ نه فقط چند نماد ثابت — امتیاز CoinGecko + سیگنال تکنیکال واقعی") {
        Text("سه امتیاز CoinGecko (توسعه‌دهنده/جامعه/نقدشوندگی؛ روش‌شناسی خودِ CoinGecko، نه ما) میانگین ساده گرفته می‌شود. " +
            "همزمان، همان SignalEngine ایچیموکو+همگرایی که ژورنال کاغذی نوبیتکس استفاده می‌کند، مستقلاً روی کندل ساعتی تازهٔ همان نماد اجرا می‌شود.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Text("این یک رتبه‌بندی سرمایه‌گذاری یا پیش‌بینی سود نیست؛ فقط مقایسهٔ چند منبع رسمی و واقعی، هرکدام با زمان دریافت خودش. " +
            "چون بازار نوبیتکس بیش از صد نماد دارد، هر بار فقط یک نماد بررسی می‌شود (~۵ ثانیه) نه یک اسکن ۲دقیقه‌ایِ همه‌چیز.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        OutlinedTextField(value = query, onValueChange = { query = it.take(15) }, singleLine = true,
            label = { Text("نماد را جست‌وجو کنید (مثلاً shib, pepe, btc)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        when (catalog.status) {
            NobitexCatalogStatus.LOADING, NobitexCatalogStatus.IDLE -> Text("در حال دریافت فهرست کامل بازارهای نوبیتکس…",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
            NobitexCatalogStatus.FAILED -> Text("دریافت فهرست بازارها ناموفق بود: " + (catalog.error ?: "خطا"),
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red, modifier = Modifier.padding(top = 6.dp))
            NobitexCatalogStatus.DONE -> {
                Text("${catalog.markets.size} بازار تتری زندهٔ نوبیتکس بارگذاری شد.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
                val matches = remember(query, catalog.markets) {
                    if (query.isBlank()) catalog.markets.take(8)
                    else catalog.markets.filter { it.market.srcCurrency.contains(query.trim(), ignoreCase = true) }.take(20)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    matches.forEach { entry ->
                        val busy = state.busyMarket == entry.market
                        OutlinedButton(onClick = { viewModel.analyzeCryptoFundamentals(entry.market) }, enabled = !busy) {
                            Text(if (busy) "…${entry.market.srcCurrency.uppercase()}" else entry.market.srcCurrency.uppercase())
                        }
                    }
                }
            }
        }
        if (state.status == CryptoFundamentalStatus.FAILED) Text("بررسی ناموفق: " + (state.error ?: "خطای منبع"),
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Red, modifier = Modifier.padding(top = 6.dp))
    }
    val ranked = remember(state.results) { state.results.values.sortedByDescending { it.compositeScore ?: -1.0 } }
    ranked.forEachIndexed { index, row ->
        val techColor = when (row.technical?.signal?.action) {
            SignalAction.BUY -> AurumColors.Green
            SignalAction.SELL -> AurumColors.Red
            else -> AurumColors.TextMuted
        }
        SectionCard("${index + 1}. ${row.name} · ${row.market.srcCurrency.uppercase()}/USDT", "CoinGecko rank ${row.marketCapRank ?: "—"}",
            trailing = { Pill(row.technical?.signal?.action?.name ?: "نامشخص", techColor) }) {
            Text("امتیاز ترکیبی CoinGecko: ${row.compositeScore?.let { String.format("%.1f", it) } ?: "—"} " +
                "(توسعه‌دهنده ${row.developerScore?.let { String.format("%.1f", it) } ?: "—"} · جامعه ${row.communityScore?.let { String.format("%.1f", it) } ?: "—"} " +
                "· نقدشوندگی ${row.liquidityScore?.let { String.format("%.1f", it) } ?: "—"})",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            Text("علاقهٔ عمومی ${row.publicInterestScore?.let { String.format("%.3f", it) } ?: "—"} · رأی مثبت جامعه ${row.sentimentUpPct?.let { String.format("%.0f", it) + "٪" } ?: "—"} " +
                "· سهم عرضهٔ در گردش ${row.supplyRatio?.let { String.format("%.0f", it * 100) + "٪" } ?: "—"} · فاصله از ATH ${row.athChangePct?.let { String.format("%.1f", it) + "٪" } ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("زمان CoinGecko: ${row.providerAt?.let { formatDateTime(it) } ?: "نامشخص"}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            val tech = row.technical
            if (tech != null) {
                Text("تکنیکال (کندل ${tech.interval.label} تازهٔ نوبیتکس، ${formatDateTime(tech.computedAt)}): ${tech.signal.action} · امتیاز همگرایی ${String.format("%.1f", tech.signal.confidence)}" +
                    (if (tech.signal.isActionable) " · ورود ${tech.signal.entry?.let { formatPrice(it) } ?: "—"}" else " · زیر آستانهٔ ورود"),
                    style = MaterialTheme.typography.bodySmall, color = techColor, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("تکنیکال: ${row.technicalError ?: "در دسترس نیست"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

