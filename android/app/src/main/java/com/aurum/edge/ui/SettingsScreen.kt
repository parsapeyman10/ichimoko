package com.aurum.edge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.aurum.edge.BuildConfig
import com.aurum.edge.core.AppSettings
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SettingsScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(settings.apiKey) }
    var symbol by remember { mutableStateOf(settings.symbol) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var minConfidence by remember { mutableStateOf(settings.minConfidence.toString()) }

    LaunchedEffect(settings.apiKey) { if (key.isBlank()) key = settings.apiKey }
    LaunchedEffect(settings.symbol) { symbol = settings.symbol }

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
            subtitle = "Twelve Data — تنها منبع اپ؛ هیچ منبع ساختگی جایگزین نمی‌شود",
        ) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Twelve Data API Key") },
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
            if (BuildConfig.DEFAULT_TD_API_KEY.isNotBlank()) {
                Text(
                    "این نسخه با کلید پیش‌فرض بیلد شده است؛ می‌توانی کلید خودت را جای آن بگذاری.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
            Button(
                onClick = {
                    balance.toDoubleOrNull()?.let(viewModel::saveBalance)
                    risk.toDoubleOrNull()?.let(viewModel::saveRiskPercent)
                    minConfidence.toDoubleOrNull()?.let(viewModel::saveMinConfidence)
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
                "Aurum Edge ${BuildConfig.VERSION_NAME} · تصمیم‌یار، نه مشاوره مالی",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
