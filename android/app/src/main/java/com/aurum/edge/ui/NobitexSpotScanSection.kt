package com.aurum.edge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.NobitexSpotCatalog
import com.aurum.edge.data.NobitexScanState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

/** Scan is completely read-only; not a signal, risk clearance, price feed or trading bridge. */
@Composable
fun NobitexSpotScanSection(viewModel: AurumViewModel) {
    val state by viewModel.nobitexScan.collectAsStateWithLifecycle()
    var quote by remember { mutableStateOf("USDT") }
    var selected by remember { mutableStateOf(NobitexSpotCatalog.bases.toSet()) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.refreshNobitexScan() } // shared repository keeps a just-fetched result

    SectionCard("غربال اسپات نوبیتکس · USDT / ریال", "۶ دارایی × دو بازار · دادهٔ واقعی عمومی، بدون کلید و بدون سفارش") {
        Text("برچسب «نامزد پژوهشی» تنها وقتی بازار باز، تغییر ۲۴ساعته ۱ تا ۱۲٪، اسپرد ≤۰٫۸٪ و گردش ۲۴ساعته ≥۲۰٬۰۰۰ USDT یا ≥۵۰ میلیارد ریال باشد داده می‌شود؛ احتمال رشد یا سود نیست.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("USDT", "ریال").forEach { option ->
                FilterChip(selected = quote == option, onClick = { quote = option }, label = { Text(option) },
                    modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            NobitexSpotCatalog.bases.forEach { base ->
                FilterChip(selected = base in selected,
                    onClick = { selected = if (base in selected) selected - base else selected + base },
                    label = { Text(base) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refreshNobitexScan, enabled = state !is NobitexScanState.Loading,
                modifier = Modifier.weight(1f)) { Text("بررسی جفت‌ها") }
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(NobitexSpotCatalog.document) } },
                modifier = Modifier.weight(1f)) { Text("مستند API") }
        }
        when (val result = state) {
            NobitexScanState.Idle -> Text("برای نمایش، پاسخ API عمومی در حال درخواست است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            NobitexScanState.Loading -> Text("دادهٔ قبلی در حال بررسی مجدد نامزد زنده محسوب نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            is NobitexScanState.Failed -> Text("غربال در دسترس نیست: ${result.message} · این وضعیت «هیچ نامزدی نیست» نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            is NobitexScanState.Done -> {
                val scan = result.snapshot
                val fresh = scan.fresh()
                Text("دریافت روی گوشی: ${formatDateTime(scan.receivedAt)} · ${if (fresh) "پاسخ کامل (۳ دقیقه اعتبار نمایش نامزد)" else "قدیمی/ناقص؛ نامزد فعال نداریم"} · زمانِ معامله توسط stats منتشر نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fresh) AurumColors.Green else AurumColors.Gold)
                val visible = scan.pairs.filter { it.quote == quote && it.base in selected }
                if (visible.isEmpty()) Text("نمادی برای این گروه انتخاب نشده است.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                visible.forEach { row ->
                    val candidate = fresh && row.candidate
                    val price = if (quote == "USDT" && row.latest != null && row.latest < 1.0)
                        String.format(Locale.US, "%.6f", row.latest) else formatPrice(row.latest)
                    Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${row.base}/$quote", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        Pill(if (candidate) "نامزد پژوهشی" else "مشاهده", if (candidate) AurumColors.Green else AurumColors.Gold)
                    }
                    Text("آخرین: $price $quote · ۲۴ساعت: ${row.dayChangePct?.let { formatPrice(it) + "٪" } ?: "—"} · اسپرد ${row.spreadPct?.let { formatPrice(it) + "٪" } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                    Text("بهترین خرید ${formatPrice(row.bestBuy)} / فروش ${formatPrice(row.bestSell)} · گردش سمت $quote: ${formatPrice(row.volumeQuote)}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                    Text(if (fresh) row.observation else "مشاهدهٔ قبلی؛ برای غربال دوباره دریافت کنید",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (candidate) AurumColors.Green else AurumColors.TextMuted)
                }
                if (fresh && scan.candidates == 0) Text("هیچ‌کدام از ۱۲ جفت معیار پژوهشی را در این پاسخ برآورده نکردند؛ این پیش‌بینی نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
        }
        Text("ریال و USDT حساب/واحد جدا دارند؛ قیمت ۲۴ساعته، OHLC، دفتر سفارش و موجودی کاربر در این غربال راستی‌آزمایی نشده‌اند. هیچ پیشنهاد معاملهٔ واقعی، رشد تضمینی، شورت اسپات یا ارسال سفارش نداریم.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Red,
            modifier = Modifier.padding(top = 8.dp))
    }
}
