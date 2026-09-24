package com.aurum.edge.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.aurum.edge.BuildConfig
import com.aurum.edge.core.AppSettings
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.notify.Notifier
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SettingsScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(settings.apiKey) }
    var symbol by remember { mutableStateOf(settings.symbol) }
    var newsUrl by remember { mutableStateOf(settings.newsBaseUrl) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var minConfidence by remember { mutableStateOf(settings.minConfidence.toString()) }
    var spread by remember { mutableStateOf(settings.spreadPrice.toString()) }
    var commission by remember { mutableStateOf(settings.commissionPerOz.toString()) }
    var confirmAuto by remember { mutableStateOf(false) }
    var confirmJournalClear by remember { mutableStateOf(false) }
    var autoAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(settings.apiKey) { if (key.isBlank()) key = settings.apiKey }
    LaunchedEffect(settings.symbol) { symbol = settings.symbol }
    LaunchedEffect(settings.newsBaseUrl) { newsUrl = settings.newsBaseUrl }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectAlertSound(context, uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val started = granted && SignalMonitorService.start(context)
        viewModel.setMonitorFlag(started)
        if (autoAfterPermission) viewModel.setAutoPaperTrading(started)
        autoAfterPermission = false
    }

    fun startMonitorIfAllowed(alsoEnableAuto: Boolean = false) {
        autoAfterPermission = alsoEnableAuto
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else {
            val started = SignalMonitorService.start(context)
            viewModel.setMonitorFlag(started)
            if (alsoEnableAuto) viewModel.setAutoPaperTrading(started)
            autoAfterPermission = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "منبع دیتای واقعی",
            subtitle = "Twelve Data برای چارت، سیگنال و بک‌تست؛ دیده‌بان پایین منابع جدا دارد",
        ) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Twelve Data API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("نماد (مثلاً XAU/USD یا XAG/USD)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Button(
                onClick = {
                    viewModel.saveApiKey(key)
                    viewModel.saveSymbol(symbol)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) { Text("ذخیره و اتصال مجدد", fontWeight = FontWeight.Bold) }
            Text(
                "کلید رایگان از twelvedata.com/apikey — این کلید فقط خواندنی است و دسترسی معاملاتی ندارد.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text("کلید داخل APK قرار نمی‌گیرد و پشتیبان‌گیری خودکار داده‌های اپ غیرفعال است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
                modifier = Modifier.padding(top = 4.dp))
        }

        WatchSettingsSection(viewModel)

        SectionCard("اخبار وب، غربال رمزارز و توقف ورود کاغذی", "نیازمند بک‌اند HTTPS؛ RSS عمومی ناشران و دادهٔ بازار با انتساب منبع") {
            OutlinedTextField(
                value = newsUrl, onValueChange = { newsUrl = it }, singleLine = true,
                label = { Text("آدرس سرور API (https://api.example.com)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.saveNewsBaseUrl(newsUrl) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("اتصال به سرور اخبار و رمزارز")
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("وتوی خبر برای ورود دستی کاغذی هنگام عدم‌دسترسی", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                Switch(checked = settings.pauseOnNews, onCheckedChange = viewModel::setPauseOnNews)
            }
            Text("خوراک‌های IRIB، YJC، اقتصاد۲۴، CoinDesk و BLS روی سرور خوانده می‌شوند؛ پوشش کامل یا حق بازنشر تجاری تضمین نیست. کلید AI و رضایت ناشران فقط روی سرور تنظیم می‌شود. وتوی بالا برای برگهٔ دستی است؛ ورود سیگنالی/خودکار بدون خبر AI معتبر همیشه متوقف است. خروج‌ها مسدود نمی‌شوند؛ سفارش واقعی غیرفعال است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        SectionCard(
            title = "مدیریت سرمایه",
            subtitle = "محاسبه حجم بر پایه ریسک واقعی همان حساب",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                    label = { Text("ریسک هر معامله %") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = minConfidence,
                onValueChange = { minConfidence = it },
                label = { Text("حداقل امتیاز ورود (۷۲ تا ۹۵)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = spread,
                    onValueChange = { spread = it },
                    label = { Text("اسپرد طلا ($)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = commission,
                    onValueChange = { commission = it },
                    label = { Text("کمیسیون/انس ($)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "این دو عدد از بروکر خودت گرفته می‌شوند و در همه گزارش‌ها (بک‌تست، خارج از نمونه، سیگنال زنده) به‌کار می‌روند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = {
                    balance.toDoubleOrNull()?.let(viewModel::saveBalance)
                    risk.toDoubleOrNull()?.let(viewModel::saveRiskPercent)
                    minConfidence.toDoubleOrNull()?.let(viewModel::saveMinConfidence)
                    spread.toDoubleOrNull()?.let(viewModel::saveSpread)
                    commission.toDoubleOrNull()?.let(viewModel::saveCommission)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) { Text("ذخیره تنظیمات ریسک") }
        }

        SectionCard(
            title = "پایش پس‌زمینه",
            subtitle = "اعلان فقط وقتی سیگنال واقعی و تاییدشده تولید شود",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("پایش زنده در پس‌زمینه", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                    Text(
                        "یک سرویس پیش‌زمینه دیتای واقعی را نگه می‌دارد و روی کندل بسته سیگنال می‌سازد.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                }
                Switch(
                    checked = settings.backgroundMonitor,
                    onCheckedChange = { enabled ->
                        if (enabled) startMonitorIfAllowed()
                        else {
                            SignalMonitorService.stop(context)
                            viewModel.setMonitorFlag(false)
                        }
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("هشدار ورود کاغذی / کاندیدای ۹/۹", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                    Text("با ورود خودکار روشن: اعلان فقط پس از ثبت موفق معاملهٔ کاغذی؛ با آن خاموش: اعلان کاندیدای ۹/۹ (نه معامله).",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                Switch(
                    checked = settings.notifyOnSignal,
                    onCheckedChange = viewModel::setNotifyOnSignal,
                )
            }
            Text("هشدار فقط با پایش روشن و اعلان مجاز اندروید کار می‌کند؛ بدون مدل خبر معتبر هیچ آلارمی داده نمی‌شود. خاموش بودن ورود خودکار کاغذی مانع هشدار نیست. محدودیت Android 15 ممکن است پایش پس‌زمینه را پس از ۶ ساعت متوقف کند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text("صدای هشدار: ${settings.alertSoundName.ifBlank { "اعلان پیش‌فرض گوشی" }}",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Gold)
            OutlinedButton(onClick = { soundPicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth()) { Text("انتخاب فایل صوتی از گوشی") }
            if (settings.alertSoundUri.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.testAlertSound(context) }, modifier = Modifier.weight(1f)) {
                        Text("پخش آزمون")
                    }
                    OutlinedButton(onClick = viewModel::resetAlertSound, modifier = Modifier.weight(1f)) {
                        Text("صدای پیش‌فرض")
                    }
                }
            }
            OutlinedButton(onClick = {
                val channelId = if (settings.alertSoundUri.isBlank()) Notifier.CHANNEL_VERIFIED_DEFAULT
                    else Notifier.CHANNEL_VERIFIED_FILE
                Notifier.ensureChannels(context)
                runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                }) }
            }, modifier = Modifier.fillMaxWidth()) { Text("تنظیمات اعلان‌های اندروید") }
            Text("فایل انتخابی با مجوز پایدار روی همین گوشی و حداکثر ۱۰ ثانیه توسط اپ پخش می‌شود، نه توسط کانال سیستم؛ در حالت بی‌صدا/مزاحم‌نشدن یا اگر اعلان‌ها مسدود باشند، شنیدن آلارم تضمین نیست. در اندروید ۱۱+ انتخاب/بی‌صداکردن صدا در تنظیمات همان کانال بر فایل اپ اولویت دارد. ابتدا «پخش آزمون» را امتحان کن.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        SectionCard("ورود خودکار کاغذی · فقط ۹/۹", "پیش‌فرض خاموش؛ بدون بروکر، بدون سفارش واقعی") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("باز کردن خودکار LONG/SHORT کاغذی پس از ۸ شرط فنی و تأیید خبر AI",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextPrimary)
                Switch(checked = settings.autoPaperTrading, onCheckedChange = { enabled ->
                    if (enabled) confirmAuto = true else viewModel.setAutoPaperTrading(false)
                })
            }
            val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
            Text(if (settings.autoPaperTrading) autoStatus else "خاموش؛ خطوط روی چارت معامله نیستند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
            Text("پایش پس‌زمینه، سرور HTTPS و کلید/رضایت مدل روی سرور لازم‌اند. نبود حتی یک شرط، فید ناقص یا خبر پراثر = بدون ورود. نتیجه در ژورنال روی گوشی ذخیره می‌شود؛ خروج با تیک واقعی SL/TP است. مدل و ناشران بازده یا معاملهٔ واقعی را تضمین نمی‌کنند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        SectionCard(
            title = "داده‌های محلی",
            subtitle = "کش کندل‌های واقعی و ژورنال معاملات کاغذی",
        ) {
            Button(
                onClick = viewModel::clearCache,
                colors = ButtonDefaults.buttonColors(containerColor = AurumColors.SurfaceAlt, contentColor = AurumColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("پاک کردن کش کندل‌های واقعی") }
            Button(
                onClick = { confirmJournalClear = true },
                colors = ButtonDefaults.buttonColors(containerColor = AurumColors.SurfaceAlt, contentColor = AurumColors.TextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { Text("پاک کردن ژورنال") }
        }

        SectionCard(
            title = "سیاست داده (بدون دموی ساختگی)",
            subtitle = "چیزی که این اپ عمداً انجام نمی‌دهد",
        ) {
            listOf(
                "هیچ کندل، قیمت، حجم یا اسپرد ساختگی تولید نمی‌شود — نه برای پر کردن صفحه، نه وقتی اینترنت قطع است.",
                "در حالت آفلاین فقط آخرین کندل‌های واقعیِ ذخیره‌شده با برچسب زمان نمایش داده می‌شود.",
                "هیچ آمار عملکردی جعلی وجود ندارد؛ نرخ برد و فاکتور سود فقط از نتایج واقعی محاسبه می‌شود.",
                "تنها «دمو»: اجرای استراتژی روی همان دیتای واقعی (تب یادگیری) و معاملات کاغذی که روی قیمت واقعی تسویه می‌شوند.",
                "کلید API فقط روی همین دستگاه ذخیره می‌شود و به هیچ سروری ارسال نمی‌گردد.",
            ).forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(vertical = 2.dp))
            }
            Text(
                "Trading ${BuildConfig.VERSION_NAME} · تصمیم‌یار، نه مشاوره مالی",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (confirmAuto) {
        AlertDialog(onDismissRequest = { confirmAuto = false },
            title = { Text("ورود خودکار فقط کاغذی") },
            text = { Text("هیچ سفارشی به بروکر ارسال نمی‌شود. فقط با ۹ تأیید تازه و مدل AI فعال روی سرور، یک رکورد LONG/SHORT کاغذی در ژورنال ایجاد می‌شود. اگر سرویس/فید قطع شود ورودی تازه نداریم؛ خروجِ پوزیشن باز نیازمند قیمت واقعی است. فعال شود؟") },
            confirmButton = { TextButton(onClick = {
                confirmAuto = false
                if (settings.backgroundMonitor) viewModel.setAutoPaperTrading(true)
                else startMonitorIfAllowed(alsoEnableAuto = true)
            }) { Text("فعال‌کردن کاغذی") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("انصراف") } })
    }
    if (confirmJournalClear) {
        AlertDialog(onDismissRequest = { confirmJournalClear = false },
            title = { Text("ژورنال کاغذی پاک شود؟") },
            text = { Text("همهٔ پوزیشن‌های باز و بسته‌شدهٔ ثبت‌شده روی این گوشی حذف می‌شوند. این کار برگشت‌پذیر نیست؛ سیگنال‌های چارت اصلاً معاملهٔ ثبت‌شده نیستند.") },
            confirmButton = { TextButton(onClick = { viewModel.clearJournal(); confirmJournalClear = false }) { Text("حذف قطعی") } },
            dismissButton = { TextButton(onClick = { confirmJournalClear = false }) { Text("انصراف") } })
    }
}

@Composable
private fun WatchSettingsSection(viewModel: AurumViewModel) {
    val selections by viewModel.watchSettings.collectAsStateWithLifecycle()
    var symbolId by remember { mutableStateOf(WatchCatalog.symbols.first().id) }
    val symbol = WatchCatalog.find(symbolId) ?: return
    val selected = selections[symbolId] ?: return
    var key by remember(symbolId) { mutableStateOf(viewModel.watchKeyOverride(symbolId)) }
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard(
        title = "منابع هر نماد و کلید جداگانه",
        subtitle = "فقط خواندنی · هر نماد انتخاب و تاریخچهٔ مستقل دارد",
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WatchCatalog.symbols.forEach { item ->
                FilterChip(selected = symbolId == item.id, onClick = { symbolId = item.id },
                    label = { Text(item.id) })
            }
        }
        Text("${symbol.label} · ${symbol.unit} · آستانه اختلاف ${symbol.tolerancePct}%",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(vertical = 6.dp))
        symbol.providerCodes.forEach { (sourceId, code) ->
            val source = SourceCatalog.find(sourceId) ?: return@forEach
            val enabled = sourceId in selected.enabledSources
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${source.title} · $code", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                    Text(source.subtitle, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                Switch(checked = enabled, onCheckedChange = { viewModel.selectWatchSource(symbolId, sourceId, it) })
            }
            if (enabled) {
                OutlinedButton(onClick = { viewModel.setWatchPreferred(symbolId, sourceId) }) {
                    Text(if (selected.preferredSourceId == sourceId) "✓ قیمت نمایشی از ${source.title}" else "انتخاب ${source.title} برای قیمت نمایشی")
                }
            }
        }
        if (SourceCatalog.twelveData.id in symbol.providerCodes) {
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("کلید Twelve Data فقط برای ${symbol.id}") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("اگر اینجا خالی باشد، کلید عمومیِ چارت استفاده می‌شود؛ فقط به Twelve Data ارسال می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            OutlinedButton(onClick = { viewModel.setWatchKeyOverride(symbol.id, key) }) { Text("ذخیره کلید این نماد") }
        }
        Text("تاریخچهٔ کامل مشاهدات هر منبع روی همین دستگاه در SQLite نگهداری می‌شود و در دیده‌بان صفحه‌به‌صفحه قابل مشاهده است؛ بک‌فیل تاریخی از سرویس‌دهنده نیست.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp))
        Text("TSETMC هنوز منبع معتبر/قرارداد پایدار ندارد و به عمد قابل انتخاب نیست. API معاملاتی و کلید Nobitex/MT5 را هرگز اینجا وارد نکنید.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
            modifier = Modifier.padding(top = 6.dp))
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("پاک کردن تاریخچهٔ دیده‌بان")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("حذف تاریخچهٔ دیده‌بان؟") },
            text = { Text("تمام قیمت‌های قبلاً دریافت‌شدهٔ همهٔ منابع روی این گوشی حذف می‌شود. این کار قابل بازگشت نیست.") },
            confirmButton = { TextButton(onClick = { viewModel.clearWatchHistory(); confirmClear = false }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("انصراف") } },
        )
    }
}
