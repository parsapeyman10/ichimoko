package com.aurum.edge.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import com.aurum.edge.data.GeminiAiStudio
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.notify.Notifier
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SettingsScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val proxyStatus by viewModel.proxyStatus.collectAsStateWithLifecycle()
    val monitorRunning by SignalMonitorService.running.collectAsStateWithLifecycle()
    // Never prefill a saved secret in an editable Compose field. Blank means keep the stored key.
    var newsUrl by remember { mutableStateOf(settings.newsBaseUrl) }
    // Never prefill a saved secret in an editable field; blank on entry, like the API key above.
    var newsAiKey by remember { mutableStateOf("") }
    var newsAiBaseUrl by remember { mutableStateOf(settings.newsAiBaseUrl) }
    var newsAiModel by remember { mutableStateOf(settings.newsAiModel) }
    var newsAiFormat by remember { mutableStateOf(settings.newsAiFormatNormalized) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var minConfidence by remember { mutableStateOf(settings.minConfidence.toString()) }
    var spread by remember { mutableStateOf(settings.spreadPrice.toString()) }
    var commission by remember { mutableStateOf(settings.commissionPerOz.toString()) }
    var confirmAuto by remember { mutableStateOf(false) }
    var autoAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(settings.newsBaseUrl) { newsUrl = settings.newsBaseUrl }
    LaunchedEffect(settings.newsAiApiKey) { newsAiKey = "" }
    LaunchedEffect(settings.newsAiBaseUrl) { newsAiBaseUrl = settings.newsAiBaseUrl }
    LaunchedEffect(settings.newsAiModel) { newsAiModel = settings.newsAiModel }

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
    val testPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.reportNotificationTest(granted && Notifier.notifyTest(context, settings.alertSoundUri))
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
        SectionCard("اتصال مستقل · پروکسی داخلی Tor", "بدون حساب، آدرس سرور، رمز یا کلید") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("پروکسی روشن / خاموش", Modifier.weight(1f))
                Switch(checked = settings.proxyEnabled, onCheckedChange = viewModel::setProxyEnabled)
            }
            Text(proxyStatus.label, color = AurumColors.Gold,
                style = MaterialTheme.typography.bodySmall)
            Text("بازسازی اتصال خودکار است. در حالت روشن، قطع پروکسی باعث اتصال مستقیم پنهانی نمی‌شود. " +
                "اینترنت قطع، مسدودبودن Tor یا محدودیت باتری اندروید ممکن است اتصال را متوقف کند. " +
                "برای پایش در پس‌زمینه، گزینهٔ پایش پس‌زمینه را هم فعال کنید.",
                style = MaterialTheme.typography.bodySmall)
            Text("قیمت‌های عمومی بدون کلید دریافت می‌شوند. این قابلیت اطلاعات شخصی یا ژورنال شما را جمع‌آوری نمی‌کند؛ " +
                "سرویس‌های مقصد درخواست بازار را می‌بینند و Tor یک شبکهٔ شخص ثالث است. " +
                "AI و سرورهای اختیاری فقط با تنظیم خودتان فعال می‌شوند.",
                style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("فهرست و حالت هر بازار · روندگیر / رنج‌گیر / ترکیبی", "۲۰۰ نماد کریپتو + فارکس و کالا؛ سهام آمریکا حذف شده است") {
            var watchlistText by remember(settings.activeWatchlist) {
                mutableStateOf(settings.activeWatchlist.joinToString(", "))
            }
            OutlinedTextField(value = watchlistText, onValueChange = { watchlistText = it },
                label = { Text("نمادها، جدا با ویرگول") },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                viewModel.setWatchlist(watchlistText.split(',', '\n', ';'))
            }) { Text("ذخیرهٔ واچ‌لیست") }
            Text("نماد سیگنال", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                settings.activeWatchlist.forEach { pair ->
                    FilterChip(selected = settings.symbol == pair,
                        onClick = { viewModel.selectChartSymbol(pair) }, label = { Text(pair) })
                }
            }
            com.aurum.edge.core.AssetClass.entries.filter { it != com.aurum.edge.core.AssetClass.STOCK }.forEach { category ->
                Text(category.label, style = MaterialTheme.typography.bodySmall)
                if (category == com.aurum.edge.core.AssetClass.CRYPTO)
                    Text("فشردگی ← انفجار · 5m · 9/26/52 + 103 (ثابت)",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.aurum.edge.core.CategoryStrategy.entries.forEach { mode ->
                        FilterChip(selected = settings.categoryStrategies[category] == mode,
                            onClick = { viewModel.setCategoryStrategy(category, mode) },
                            label = { Text(mode.label) })
                    }
                }
            }
        }

        SectionCard("خبر و AI · LLMsRelay / Gemini") {
            OutlinedTextField(
                value = newsUrl, onValueChange = { newsUrl = it }, singleLine = true,
                label = { Text("نشانی HTTPS سرور خبر فارکس (اختیاری)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.saveNewsBaseUrl(newsUrl) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("ذخیره/حذف نشانی سرور خبر فارکس")
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("وتوی خبر برای ورود دستی کاغذی هنگام عدم‌دسترسی", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                Switch(checked = settings.pauseOnNews, onCheckedChange = viewModel::setPauseOnNews)
            }
            OutlinedTextField(value = newsAiKey, onValueChange = {
                newsAiKey = it; viewModel.resetAiDraftDiagnostics()
            }, singleLine = true,
                label = { Text("کلید مدل انتخابی") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    newsAiKey = ""; newsAiModel = ""
                    newsAiBaseUrl = "https://api.llmsrelay.com"; newsAiFormat = "ANTHROPIC"
                    viewModel.resetAiDraftDiagnostics()
                }, modifier = Modifier.weight(1f)) { Text("LLMsRelay") }
                OutlinedButton(onClick = {
                    newsAiKey = ""; newsAiModel = ""
                    newsAiBaseUrl = GeminiAiStudio.OPENAI_BASE_URL
                    newsAiFormat = GeminiAiStudio.WIRE_FORMAT
                    viewModel.resetAiDraftDiagnostics()
                }, modifier = Modifier.weight(1f)) { Text("Gemini") }
            }
            Text("سرویس: " + when (newsAiBaseUrl) {
                "https://api.llmsrelay.com", "https://api.llmsrelay.com/v1" -> "LLMsRelay"
                GeminiAiStudio.OPENAI_BASE_URL -> "Gemini"
                else -> "انتخاب نشده"
            }, color = AurumColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiAiStudio.KEYS_URL))) }
            }) { Text("ساخت کلید Gemini") }
            OutlinedTextField(value = newsAiModel, onValueChange = {
                newsAiModel = it; viewModel.resetAiDraftDiagnostics()
            }, singleLine = true,
                label = { Text("مدل") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            val models = viewModel.aiModels.collectAsStateWithLifecycle().value
            OutlinedButton(onClick = { viewModel.loadNewsAiModels(newsAiKey, newsAiBaseUrl, newsAiModel, newsAiFormat) },
                enabled = !models.loading && (newsAiKey.isNotBlank() || settings.hasClientNewsAi) &&
                    newsAiBaseUrl in setOf("https://api.llmsrelay.com", "https://api.llmsrelay.com/v1", GeminiAiStudio.OPENAI_BASE_URL),
                modifier = Modifier.padding(top = 6.dp)) {
                Text(if (models.loading) "در حال دریافت…" else "فهرست مدل‌های مجازِ همین کلید")
            }
            models.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            if (models.models.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    models.models.forEach { id ->
                        if (id == newsAiModel) Button(onClick = { newsAiModel = id; viewModel.resetAiDraftDiagnostics() }) { Text(id, maxLines = 1) }
                        else OutlinedButton(onClick = { newsAiModel = id; viewModel.resetAiDraftDiagnostics() }) { Text(id, maxLines = 1) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveNewsAiConfig(newsAiKey, newsAiBaseUrl, newsAiModel, newsAiFormat) },
                    enabled = newsAiBaseUrl in setOf("https://api.llmsrelay.com", "https://api.llmsrelay.com/v1", GeminiAiStudio.OPENAI_BASE_URL),
                    modifier = Modifier.weight(1f)) { Text("ذخیرهٔ کلید مستقیم") }
                OutlinedButton(onClick = {
                    newsAiKey = ""; newsAiBaseUrl = ""; newsAiModel = ""
                    viewModel.clearNewsAiConfig()
                }, modifier = Modifier.weight(1f)) { Text("حذف کلید مستقیم") }
            }
            val probe = viewModel.aiProbe.collectAsStateWithLifecycle().value
            OutlinedButton(onClick = {
                viewModel.testNewsAiConnection(newsAiKey, newsAiBaseUrl, newsAiModel, newsAiFormat)
            }, enabled = !probe.running && (newsAiKey.isNotBlank() || settings.hasClientNewsAi) &&
                newsAiBaseUrl in setOf("https://api.llmsrelay.com", "https://api.llmsrelay.com/v1", GeminiAiStudio.OPENAI_BASE_URL),
                modifier = Modifier.padding(top = 8.dp)) {
                Text(if (probe.running) "در حال آزمون…" else "تست اتصال به مدل")
            }
            probe.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("اتصال تأیید شد")) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.padding(top = 4.dp))
            }
            // The gate the auto-trader itself uses before it asks the AI to judge a trade.
            val aiConnection by viewModel.aiConnection.collectAsStateWithLifecycle()
            val connectionText = when {
                !aiConnection.configured -> "اتصال AI: تنظیم نشده"
                aiConnection.reachable == true -> "اتصال AI: موفق"
                aiConnection.reachable == false -> "اتصال AI: ناموفق"
                else -> "اتصال AI: بررسی نشده"
            }
            Text(
                connectionText,
                style = MaterialTheme.typography.labelSmall,
                color = when (aiConnection.reachable) {
                    true -> AurumColors.Green
                    false -> if (aiConnection.configured) AurumColors.Red else AurumColors.Gold
                    null -> AurumColors.TextMuted
                },
                modifier = Modifier.padding(top = 6.dp),
            )
            aiConnection.checkedAt?.let { at ->
                Text(
                    "آخرین بررسی اتصال: ${formatDateTime(at)}" +
                        if (aiConnection.detail.isNotBlank()) " · ${aiConnection.detail}" else "",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // «ادامه بده یا نه»: advisory review of the OPEN paper positions.
            val holdReview by viewModel.holdReview.collectAsStateWithLifecycle()
            Text(
                when {
                    holdReview.alerts.isNotEmpty() ->
                        "بازبینی پوزیشن‌های باز: نظر AI ادامهٔ ${holdReview.alerts.size} مورد را توصیه نمی‌کند (فقط هشدار)"
                    holdReview.reviewed > 0 && holdReview.checkedAt != null ->
                        "بازبینی پوزیشن‌های باز: ${holdReview.reviewed} مورد با AI بررسی شد · ${formatDateTime(holdReview.checkedAt!!)}"
                    holdReview.skipped.isNotBlank() -> "بازبینی پوزیشن‌های باز: ${holdReview.skipped}"
                    else -> "بازبینی پوزیشن‌های باز: هنوز انجام نشده (هر ۱۰ دقیقه برای هر پوزیشن، اول اتصال بررسی می‌شود)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (holdReview.alerts.isNotEmpty()) AurumColors.Gold else AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
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
                    label = { Text("ریسک هر معامله % (حداکثر ۰٫۵)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = minConfidence,
                onValueChange = { minConfidence = it },
                label = { Text("حداقل امتیاز ورود (۶۰ تا ۹۵)") },
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
                        "وضعیت: ${if (monitorRunning) "فعال" else if (settings.backgroundMonitor) "در انتظار شروع" else "خاموش"}",
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
                    Text("هشدار کاندیدای چهارلایهٔ کاغذی", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                }
                Switch(
                    checked = settings.notifyOnSignal,
                    onCheckedChange = viewModel::setNotifyOnSignal,
                )
            }
            OutlinedButton(onClick = { runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            } }, modifier = Modifier.fillMaxWidth()) { Text("تنظیم باتری/اعلان این برنامه در گوشی") }
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
                val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (permissionMissing) testPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else viewModel.reportNotificationTest(Notifier.notifyTest(context, settings.alertSoundUri))
            }, modifier = Modifier.fillMaxWidth()) { Text("آزمون اعلان واقعی گوشی · بدون معامله") }
            OutlinedButton(onClick = {
                val channelId = if (settings.alertSoundUri.isBlank()) Notifier.CHANNEL_VERIFIED_DEFAULT
                    else Notifier.CHANNEL_VERIFIED_FILE
                Notifier.ensureChannels(context)
                runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                }) }
            }, modifier = Modifier.fillMaxWidth()) { Text("تنظیمات اعلان‌های اندروید") }
        }

        SectionCard("ورود خودکار کاغذی · بدون سفارش واقعی", "بدون فرم دستی؛ بدون بروکر، بدون سفارش واقعی") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ورود خودکار کاغذی",
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
        }

    }
    if (confirmAuto) {
        AlertDialog(onDismissRequest = { confirmAuto = false },
            title = { Text("ورود خودکار فقط کاغذی") },
            text = { Text("فقط معاملهٔ کاغذی؛ سفارش واقعی ارسال نمی‌شود. فعال شود؟") },
            confirmButton = { TextButton(onClick = {
                confirmAuto = false
                viewModel.setAutoPaperTrading(true)
            }) { Text("فعال‌کردن کاغذی") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("انصراف") } })
    }

}
