package com.aurum.edge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.FreeHistoryCatalog
import com.aurum.edge.data.FreeHistoryResult
import com.aurum.edge.data.FreeHistoryState
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The ONLY place where "learning" happens: the strategy is replayed over real bars
 * downloaded from the provider. No Monte-Carlo, no synthetic history, no invented win rate.
 */
@Composable
fun LearnScreen(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val learn by viewModel.learn.collectAsStateWithLifecycle()
    val walkForward by viewModel.walkForward.collectAsStateWithLifecycle()
    val freeHistory by viewModel.freeHistory.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var freeSource by remember { mutableStateOf(FreeHistoryCatalog.choices.first().id) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.saveFreeHistoryCsv(uri)
    }

    var interval by remember { mutableStateOf(settings.interval) }
    var bars by remember { mutableStateOf(1000) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var spread by remember { mutableStateOf(settings.spreadPrice.toString()) }
    var commission by remember { mutableStateOf(settings.commissionPerOz.toString()) }
    var mtLink by remember { mutableStateOf("") }
    var mtSymbol by remember { mutableStateOf(settings.symbol) }
    var mtTimezone by remember { mutableStateOf("+00:00") }
    var mtUri by remember { mutableStateOf<Uri?>(null) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> mtUri = uri }
    val lastCompleteMonth = remember { YearMonth.now(ZoneOffset.UTC).minusMonths(1) }
    var histYear by remember { mutableStateOf(lastCompleteMonth.year.toString()) }
    var histMonth by remember { mutableStateOf(lastCompleteMonth.monthValue.toString()) }
    var histUri by remember { mutableStateOf<Uri?>(null) }
    val histPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> histUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "یادگیری روی دیتای اصلی",
            subtitle = "استراتژی روی کندل‌های واقعی Twelve Data اجرا می‌شود و نتیجه واقعی گزارش می‌شود",
        ) {
            Text(
                "تنها «دموی» این اپ همین است: همان موتور زنده، روی همان کندل‌های واقعی، از گذشته به آینده اجرا می‌شود تا ببینی در ادامه چطور رفتار می‌کند. هیچ عدد شبیه‌سازی‌شده یا مونت‌کارلویی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Interval.entries.forEach { entry ->
                    FilterChip(
                        selected = interval == entry,
                        onClick = { interval = entry },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                            selectedLabelColor = AurumColors.Gold,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(500, 1000, 2000, 5000).forEach { count ->
                    FilterChip(
                        selected = bars == count,
                        onClick = { bars = count },
                        label = { Text("$count کندل", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AurumColors.Cyan.copy(alpha = 0.18f),
                            selectedLabelColor = AurumColors.Cyan,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("موجودی $") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = risk,
                    onValueChange = { risk = it },
                    label = { Text("ریسک %") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = spread,
                    onValueChange = { spread = it },
                    label = { Text("اسپرد (فرض)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = commission,
                    onValueChange = { commission = it },
                    label = { Text("کمیسیون/انس (فرض)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "اسپرد و کمیسیون باید از بروکر خودت گرفته شود؛ این دو، فرض‌های هزینه‌اند و در گزارش شفاف نشان داده می‌شوند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = {
                    viewModel.runLearn(
                        interval = interval,
                        bars = bars,
                        balance = balance.toDoubleOrNull() ?: settings.accountBalance,
                        risk = (risk.toDoubleOrNull() ?: settings.riskPercent).coerceIn(0.1, 5.0),
                        spread = spread.toDoubleOrNull() ?: 0.30,
                        commission = commission.toDoubleOrNull() ?: 0.05,
                        threshold = settings.minConfidence,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("دانلود دیتای واقعی و اجرای استراتژی", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    viewModel.runWalkForward(
                        interval = interval,
                        bars = bars,
                        balance = balance.toDoubleOrNull() ?: settings.accountBalance,
                        risk = (risk.toDoubleOrNull() ?: settings.riskPercent).coerceIn(0.1, 5.0),
                        spread = spread.toDoubleOrNull() ?: 0.30,
                        commission = commission.toDoubleOrNull() ?: 0.05,
                        threshold = settings.minConfidence,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("تست خارج از نمونه (۷۰٪ گذشته / ۳۰٪ دیده‌نشده)", fontWeight = FontWeight.Bold)
            }
            Text(
                "در این تست، استراتژی روی نیمه قدیمی سری واقعی اجرا می‌شود و بعد همان قواعد روی نیمه جدیدی که در تنظیم ندیده، سنجیده می‌شود. اگر خارج از نمونه زیان‌ده بود، همان را نشان می‌دهیم.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("دریافت خودکار دادهٔ تاریخی", "منابع رایگان مشخص؛ اسپات روزانهٔ طلا ممکن است طرح پولی بخواهد") {
            Text("ارز: نرخ مرجع ECB؛ طلا: میانگین ماهانهٔ بانک جهانی از DataHub؛ هر دو بدون کلید. سهام آمریکا: کندل روزانهٔ Twelve Data با کلید خواندنی رایگان. اسپات روزانهٔ طلا ممکن است پلن پولی ناشر بخواهد. این سری‌ها برای پژوهش‌اند، نه تیک زنده یا سفارش.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            FreeHistoryCatalog.choices.forEach { choice ->
                FilterChip(selected = freeSource == choice.id, onClick = { freeSource = choice.id },
                    label = { Text(choice.title) }, modifier = Modifier.padding(top = 2.dp))
            }
            val selected = FreeHistoryCatalog.find(freeSource)!!
            Text("منبع: ${selected.sourceTitle}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan, modifier = Modifier.padding(top = 4.dp))
            if (selected.kind == com.aurum.edge.data.FreeHistoryKind.TWELVE_DAILY && !settings.hasKey) {
                Text("برای سهم، کلید رایگان Twelve Data را در تنظیمات وارد کن؛ برای اسپات روزانهٔ طلا، خودِ کلید کافی نیست و ممکن است دسترسی پولی به Commodities لازم باشد. طلا ماهانه بدون کلید بالاست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.downloadFreeHistory(freeSource) },
                    enabled = freeHistory !is FreeHistoryState.Loading, modifier = Modifier.weight(1f)) {
                    Text("دریافت داده")
                }
                OutlinedButton(onClick = { runCatching { uriHandler.openUri(selected.sourcePage) } },
                    modifier = Modifier.weight(1f)) { Text("صفحهٔ منبع") }
            }
            if (selected.kind == com.aurum.edge.data.FreeHistoryKind.TWELVE_DAILY) {
                OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://twelvedata.com/apikey") } },
                    modifier = Modifier.padding(top = 5.dp)) { Text("دریافت کلید رایگان ناشر") }
            }
            when (val state = freeHistory) {
                is FreeHistoryState.Idle -> Text("نماد را انتخاب کن و «دریافت داده» را بزن؛ فایل واقعی پس از پاسخ منبع ساخته می‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                is FreeHistoryState.Loading -> Text("در حال دریافت ${state.title}…",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                is FreeHistoryState.Failed -> Text("دریافت انجام نشد: ${state.message}",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                is FreeHistoryState.Done -> {
                    val data = state.result
                    if (data.choice.id != freeSource) {
                        Text("فایل قبلی متعلق به ${data.choice.title} است؛ برای نماد انتخابی دوباره «دریافت داده» را بزن.",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                    } else {
                        val range = when (data) {
                            is FreeHistoryResult.Ohlc -> "${data.rows.first().date} تا ${data.rows.last().date} · ${data.rows.size} کندل روزانهٔ بسته · آخرین Close: ${formatPrice(data.rows.last().close)} USD"
                            is FreeHistoryResult.Rates -> "${data.rows.first().date} تا ${data.rows.last().date} · ${data.rows.size} نرخ مرجع · آخرین: ۱ EUR = ${formatPrice(data.rows.last().rate)} ${data.choice.code.substringAfter('/')}"
                            is FreeHistoryResult.GoldMonthly -> "${data.rows.first().date} تا ${data.rows.last().date} · ${data.rows.size} ماه واقعی از ۱۹۶۰ · آخرین میانگین: ${formatPrice(data.rows.last().usdPerTroyOunce)} USD/انس"
                        }
                        Text("دریافت شد: ${data.choice.title} · $range",
                            style = MaterialTheme.typography.bodySmall, color = AurumColors.Green,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("منبع: ${data.choice.sourceTitle} · دریافت: ${formatDateTime(data.fetchedAt)}؛ نرخ ECB و میانگین ماهانهٔ طلا کندل OHLC نیستند و برای بک‌تست کندلی به کار نمی‌روند.",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        OutlinedButton(onClick = {
                            csvSaver.launch("${data.choice.code.replace('/', '_')}_${data.fetchedAt}.csv")
                        }, modifier = Modifier.padding(top = 8.dp)) { Text("ذخیرهٔ CSV دادهٔ دریافتی") }
                    }
                }
            }
            Text("اگر منبع قطع/محدود شود یا تاریخ و هویت نماد مغایر باشد، دادهٔ ساختگی یا کش قدیمی جایگزین نمی‌شود. این دانلود به فید معاملاتی/تاریخچهٔ تأییدشده تزریق نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
        }

        SectionCard("HistData · طلای XAU/USD تاریخی", "فقط فایل ماهانهٔ M1 و قیمت BID؛ پژوهش، نه فید یا سفارش") {
            Text("آرشیو ماهانهٔ رسمی را باز کن، فایل ZIP را در مرورگر گوشی دانلود و همین‌جا انتخاب کن؛ URL دلخواه یا کلید لازم نیست. CSV داخل ZIP به‌شکل DAT_ASCII/MT_XAUUSD_M1_YYYYMM است. سال‌های کاملِ چندصد هزارردیفی یا فایل تیک پشتیبانی نمی‌شوند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = histYear, onValueChange = { histYear = it.take(4) },
                    label = { Text("سال میلادی") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = histMonth, onValueChange = { histMonth = it.take(2) },
                    label = { Text("ماه ۱ تا ۱۲") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            val period = runCatching { YearMonth.of(histYear.toInt(), histMonth.toInt()) }.getOrNull()
                ?.takeIf { it.year >= 2009 && it <= lastCompleteMonth }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (period != null) runCatching { uriHandler.openUri(
                        "https://www.histdata.com/download-free-forex-historical-data/?/ascii/1-minute-bar-quotes/xauusd/${period.year}/${period.monthValue}") }
                }, enabled = period != null, modifier = Modifier.weight(1f)) { Text("صفحهٔ دانلود رسمی") }
                OutlinedButton(onClick = { histPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f)) { Text("انتخاب ZIP/CSV") }
            }
            if (period == null) Text("ماه تکمیل‌شدهٔ معتبر از ۲۰۰۹ تا ${lastCompleteMonth} را انتخاب کنید.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            histUri?.let { Text("فایل انتخاب شد: ${it.lastPathSegment?.takeLast(40) ?: "ZIP/CSV"}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }
            Button(onClick = {
                viewModel.importHistData(histUri, balance.toDoubleOrNull() ?: settings.accountBalance,
                    risk.toDoubleOrNull() ?: settings.riskPercent,
                    spread.toDoubleOrNull() ?: settings.spreadPrice,
                    commission.toDoubleOrNull() ?: settings.commissionPerOz,
                    settings.minConfidence)
            }, enabled = histUri != null && learn !is LearnState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("بک‌تست پژوهشی فایل HistData") }
            Text("HistData ساعت EST ثابت UTC−05:00 بدون تغییر تابستانی و کندل BID دارد؛ اسپرد/کارمزد فرض‌اند. نام فایل منشأ را اثبات نمی‌کند؛ تنها ۵۰۰۰ کندل آخر تحلیل می‌شود و هیچ داده‌ای به چارت زنده/ژورنال معامله تزریق نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
        }

        SectionCard("ورود فایل/لینک MetaTrader برای پژوهش", "CSV / TSV خروجی MT4 یا MT5؛ هرگز به چارت زنده یا سفارش وصل نمی‌شود") {
            Text("منشأ فایل را خودت تأیید کن؛ نام نماد، تایم‌فریم انتخابی بالای صفحه و منطقه زمانی سرور MT باید با فایل یکسان باشند. تنها ۵۰۰۰ کندل آخر تحلیل می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mtSymbol, onValueChange = { mtSymbol = it }, singleLine = true,
                    label = { Text("نماد فایل") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = mtTimezone, onValueChange = { mtTimezone = it }, singleLine = true,
                    label = { Text("UTC offset") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = mtLink, onValueChange = { mtLink = it }, singleLine = true,
                label = { Text("لینک عمومی HTTPS فایل CSV (اختیاری)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) },
                modifier = Modifier.padding(top = 8.dp)) { Text("انتخاب فایل CSV از گوشی") }
            mtUri?.let { Text("فایل انتخاب شد: ${it.lastPathSegment?.takeLast(45) ?: "CSV"} (اولویت با فایل)",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }
            Button(onClick = {
                viewModel.importMetaTrader(mtUri, mtLink, mtSymbol, interval, mtTimezone,
                    balance.toDoubleOrNull() ?: settings.accountBalance,
                    risk.toDoubleOrNull() ?: settings.riskPercent,
                    spread.toDoubleOrNull() ?: settings.spreadPrice,
                    commission.toDoubleOrNull() ?: settings.commissionPerOz,
                    settings.minConfidence)
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("بک‌تست پژوهشی روی CSV وارداتی") }
            Text("فایل باید DATE/TIME/OPEN/HIGH/LOW/CLOSE داشته باشد. قیمت یا نتایج فایل وارداتی توسط ارائه‌دهندهٔ بازار تأیید نشده‌اند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
        }

        when (val state = learn) {
            is LearnState.Idle -> SectionCard("نتیجه‌ای هنوز نیست", "بازه را انتخاب کن و اجرا بزن") {
                Text(
                    "خروجی شامل تعداد معاملات، نرخ برد، فاکتور سود، افت سرمایه و فهرست تریدها روی همان کندل‌های واقعی است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
            }

            is LearnState.Loading -> SectionCard("در حال بررسی منبع", state.step) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = AurumColors.Gold, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    Text("بدون کندل معتبر هیچ نتیجه‌ای ساخته نمی‌شود.", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                }
            }

            is LearnState.Failed -> SectionCard("اجرا نشد", "خطای دیتای ارائه‌دهنده یا CSV وارداتی") {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                Text(
                    "بدون کندل معتبر از منبع انتخابی، نتیجه‌ای تولید نمی‌کنیم؛ فایل وارداتی مستقل از فید زنده است.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is LearnState.Done -> {
                BacktestReport(state)
                PerformancePanel(PerformanceMetrics.fromBacktest(state.result), "${state.result.symbol} · ${state.result.dataSource} · هزینه‌های فرض‌شده")
            }
        }

        when (val wf = walkForward) {
            is WalkForwardState.Idle -> Unit
            is WalkForwardState.Loading -> SectionCard("در حال اجرای تست خارج از نمونه", wf.step) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = AurumColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    Text("هر دو نیمه فقط روی کندل‌های واقعی ارزیابی می‌شوند.", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                }
            }
            is WalkForwardState.Failed -> SectionCard("تست خارج از نمونه اجرا نشد", "خطای منبع داده") {
                Text(wf.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            }
            is WalkForwardState.Done -> {
                WalkForwardReport(wf)
                PerformancePanel(PerformanceMetrics.fromBacktest(wf.result.outOfSample), "${wf.result.outOfSample.symbol} · خارج از نمونه (۷۰/۳۰)")
            }
        }
    }
}

@Composable
private fun HalfReport(label: String, accent: Color, result: com.aurum.edge.engine.Backtester.Result) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(
            "  ${formatDateTime(result.fromTime)} → ${formatDateTime(result.toTime)}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        StatTile("معاملات", "${result.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
        StatTile("نرخ برد", result.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
        StatTile("فاکتور سود", result.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        StatTile("موجودی نهایی", "${formatPrice(result.finalBalance)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
        StatTile("حداکثر افت", "${String.format("%.1f", result.maxDrawdownPct)}%", AurumColors.Red, Modifier.weight(1f))
        StatTile("رد‌شده (حداقل لات)", "${result.skippedMinLot}", AurumColors.TextSecondary, Modifier.weight(1f))
    }
}

@Composable
private fun WalkForwardReport(state: WalkForwardState.Done) {
    val wf = state.result
    val outPf = wf.outOfSample.profitFactor ?: 0.0
    SectionCard(
        title = "تست خارج از نمونه (Walk-Forward) ${state.interval.label}",
        subtitle = "${wf.bars} کندل واقعی · تقسیم در ${formatDateTime(wf.splitTime)} · هزینه‌ها در هر دو نیمه یکسان",
    ) {
        Text(
            wf.verdict,
            style = MaterialTheme.typography.bodySmall,
            color = if (outPf > 1.0) AurumColors.Green else AurumColors.Red,
        )
        HalfReport("داخل نمونه (آموزش)", AurumColors.TextSecondary, wf.inSample)
        HalfReport("خارج از نمونه (دیده‌نشده)", AurumColors.Gold, wf.outOfSample)

        if (wf.outOfSample.trades.isNotEmpty()) {
            Text(
                "معاملات نیمه دیده‌نشده (آخرین ${minOf(wf.outOfSample.trades.size, 10)})",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            wf.outOfSample.trades.takeLast(10).reversed().forEach { trade ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trade.side.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trade.side.name == "BUY") AurumColors.Green else AurumColors.Red,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        "${formatPrice(trade.entry)} → ${formatPrice(trade.exit)} · ${trade.exitReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${if (trade.pnlUsd >= 0) "+" else ""}${formatPrice(trade.pnlUsd)}$",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (trade.pnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                    )
                }
            }
        } else {
            Text(
                "در نیمه دیده‌نشده هیچ معامله‌ای ثبت نشد؛ عدد جعلی برای پر کردن این بخش ساخته نمی‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BacktestReport(state: LearnState.Done) {
    val result = state.result
    SectionCard(
        title = "گزارش ${state.interval.label} · ${result.dataSource}",
        subtitle = "${result.bars} کندل · ${formatDateTime(result.fromTime)} تا ${formatDateTime(result.toTime)}",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("معاملات", "${result.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("نرخ برد", result.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
            StatTile("فاکتور سود", result.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("موجودی نهایی", "${formatPrice(result.finalBalance)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("سود/زیان خالص", "${formatPrice(result.netPnl)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("حداکثر افت", "${String.format("%.1f", result.maxDrawdownPct)}%", AurumColors.Red, Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("انتظار به R", result.expectancyR?.let { String.format("%.2f", it) } ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("کارمزد کل", "${formatPrice(result.feesUsd)}$", AurumColors.TextSecondary, Modifier.weight(1f))
            StatTile("رد‌شده (حداقل لات)", "${result.skippedMinLot}", AurumColors.TextSecondary, Modifier.weight(1f))
        }

        if (result.equity.size > 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .padding(top = 12.dp)
                    .background(AurumColors.ChartBg, RoundedCornerShape(8.dp)),
            ) {
                val points = result.equity
                val minV = points.minOf { it.balance }
                val maxV = points.maxOf { it.balance }
                val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
                val stepX = size.width / (points.size - 1).coerceAtLeast(1)
                var previous: Offset? = null
                points.forEachIndexed { index, point ->
                    val x = index * stepX
                    val y = (size.height * (1 - ((point.balance - minV) / span))).toFloat()
                    val current = Offset(x, y)
                    previous?.let { drawLine(AurumColors.Gold, it, current, strokeWidth = 2.5f) }
                    previous = current
                }
            }
            Text(
                "منحنی سرمایه — فقط از نتایج بک‌تست روی کندل‌های انتخاب‌شده",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(result.note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        Text(
            "فرض‌های هزینه: اسپرد ${result.spreadPrice} و کمیسیون ${result.commissionPerOz}$ بر انس (رفت و برگشت محاسبه شده). حداقل حجم 0.01 لات = 1 انس.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (result.trades.isNotEmpty()) {
        SectionCard("فهرست معاملات پژوهشی", "نمایش ${minOf(result.trades.size, 30)} ترید آخر") {
            result.trades.takeLast(30).reversed().forEach { trade ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trade.side.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trade.side.name == "BUY") AurumColors.Green else AurumColors.Red,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${formatPrice(trade.entry)} → ${formatPrice(trade.exit)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            "${formatDateTime(trade.entryTime)} · ${trade.exitReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${if (trade.pnlUsd >= 0) "+" else ""}${formatPrice(trade.pnlUsd)}$",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (trade.pnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                        )
                        Text(
                            "${String.format("%.2f", trade.rMultiple)}R",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                }
            }
        }
    } else {
        SectionCard("هیچ معامله‌ای شکل نگرفت", "هیچ نتیجه‌ای برای پر کردن آمار ساخته نمی‌شود") {
            Text(
                "در این بازه، موتور حتی یک سیگنال واجد شرایط پیدا نکرد. نتیجه‌ای ساخته نمی‌شود تا عدد قشنگ‌تری ببینی — بازه بزرگ‌تر یا تایم‌فریم دیگری را امتحان کن.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        }
    }
}
