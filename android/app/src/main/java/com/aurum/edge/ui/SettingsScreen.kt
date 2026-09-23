package com.aurum.edge.ui

import android.Manifest
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

    LaunchedEffect(settings.apiKey) { if (key.isBlank()) key = settings.apiKey }
    LaunchedEffect(settings.symbol) { symbol = settings.symbol }
    LaunchedEffect(settings.newsBaseUrl) { newsUrl = settings.newsBaseUrl }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            SignalMonitorService.start(context)
            viewModel.setMonitorFlag(true)
        } else {
            viewModel.setMonitorFlag(false)
        }
    }

    fun startMonitorIfAllowed() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else {
            SignalMonitorService.start(context)
            viewModel.setMonitorFlag(true)
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
                Text("توقف ورود کاغذی هنگام خبر پراثر/عدم دسترسی", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                Switch(checked = settings.pauseOnNews, onCheckedChange = viewModel::setPauseOnNews)
            }
            Text("خوراک‌های IRIB، YJC، اقتصاد۲۴، CoinDesk و BLS روی سرور خوانده می‌شوند؛ پوشش کامل یا حق بازنشر تجاری تضمین نیست. CoinGecko Demo Key اختیاری فقط روی سرور است. با روشن کردن توقف خبر، نبود/کهنگی حتی یک خوراک جلوی ورود تازه را می‌گیرد؛ خروج‌ها مسدود نمی‌شوند. سفارش واقعی غیرفعال است.",
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
                    Text("اعلان سیگنال", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                    Text("هشدار روی همان سیگنالی که در تب «سیگنال» می‌بینی", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                Switch(
                    checked = settings.notifyOnSignal,
                    onCheckedChange = viewModel::setNotifyOnSignal,
                )
            }
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
                onClick = viewModel::clearJournal,
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
