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
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.notify.Notifier
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SettingsScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val monitorRunning by SignalMonitorService.running.collectAsStateWithLifecycle()
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var minConfidence by remember { mutableStateOf(settings.minConfidence.toString()) }
    var spread by remember { mutableStateOf(settings.spreadPrice.toString()) }
    var commission by remember { mutableStateOf(settings.commissionPerOz.toString()) }
    var confirmAuto by remember { mutableStateOf(false) }
    var autoAfterPermission by remember { mutableStateOf(false) }

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
        SectionCard("اتصال اینترنت / VPN گوشی", "بدون پروکسی داخلی و کتابخانهٔ حجیم") {
            TextButton(onClick = { com.aurum.edge.data.AppNetwork.refreshSystemNetwork(force = true) }) {
                Text("بازبینی اتصال پس از تغییر VPN")
            }
            Text("Tor و Snowflake از برنامه حذف شده‌اند. درخواست‌ها از مسیر شبکهٔ انتخاب‌شدهٔ اندروید ارسال می‌شوند.",
                style = MaterialTheme.typography.bodySmall)
            Text("برای استفاده از Windscribe، برنامهٔ رسمی آن را جداگانه روی گوشی وصل کنید. " +
                "در Split Tunneling مطمئن شوید Aurum Edge از VPN مستثنا نشده است. " +
                "هیچ حساب، رمز یا کلید Windscribe در این برنامه دریافت نمی‌شود.",
                style = MaterialTheme.typography.bodySmall)
            Text("با قطع VPN ممکن است اندروید از اینترنت مستقیم استفاده کند؛ اگر این را نمی‌خواهید، " +
                "در صورت پشتیبانی، Always-on VPN و مسدودکردن اتصال بدون VPN را در تنظیمات اندروید فعال کنید. " +
                "اتصال VPN تضمین دسترسی همهٔ منابع داده نیست.",
                style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("حالت هر بازار · روندگیر / رنج‌گیر / ترکیبی", "اعمال روی تمام ${settings.activeSymbols.size} نماد برنامه") {
            Text("تنظیم هر دسته روی همهٔ نمادهای همان دسته اعمال می‌شود؛ اسکن و اعلان به فهرست دستی محدود نیستند. " +
                "فعال‌بودن نماد مجوز معامله نیست؛ دادهٔ تازه و قفل‌های ریسک لازم‌اند.",
                style = MaterialTheme.typography.bodySmall)
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
