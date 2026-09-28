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
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.notify.Notifier
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SettingsScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val monitorRunning by SignalMonitorService.running.collectAsStateWithLifecycle()
    // Never prefill a saved secret in an editable Compose field. Blank means keep the stored key.
    var key by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf(settings.symbol) }
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
    var confirmJournalClear by remember { mutableStateOf(false) }
    var autoAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(settings.apiKey) { key = "" } // clear input only after a saved key changes
    LaunchedEffect(settings.symbol) { symbol = settings.symbol }
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
        SectionCard(
            title = "منبع دیتای واقعی",
            subtitle = "Twelve Data برای چارت، سیگنال و بک‌تست؛ بدون کلید هم فید رایگان خودکار (Swissquote/Gold-API) وصل می‌شود؛ دیده‌بان پایین منابع جدا دارد",
        ) {
            Text(if (settings.hasKey) "✓ کلید خواندنی در همین نصب موجود است؛ اعتبارش فقط با دریافت دادهٔ تازه مشخص می‌شود."
                else "کلید روی این نصب ذخیره نشده است؛ به‌طور خودکار از فید رایگان قیمت لحظه‌ای (Swissquote، و در صورت نیاز Gold-API برای طلا) استفاده می‌شود — بدون تاریخچهٔ REST جداگانه.",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.hasKey) AurumColors.Cyan else AurumColors.Gold)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (settings.hasKey) "کلید جدید برای جایگزینی (خالی = کلید قبلی)" else "Twelve Data API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("جفت‌ارز چارت و سیگنال (انتخاب فوری؛ فقط همین ۸ نماد):", style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchCatalog.chartSymbols.forEach { pair ->
                    FilterChip(selected = symbol == pair, onClick = {
                        symbol = pair
                        viewModel.selectChartSymbol(pair)
                    }, label = { Text(pair) })
                }
            }
            Button(
                onClick = { viewModel.saveMarketCredentials(key, symbol) },
                enabled = key.isNotBlank() || settings.hasKey,
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
            Text("کلید داخل APK نیست؛ فقط در دادهٔ خصوصی این نصب ذخیره می‌شود. پاک‌کردن داده‌ها/حذف اپ، کلید را پاک می‌کند. debug و release دو نصب جدا با داده‌های جدا هستند: ${BuildConfig.APPLICATION_ID}. APKهای CI با امضای debug موقت ممکن است قابل ارتقا روی نصب قبلی نباشند؛ برای حفظ داده، امضای ثابت لازم است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
                modifier = Modifier.padding(top = 4.dp))
        }

        WatchSettingsSection(viewModel)

        SectionCard("افزونه‌های موتور سیگنال", "هستهٔ ایچیموکو/کانفلوئنس همیشه روشن است؛ تیک‌های زیر به همان موتور اضافه می‌شوند، جای آن را نمی‌گیرند") {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("هسته پایه ایچیموکو", style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                    Text("همیشه فعال: کراس/ابر/چیکو/EMA/VWAP/RSI و مدیریت ریسک فعلی",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                Text("فعال", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            }
            SignalAddonRow(
                title = "افزودن فیلتر مومنتوم/حجم",
                detail = "سیگنال پایه فقط وقتی اجازه ورود می‌گیرد که MACD/ADX هم‌جهت باشند و حجم نسبیِ معتبر ضعیف نباشد؛ حجم جعلی ساخته نمی‌شود.",
                checked = settings.signalProfile.momentumVolume,
                onCheckedChange = viewModel::setSignalMomentumVolume,
            )
            SignalAddonRow(
                title = "افزودن سناریوی تختی SpanB52",
                detail = "به‌جز سیگنال‌های پایه، اگر خط ۵۲ مدتی تخت باشد و قیمت از رنج خارج شود، یک مسیر ورود جدا هم بررسی می‌شود.",
                checked = settings.signalProfile.flatSpanB,
                onCheckedChange = viewModel::setSignalFlatSpanB,
            )
            SignalAddonRow(
                title = "فیلتر بازار رنج / Chop",
                detail = "وقتی ADX پایین، باند/رنج فشرده و شکست معتبر نداریم، ورود متوقف می‌شود تا سیگنال‌های داخل بازار خنثی کمتر شوند.",
                checked = settings.signalProfile.rangeChopFilter,
                onCheckedChange = viewModel::setSignalRangeChop,
            )
            SignalAddonRow(
                title = "تأیید تایم‌فریم بالاتر",
                detail = "از شیب EMA200 و موقعیت قیمت نسبت به ساختار بزرگ‌تر همین داده‌ها کمک می‌گیرد تا سیگنال خلاف روند غالب رد شود.",
                checked = settings.signalProfile.higherTimeframeFilter,
                onCheckedChange = viewModel::setSignalHigherTimeframe,
            )
            SignalAddonRow(
                title = "فیلتر فیک‌بریک‌اوت / Retest",
                detail = "کندل باید بسته‌شدن سالم، ویک غیرمشکوک و شکست/ری‌تست معتبر نسبت به ساختار نزدیک داشته باشد.",
                checked = settings.signalProfile.fakeBreakoutFilter,
                onCheckedChange = viewModel::setSignalFakeBreakout,
            )
            SignalAddonRow(
                title = "فیلتر اسپرد و نقدشوندگی پویا",
                detail = "اگر هزینهٔ اسپرد نسبت به ATR همان لحظه زیاد باشد، سیگنال اجرا متوقف می‌شود؛ قیمت یا اسپرد ساختگی تولید نمی‌شود.",
                checked = settings.signalProfile.dynamicSpreadFilter,
                onCheckedChange = viewModel::setSignalDynamicSpread,
            )
            SignalAddonRow(
                title = "فیلتر زمان‌های خطرناک",
                detail = "ورود نزدیک رول‌اور، باز/بسته‌شدن آخر هفته و پنجره‌های معمول خبرهای سنگین آمریکا محدود می‌شود؛ جایگزین تقویم واقعی خبر نیست.",
                checked = settings.signalProfile.riskyTimingFilter,
                onCheckedChange = viewModel::setSignalRiskyTiming,
            )
            SignalAddonRow(
                title = "فیلتر ریسک ساختار و حد ضرر",
                detail = "اگر حد ضرر نسبت به ATR/ساختار خیلی نزدیک یا خیلی دور باشد، ورود رد می‌شود تا R/R ظاهری فریبنده نشود.",
                checked = settings.signalProfile.structureRiskFilter,
                onCheckedChange = viewModel::setSignalStructureRisk,
            )
            SignalAddonRow(
                title = "کول‌داون بعد از شکست/نوسان رفت‌وبرگشتی",
                detail = "اگر اخیراً کراس مخالف یا چند چرخش تنکان/کیجون دیده شود، چند کندل صبر می‌کند تا overtrade کمتر شود.",
                checked = settings.signalProfile.cooldownFilter,
                onCheckedChange = viewModel::setSignalCooldown,
            )
            Text("تختی SpanB 52 همان میانگین ۵۲ کندل ایچیموکو است: اگر چند کندل ثابت بماند یعنی سقف/کف ۵۲تایی عوض نشده؛ فقط وقتی قیمت از آن و از رنج کوتاه خارج شود به‌عنوان سناریوی رشد/ریزش بررسی می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
        }

        SectionCard("گیت خبر و AI فارکس", "تقویم Forex Factory و خبرهای واقعی وب بی‌نیاز از سرور؛ شرط نهم با سرور HTTPS یا کلید مستقیم زیر تأمین می‌شود") {
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
            Text("خبرهای مستقیم فقط برای مطالعه‌اند؛ ورود سیگنالی/خودکار بدون مدل و شاهد معتبر متوقف است. وتوی دستی جداست؛ سفارش واقعی غیرفعال.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text("جایگزین بدون سرور: اگر نشانی بالا خالی باشد و کلید زیر را پر کنید، خودِ گوشی مستقیماً با RSS همین‌جا + کلید شما تحلیل می‌کند. همین کلید، «همراه تریدر AI» صفحهٔ خانه را هم روشن می‌کند (نظر خودکار هر ۱۰ دقیقه + اعلان تغییر جهت).",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(value = newsAiKey, onValueChange = { newsAiKey = it }, singleLine = true,
                label = { Text("کلید هوش مصنوعی تریدر — Claude یا سازگار با OpenAI (فقط روی همین گوشی)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(value = newsAiBaseUrl, onValueChange = { newsAiBaseUrl = it }, singleLine = true,
                label = { Text("نشانی پایهٔ HTTPS سرویس مدل") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    newsAiBaseUrl = "https://api.llmsrelay.com"; newsAiFormat = "ANTHROPIC"
                }, modifier = Modifier.weight(1f)) { Text("LLMsRelay (sk-cs4)", maxLines = 1) }
                OutlinedButton(onClick = {
                    newsAiBaseUrl = "https://api.anthropic.com"; newsAiFormat = "ANTHROPIC"
                }, modifier = Modifier.weight(1f)) { Text("Claude رسمی", maxLines = 1) }
                OutlinedButton(onClick = {
                    newsAiBaseUrl = "https://api.openai.com/v1"; newsAiFormat = "OPENAI"
                }, modifier = Modifier.weight(1f)) { Text("OpenAI رسمی", maxLines = 1) }
            }
            Text("«نشانی پایه» یعنی آدرسِ سرویسی که کلید شما را صادر کرده — کلید مثل رمز کارت است و نشانی مثل آدرس همان مغازه؛ هر دو را فقط پنل سایت کلید (بخش API / Base URL) می‌دهد. اپ فقط نشانی‌های امن https:// را می‌پذیرد تا کلید در مسیر لو نرود. کلیدهایی که با sk-cs4 شروع می‌شوند مال LLMsRelay هستند و نشانی‌شان با دکمهٔ بالا پر می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(value = newsAiModel, onValueChange = { newsAiModel = it }, singleLine = true,
                label = { Text("نام مدل (مثلاً claude-sonnet-4.6 یا gpt-4o-mini)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            val models = viewModel.aiModels.collectAsStateWithLifecycle().value
            OutlinedButton(onClick = { viewModel.loadNewsAiModels(newsAiKey, newsAiBaseUrl, newsAiFormat) },
                enabled = !models.loading && (newsAiKey.isNotBlank() || settings.hasClientNewsAi) &&
                    (newsAiBaseUrl.isNotBlank() || settings.newsAiBaseUrl.isNotBlank()),
                modifier = Modifier.padding(top = 6.dp)) {
                Text(if (models.loading) "در حال دریافت…" else "فهرست مدل‌های مجازِ همین کلید")
            }
            models.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
            if (models.models.isNotEmpty()) {
                Text("روی مدل بزنید تا انتخاب شود (فهرست واقعی همان سرویس، فیلترشده با کلید شما):",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    models.models.forEach { id ->
                        if (id == newsAiModel) Button(onClick = { newsAiModel = id }) { Text(id, maxLines = 1) }
                        else OutlinedButton(onClick = { newsAiModel = id }) { Text(id, maxLines = 1) }
                    }
                }
            }
            Text("قالب API (برای نشانی‌های غیر از دو دامنهٔ رسمی، صریح انتخاب کنید):",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "AUTO" to "خودکار (با دامنه تشخیص)",
                    "ANTHROPIC" to "Claude / Anthropic",
                    "OPENAI" to "سازگار با OpenAI",
                ).forEach { (value, label) ->
                    val selected = newsAiFormat == value
                    if (selected) Button(onClick = { newsAiFormat = value },
                        modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    else OutlinedButton(onClick = { newsAiFormat = value },
                        modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveNewsAiConfig(newsAiKey, newsAiBaseUrl, newsAiModel, newsAiFormat) },
                    modifier = Modifier.weight(1f)) { Text("ذخیرهٔ کلید مستقیم") }
                OutlinedButton(onClick = {
                    newsAiKey = ""; newsAiBaseUrl = ""; newsAiModel = ""
                    viewModel.clearNewsAiConfig()
                }, modifier = Modifier.weight(1f)) { Text("حذف کلید مستقیم") }
            }
            val probe = viewModel.aiProbe.collectAsStateWithLifecycle().value
            OutlinedButton(onClick = {
                viewModel.testNewsAiConnection(newsAiKey, newsAiBaseUrl, newsAiModel, newsAiFormat)
            }, enabled = !probe.running && (newsAiKey.isNotBlank() || settings.hasClientNewsAi),
                modifier = Modifier.padding(top = 8.dp)) {
                Text(if (probe.running) "در حال آزمون…" else "تست اتصال به مدل")
            }
            probe.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("اتصال تأیید شد")) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Text("⚠️ این کلید روی گوشی ذخیره می‌شود، هرگز به گیت‌هاب نمی‌رود، اما اگر همین APK را با کسی به‌اشتراک بگذارید، کلید همراه آن قابل استخراج است. برای ارائهٔ عمومی از سقف/rate limit سرویس کلید استفاده کنید.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
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
                        "وضعیت سرویس: ${if (monitorRunning) "فعال" else if (settings.backgroundMonitor) "در انتظار شروع" else "خاموش"} · هنگام قفل بودن، فید و تقویم فارکس دوره‌ای بررسی می‌شوند؛ دادهٔ دیررس معامله نیست.",
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
            Text("خبر High/USD با اعلام قبلی یا انتشار عدد (فقط اگر در تقویم باشد) در کانال مستقل «خبر پژوهشی · نه معامله» اطلاع داده می‌شود؛ عنوان/مقایسهٔ عددی سیگنال یا شرط نهم AI نیست. علت بی‌هشداری معامله در تب «معامله» است. هنگام قفل بودن، تغییر شبکه/سکوت فید بازیابی با تأخیر و برچسب دادهٔ قدیمی می‌گیرد، نه LIVE جعلی. Doze، سهمیهٔ ناشر و محدودیت dataSync در Android 15 (حدود ۶ ساعت مجموع در ۲۴ ساعتِ پس‌زمینه) قابل دورزدن نیستند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            OutlinedButton(onClick = { runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            } }, modifier = Modifier.fillMaxWidth()) { Text("تنظیم باتری/اعلان این برنامه در گوشی") }
            Text("در صفحهٔ تنظیمات گوشی، اجازهٔ فعالیت پس‌زمینه و محدودیت باتری را خودتان بررسی کنید؛ برنامه مجوز را خودکار تغییر نمی‌دهد و هیچ تنظیمی اتصال ۲۴ساعته را تضمین نمی‌کند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
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
                "کلید خواندنی در APK نیست؛ از همین گوشی فقط به HTTPS همان ارائه‌دهندهٔ انتخابی ارسال می‌شود. کلید معاملاتی در اپ پذیرفته نمی‌شود.",
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
private fun SignalAddonRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall,
                color = if (checked) AurumColors.Gold else AurumColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WatchSettingsSection(viewModel: AurumViewModel) {
    val selections by viewModel.watchSettings.collectAsStateWithLifecycle()
    val available = WatchCatalog.symbols
    var symbolId by remember { mutableStateOf(available.firstOrNull()?.id.orEmpty()) }
    val symbol = available.firstOrNull { it.id == symbolId } ?: return
    val selected = selections[symbolId] ?: return
    var key by remember(symbolId) { mutableStateOf(viewModel.watchKeyOverride(symbolId)) }
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard(
        title = "منابع هر نماد و کلید جداگانه",
        subtitle = "فقط خواندنی · هر نماد انتخاب و تاریخچهٔ مستقل دارد",
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            available.forEach { item ->
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
                label = { Text("کلید اختصاصی ${symbol.id} (خالی = حذف)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("این فیلد مختص دیده‌بان است و بر چارت/هشدار تأثیر ندارد. اگر کلید اختصاصی را خالی ذخیره کنید، کلید چارت استفاده می‌شود؛ فقط به Twelve Data ارسال می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            OutlinedButton(onClick = { viewModel.setWatchKeyOverride(symbol.id, key) }) { Text("ذخیره کلید این نماد") }
        }
        Text("تاریخچهٔ کامل مشاهدات هر منبع روی همین دستگاه در SQLite نگهداری می‌شود و در دیده‌بان صفحه‌به‌صفحه قابل مشاهده است؛ بک‌فیل تاریخی از سرویس‌دهنده نیست.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp))
        Text("فقط منابع نمایشی همین نمادها اینجا انتخاب می‌شوند. کلید معاملاتی بروکر را هرگز اینجا وارد نکنید؛ این اپ سفارش واقعی ارسال نمی‌کند.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
            modifier = Modifier.padding(top = 6.dp))
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("پاک کردن تاریخچهٔ همین فضا")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("حذف تاریخچهٔ دیده‌بان؟") },
            text = { Text("فقط قیمت‌های قبلاً دریافت‌شدهٔ نمادهای دیده‌بان روی گوشی حذف می‌شود؛ ژورنال و تنظیمات دست‌نخورده می‌مانند. این کار قابل بازگشت نیست.") },
            confirmButton = { TextButton(onClick = { viewModel.clearWatchHistory(); confirmClear = false }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("انصراف") } },
        )
    }
}
