package com.aurum.edge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.data.FreeHistoryCatalog
import com.aurum.edge.data.FreeHistoryResult
import com.aurum.edge.data.FreeHistoryState
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.ui.components.SectionCard
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
    val replay by viewModel.replay.collectAsStateWithLifecycle()
    val replayDecisions by viewModel.replayDecisions.collectAsStateWithLifecycle()
    val walkForward by viewModel.walkForward.collectAsStateWithLifecycle()
    val freeHistory by viewModel.freeHistory.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var freeSource by remember { mutableStateOf(FreeHistoryCatalog.choices.first().id) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.saveFreeHistoryCsv(uri)
    }

    var interval by remember { mutableStateOf(settings.interval) }
    var bars by remember { mutableStateOf(HistoryPolicy.TARGET_CANDLES) }
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
    var histUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var histInterval by remember { mutableStateOf(Interval.M5) }
    val histPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        histUris = uris.sorted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "پژوهش فنی · بدون سرور",
            subtitle = "دادهٔ OHLC واقعی یا فایل وارداتی؛ اجرای معاملات در گذشته فرضی است",
        ) {
            Text(
                "این بک‌تست فقط قواعد فنی روی کندل‌هاست؛ شواهد تاریخیِ نقطه‌به‌نقطه برای لایهٔ خبر/AI، ICT و MTF نداریم. بنابراین عملکرد سیگنال همراه خبر یا سفارش واقعی را نمی‌سنجد. عدد ساختگی، خبرِ جایگزین AI و وعدهٔ سود تولید نمی‌شود.",
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
                listOf(HistoryPolicy.TARGET_CANDLES, 5000).forEach { count ->
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
                enabled = learn !is LearnState.Loading && walkForward !is WalkForwardState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("دانلود و بازپخش قواعد فنی", fontWeight = FontWeight.Bold)
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
                enabled = learn !is LearnState.Loading && walkForward !is WalkForwardState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("خارج نمونه ۷۰/۳۰ + آزمون هزینهٔ ۲×", fontWeight = FontWeight.Bold)
            }
            Text(
                "یک تقسیم ۷۰/۳۰ زمانی از همان کندل‌ها؛ نتیجهٔ خارج نمونه با هزینهٔ فرضی و دوباره با اسپرد/کمیسیون ۲ برابر محاسبه می‌شود. این آزمون حساسیت، اجرای بروکر یا تأیید لایهٔ خبر/AI نیست. کمتر از ۳۰ معاملهٔ بسته فقط هشدار کم‌نمونگی دارد (نه آزمون معنی‌داری).",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("دریافت خودکار دادهٔ تاریخی", "منابع رایگان مشخص؛ اسپات روزانهٔ طلا ممکن است طرح پولی بخواهد") {
            Text("بک‌تست بالا حداقل ۳۰۰۰ کندل از منبع آنلاین فعال می‌گیرد. این بخش CSV جداست: ارزها نرخ مرجع ECB و طلا میانگین ماهانهٔ بانک جهانی از DataHub را بدون کلید می‌گیرند؛ اسپات روزانهٔ طلا از Twelve Data ممکن است پلن پولی ناشر بخواهد. این سری‌ها برای پژوهش‌اند، نه تیک زنده یا سفارش.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            FreeHistoryCatalog.choices.forEach { choice ->
                FilterChip(selected = freeSource == choice.id, onClick = { freeSource = choice.id },
                    label = { Text(choice.title) }, modifier = Modifier.padding(top = 2.dp))
            }
            val selected = FreeHistoryCatalog.find(freeSource)!!
            Text("منبع: ${selected.sourceTitle}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan, modifier = Modifier.padding(top = 4.dp))
            if (selected.kind == com.aurum.edge.data.FreeHistoryKind.TWELVE_DAILY && !settings.hasKey) {
                Text("برای اسپات روزانهٔ طلا، کلید Twelve Data را در تنظیمات وارد کن؛ خودِ کلید کافی نیست و ممکن است دسترسی پولی به Commodities لازم باشد. طلا ماهانه بدون کلید بالاست.",
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

        SectionCard("HistData · تاریخچهٔ XAU/USD و ۷ جفت اصلی", "فایل ماهانه/سالانهٔ M1 و قیمت BID؛ پژوهش، نه فید یا سفارش") {
            Text("از صفحهٔ دانلود رسمی، ZIP ماهانه یا سالانه را در مرورگر گوشی بگیر و همین‌جا انتخاب کن؛ URL دلخواه یا کلید لازم نیست. می‌توانی چند فایل را با هم انتخاب کنی (مثلاً ۵ ZIP سالانه = ۵ سال)؛ همه باید نماد یکسان داشته باشند و به تایم‌فریم انتخابی تجمیع می‌شوند. فایل تیک پشتیبانی نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("تایم‌فریم پژوهشی (از کندل‌های M1 واقعی تجمیع می‌شود؛ شکاف آخر هفته/تعطیلی همان می‌ماند):",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Interval.M1, Interval.M5, Interval.M15, Interval.M30, Interval.H1).forEach { entry ->
                    if (histInterval == entry) Button(onClick = { histInterval = entry },
                        modifier = Modifier.weight(1f)) { Text(entry.label) }
                    else OutlinedButton(onClick = { histInterval = entry },
                        modifier = Modifier.weight(1f)) { Text(entry.label) }
                }
            }
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
            if (period == null) Text("ماه تکمیل‌شدهٔ معتبر از ۲۰۰۹ تا ${lastCompleteMonth} را انتخاب کنید؛ ZIP سالانهٔ کامل را هم از همان صفحه می‌توانی بگیری و یک‌جا انتخاب کنی.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            if (histUris.isNotEmpty()) Text("${histUris.size} فایل انتخاب شد (ماهانه یا سالانه، هم‌نماد)",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            Button(onClick = {
                viewModel.importHistData(histUris, histInterval,
                    balance.toDoubleOrNull() ?: settings.accountBalance,
                    risk.toDoubleOrNull() ?: settings.riskPercent,
                    spread.toDoubleOrNull() ?: settings.spreadPrice,
                    commission.toDoubleOrNull() ?: settings.commissionPerOz,
                    settings.minConfidence)
            }, enabled = histUris.isNotEmpty() && learn !is LearnState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("بک‌تست پژوهشی فایل‌های HistData") }
            Text("HistData ساعت EST ثابت UTC−05:00 بدون تغییر تابستانی و کندل BID دارد؛ اسپرد/کارمزد فرض‌اند. نام فایل منشأ را اثبات نمی‌کند؛ سقف پژوهش ۶۰۰ هزار کندل است (۵ سالِ ۵ دقیقه‌ای ≈ ۵۲۵ هزار) و کندل‌های تجمیعی فقط از M1 واقعی همان فایل‌ها ساخته می‌شوند. هیچ داده‌ای به چارت زنده/ژورنال معامله تزریق نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
        }

        SectionCard("ورود فایل/لینک آموزشی OHLC / MetaTrader", "CSV / TSV با هدر روشن؛ هرگز به چارت زنده یا سفارش وصل نمی‌شود") {
            Text("منشأ فایل را خودت تأیید کن؛ نام نماد، تایم‌فریم انتخابی بالای صفحه و منطقه زمانی دیتاست/سرور باید با فایل یکسان باشند. دیتاست‌های آموزشی با ستون‌های timestamp یا datetime یا DATE+TIME و OPEN/HIGH/LOW/CLOSE (حجم اختیاری) پذیرفته می‌شوند. تا ۶۰۰ هزار کندل آخر تحلیل می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mtSymbol, onValueChange = { mtSymbol = it }, singleLine = true,
                    label = { Text("نماد فایل") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = mtTimezone, onValueChange = { mtTimezone = it }, singleLine = true,
                    label = { Text("UTC offset دیتاست") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = mtLink, onValueChange = { mtLink = it }, singleLine = true,
                label = { Text("لینک عمومی HTTPS فایل CSV آموزشی (اختیاری)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) },
                modifier = Modifier.padding(top = 8.dp)) { Text("انتخاب فایل CSV/TSV از گوشی") }
            mtUri?.let { Text("فایل انتخاب شد: ${it.lastPathSegment?.takeLast(45) ?: "CSV"} (اولویت با فایل)",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }
            Button(onClick = {
                viewModel.importMetaTrader(mtUri, mtLink, mtSymbol, interval, mtTimezone,
                    balance.toDoubleOrNull() ?: settings.accountBalance,
                    risk.toDoubleOrNull() ?: settings.riskPercent,
                    spread.toDoubleOrNull() ?: settings.spreadPrice,
                    commission.toDoubleOrNull() ?: settings.commissionPerOz,
                    settings.minConfidence)
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("بک‌تست پژوهشی روی CSV آموزشی/MT") }
            Text("هدرهای قابل قبول: timestamp/datetime/date یا DATE+TIME، سپس OPEN/HIGH/LOW/CLOSE و در صورت وجود volume/tickvol. برای CSV روزانهٔ خروجی همین اپ، تایم‌فریم 1D را انتخاب کن؛ فایل‌های نرخ مرجع ECB یا طلای ماهانه کندل OHLC نیستند و عمداً بک‌تست کندلی نمی‌شوند. قیمت یا نتایج فایل وارداتی توسط ارائه‌دهندهٔ بازار تأیید نشده‌اند و در فید زنده/ژورنال سفارش ذخیره نمی‌شوند.",
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

        ReplayPanel(replay, viewModel, replayDecisions)

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
