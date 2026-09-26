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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.MarketHours
import com.aurum.edge.data.EquityBoardStatus
import com.aurum.edge.data.EquityRow
import com.aurum.edge.data.TtmResearch
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

private fun Long?.asBoardNumber(): String = this?.let { NumberFormat.getNumberInstance(Locale("fa")).format(it) } ?: "—"
private fun Double?.asBoardNumber(): String = this?.takeIf(Double::isFinite)?.let {
    String.format(Locale("fa"), "%.2f", it)
} ?: "—"

@Composable
fun IranStocksScreen(viewModel: AurumViewModel) {
    val state by viewModel.equities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val browser = LocalUriHandler.current
    var keyInput by remember { mutableStateOf("") }
    var symbolInput by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.stockDataKey) {
        keyInput = "" // a saved key must not reappear as editable text in the UI
        if (settings.stockDataKey.isNotBlank()) while (true) {
            if (MarketHours.iranStockSessionScheduled()) viewModel.refreshEquities()
            delay(180_000L) // one bounded board refresh per three minutes, not web scraping per second
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); now = System.currentTimeMillis() } }
    val recentReceipt = state.recentReceipt(now)
    val qualified = remember(state.rows) { state.rows.filter { it.basicValuePass && it.boardPass } }
    val shown = remember(state.rows, symbolInput) {
        val search = symbolInput.trim()
        (if (search.isBlank()) state.rows.sortedByDescending { it.turnoverRial ?: 0L }
         else state.rows.filter { it.symbol.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true) })
            .take(12)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        ReadOnlyMonitorCard(viewModel, settings)
        SectionCard("بورس ایران · تابلو و پژوهش", "BrsApi (واسطهٔ مستقل TSETMC) · نه حساب کارگزاری آگاه") {
            Text(MarketHours.labelForWorkspace("iran_stocks", now),
                style = MaterialTheme.typography.bodySmall,
                color = if (MarketHours.iranStockSessionScheduled(now)) AurumColors.Cyan else AurumColors.Gold)
            Text("کلید رایگانِ خواندنی بازار را از خود ارائه‌دهنده بگیرید؛ کلید معاملاتی آگاه/نوبیتکس را اینجا وارد نکنید. دادهٔ قیمت تابلوی این سرویس فقط ساعت HH:mm:ss دارد، نه تاریخ مستقل معامله؛ حتی پاسخ تازه، قیمت زنده یا مجوز خرید نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            OutlinedButton(onClick = { runCatching { browser.openUri("https://brsapi.ir/tsetmc-exchange-free-bourse-api-key-request/") } }) {
                Text("دریافت کلید داده از BrsApi ↗")
            }
            OutlinedTextField(value = keyInput, onValueChange = { keyInput = it.take(80); notice = "" },
                label = { Text("کلید دادهٔ خواندنی BrsApi") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    notice = if (viewModel.saveStockDataKey(keyInput)) "کلید محلی ذخیره شد؛ دریافت در حال انجام است"
                             else "کلید باید ۱۰ تا ۸۰ نویسهٔ لاتین/عدد باشد؛ ذخیره نشد"
                }, enabled = keyInput.isNotBlank(), modifier = Modifier.weight(1f)) { Text("ذخیرهٔ کلید") }
                OutlinedButton(onClick = viewModel::refreshEquities,
                    enabled = settings.stockDataKey.isNotBlank() && state.status != EquityBoardStatus.LOADING,
                    modifier = Modifier.weight(1f)) { Text("بررسی تابلو") }
            }
            if (settings.stockDataKey.isNotBlank()) OutlinedButton(onClick = {
                notice = if (viewModel.clearStockDataKey()) "کلید محلی پاک شد" else "پاک‌کردن کلید ناموفق بود"
            }) { Text("حذف کلید خواندنی") }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.labelSmall,
                color = if (notice.contains("نشد") || notice.contains("ناموفق")) AurumColors.Red else AurumColors.Cyan)
            Text(when (state.status) {
                EquityBoardStatus.UNCONFIGURED -> "هنوز کلید داده تنظیم نشده؛ هیچ سهم یا نتیجه‌ای جعل نمی‌شود."
                EquityBoardStatus.LOADING -> "در حال دریافت؛ نتایج قبلی برای غربال تازه معتبر نیستند."
                EquityBoardStatus.UNAVAILABLE -> "تابلو ناموجود: ${state.error ?: "خطای منبع"}"
                EquityBoardStatus.OBSERVED -> "${state.rows.size} سهم دارای ساختار معتبر · دریافت روی گوشی ${formatDateTime(state.receivedAt)} · ${if (recentReceipt) "پاسخ اخیر؛ تاریخ قیمت تأیید نیست" else "دریافت قبلی؛ غربال فعال خاموش"}"
            }, style = MaterialTheme.typography.bodySmall,
                color = if (state.status == EquityBoardStatus.UNAVAILABLE) AurumColors.Red else AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
        }
        SectionCard("غربال عددیِ محدود", "P/E و EPS + توان خریدار حقیقی؛ CAN SLIM کامل نیست") {
            Text("فقط اگر EPS مثبت، P/E بین ۰٫۰۱ تا ۱۵، گردش ≥۱۰ میلیارد ریال، نسبت سرانهٔ خرید به فروش حقیقی ≥۱٫۲، خالص حجم حقیقی مثبت و اسپرد بهترین سفارش ≤۲٪ باشد، ردیف در غربال عددی می‌ماند. هیچ‌کدام احتمال سود/کیفیت گزارش را ثابت نمی‌کند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text(if (recentReceipt) "عبوریِ عددی از ${state.rows.size} سهم: ${qualified.size} (صرفاً مشاهده، بدون تأیید تاریخ معامله)"
                 else "غربال تازه نداریم؛ قطع منبع/کهنگی را «هیچ نامزدی نیست» معنا نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
            Text("CAN SLIM: C/A رشد EPS فصلی/سالانه، N تحول شرکت، L قدرت نسبی، I مالکیت نهادی و M روند بازار از یک عکس تابلو قابل تأیید نیستند. S (عرضه/تقاضا) نیز فقط نشانهٔ حجمی همین پاسخ است؛ تا گزارش‌های چنددوره‌ای کدال و تاریخچهٔ معتبر نرسند، نتیجهٔ CAN SLIM = نامشخص.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red,
                modifier = Modifier.padding(top = 5.dp))
            OutlinedButton(onClick = { runCatching { browser.openUri("https://www.codal.ir/") } }) {
                Text("گزارش‌های رسمی کدال ↗")
            }
        }
        if (state.status == EquityBoardStatus.OBSERVED) {
            SectionCard("ردیف‌های تابلو", "۱۲ ردیف با بیشترین گردش یا جستجوی نماد؛ رتبه‌بندیِ سود نیست") {
                OutlinedTextField(value = symbolInput, onValueChange = { symbolInput = it.take(40) },
                    label = { Text("جستجوی نماد/شرکت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("ارقام قیمت ریال‌اند. ساعت تابلو تاریخ ندارد؛ آخرین قیمت ≠ امکان اجرای سفارش.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                if (shown.isEmpty()) Text("نمادی در این پاسخ پیدا نشد.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            }
            shown.forEach { row -> EquityRowCard(row, recentReceipt) }
        }
        TtmCalculator()
    }
}

@Composable
private fun EquityRowCard(row: EquityRow, recentReceipt: Boolean) {
    val basic = row.basicValuePass && row.boardPass
    SectionCard("${row.symbol} · ${row.name}", "${row.isin} · ساعت اعلام‌شده ${row.boardClock} (تاریخ نامشخص)",
        trailing = { Pill(if (recentReceipt && basic) "عبور عددی" else "فقط مشاهده",
            if (recentReceipt && basic) AurumColors.Cyan else AurumColors.Gold) }) {
        Text("پایانی ${row.closeRial.asBoardNumber()} · آخرین ${row.lastRial.asBoardNumber()} ریال · گردش ${row.turnoverRial.asBoardNumber()} ریال",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
        Text("EPS گزارش‌شده ${row.epsRial.asBoardNumber()} ریال (TTM تأیید نشده) · P/E ${row.pe.asBoardNumber()} · حجم ${row.tradedShares.asBoardNumber()}",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Text("توان حقیقی ${row.retailPower.asBoardNumber()}× · خالص حجم حقیقی ${row.netRetailShares.asBoardNumber()} سهم · اسپرد سطر اول ${row.bestSpreadPct.asBoardNumber()}٪",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Text("عمق سفارش، تازگی معامله، افشای کدال و معامله‌پذیری جداگانه تأیید نشده‌اند.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
    }
}

private fun parsePersianNumber(input: String): Double? {
    if (input.length > 32) return null
    val ascii = input.trim().map { ch ->
        when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            '٫' -> '.'
            else -> ch
        }
    }.joinToString("").replace(",", "").replace("٬", "")
    return ascii.toDoubleOrNull()
}

/** Manual, clearly unverified TTM until audited, period-aligned Codal statements are supplied. */
@Composable
private fun TtmCalculator() {
    var annual by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var previous by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf("") }
    var aligned by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    val result = if (attempted && aligned) listOf(annual, current, previous, shares).map(::parsePersianNumber)
        .takeIf { it.all { value -> value != null } }?.let {
            TtmResearch.epsRial(it[0]!!, it[1]!!, it[2]!!, it[3]!!)
        } else null
    SectionCard("EPS دوازده‌ماههٔ شناور · TTM", "محاسبهٔ دستی از کدال، نه برداشت خودکار از EPS تابلو") {
        Text("فرمول: سود خالص ۱۲ماههٔ آخر + سود تجمعی دورهٔ جاری − سود تجمعی دورهٔ متناظر سال قبل، تقسیم بر شمار سهام جاری. سودها «میلیون ریال» و شمار سهام «میلیون سهم» باشند. صورت‌های مالی باید هم‌دامنه (تلفیقی/غیرتلفیقی)، هم‌دوره و با سرمایهٔ قابل مقایسه باشند.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        OutlinedTextField(value = annual, onValueChange = { annual = it.take(32); attempted = false },
            label = { Text("سود خالص سال مالی آخر · میلیون ریال") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = current, onValueChange = { current = it.take(32); attempted = false },
            label = { Text("سود تجمعی دورهٔ جاری · میلیون ریال") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = previous, onValueChange = { previous = it.take(32); attempted = false },
            label = { Text("سود همان دورهٔ سال قبل · میلیون ریال") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = shares, onValueChange = { shares = it.take(32); attempted = false },
            label = { Text("تعداد سهام جاری · میلیون سهم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = aligned, onCheckedChange = { aligned = it; attempted = false })
            Text("دوره/دامنه/سرمایه را خودم با کدال تطبیق دادم", style = MaterialTheme.typography.labelSmall)
        }
        Button(onClick = { attempted = true }, enabled = aligned) { Text("محاسبهٔ TTM پژوهشی") }
        if (attempted) Text(if (result != null) "EPS TTM محاسبه‌شده از ورود دستی شما: ${result.asBoardNumber()} ریال/سهم · تأیید منبع نشده"
                          else "مقادیر نامعتبرند؛ عدد محدود، شمار سهام مثبت و دوره‌های متناظر وارد کنید.",
            style = MaterialTheme.typography.bodySmall, color = if (result != null) AurumColors.Cyan else AurumColors.Red)
        Text("هیچ عددی به غربال خودکار یا سفارش منتقل نمی‌شود؛ تجدید ارائه و افزایش سرمایه می‌توانند محاسبه را تغییر دهند.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        OutlinedButton(onClick = { runCatching { browser.openUri("https://www.codal.ir/") } }) {
            Text("بررسی صورت‌های مالی در کدال ↗")
        }
    }
}

@Composable
fun AgahGuideScreen() {
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("آگاه · مسیر رسمی", "بخش کارگزاری در فضای بورس؛ جدا از منبع تابلو") {
            Text("در آساتریدر می‌توانید ثبت‌نام و احراز هویت کنید و پس از دریافت کد بورسی از حساب خود استفاده کنید. این اپ نه به حساب آگاه وصل می‌شود، نه توکن/رمز می‌گیرد و نه سفارش می‌فرستد.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("در صفحهٔ رسمی آگاه راه عمومیِ صدور API key معاملاتی برای این نرم‌افزار تأیید نشد. برای دسترسی برنامه‌نویسی، از پشتیبانی خود آگاه دربارهٔ مجوز، مستندات، محیط آزمایشی و سطح دسترسی سؤال کنید؛ کلید یا کوکی آساتریدر را استخراج نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            OutlinedButton(onClick = { runCatching { browser.openUri("https://agah.com/asa-trader") } }) {
                Text("سایت رسمی آساتریدر و راه تماس ↗")
            }
            OutlinedButton(onClick = { runCatching { browser.openUri("https://ex.agah.com/reg/entry") } }) {
                Text("ثبت‌نام و دریافت کد بورسی ↗")
            }
            OutlinedButton(onClick = { runCatching { browser.openUri("https://online.agah.com/login") } }) {
                Text("ورود رسمی به آساتریدر ↗")
            }
        }
        SectionCard("مرز امنیتی", "تحقیق تابلوی بورس ≠ دسترسی به حساب معاملاتی") {
            Text("حتی اگر نتیجهٔ غربال عددی یا TTM دلخواه باشد، قیمت تابلو تاریخ مستقل ندارد، CAN SLIM کامل نیست و تأیید ریسک، قوانین سفارش و سطح دسترسی رسمی کارگزاری وجود ندارد؛ اجرای واقعی عمداً غیرفعال است.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        }
    }
}
