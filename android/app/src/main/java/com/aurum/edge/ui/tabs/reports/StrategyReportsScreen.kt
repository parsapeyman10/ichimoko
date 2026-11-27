package com.aurum.edge.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.StrategyAuditReport
import com.aurum.edge.core.SessionPerformanceReport
import com.aurum.edge.core.SessionStrategyEvidence
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.BacktestJob
import com.aurum.edge.data.BacktestJobStore
import com.aurum.edge.data.BacktestSession
import com.aurum.edge.data.BacktestSessionStore
import com.aurum.edge.data.StrategyReportExport
import com.aurum.edge.data.ComprehensiveResearchExport
import com.aurum.edge.ui.theme.AurumColors
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private data class ExperimentInventory(val jobs: List<BacktestJob>, val session: BacktestSession?, val archives: List<File>)
private val reportJson = Json { prettyPrint = true; encodeDefaults = true }

private fun shareReport(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "ارسال یا ذخیرهٔ گزارش").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun EvidenceSelector(label: String, value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $value", fontSize = 10.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false }) }
        }
    }
}

@Composable
fun StrategyReportsScreen(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val neural by viewModel.neuralState.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var confirmComprehensive by remember { mutableStateOf(false) }
    val inventory by produceState<ExperimentInventory?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            ExperimentInventory(BacktestJobStore(context).list(), BacktestSessionStore(context).read(), StrategyReportExport.archives(context))
        }
    }
    val strategies = remember(settings, neural) { StrategyAuditReport.build(settings, neural) }
    val performance = remember(trades) { SessionPerformanceReport.build(trades) }
    var chosenSymbol by remember { mutableStateOf("XAU/USD") }
    var chosenStrategy by remember { mutableStateOf(com.aurum.edge.core.BitcoinStrategyModel.BASE_ICHIMOKU.id) }
    var chosenInterval by remember { mutableStateOf(Interval.M5) }
    var chosenDirection by remember { mutableStateOf("هر دو") }
    val symbols = remember(trades) { (V1Universe.allSymbols + trades.map { V1Universe.normalize(it.symbol) }).filter { it.isNotBlank() }.distinct().sorted() }
    val strategyIds = remember(strategies, trades) { (strategies.map { it.id } + trades.map { it.strategyId }).filter { it.isNotBlank() }.distinct().sorted() }
    val direction = when (chosenDirection) { "خرید" -> SignalAction.BUY; "فروش" -> SignalAction.SELL; else -> null }
    val selectedTrades = remember(trades, chosenSymbol, chosenStrategy, chosenInterval, direction) {
        trades.filter { V1Universe.normalize(it.symbol) == chosenSymbol && it.strategyId == chosenStrategy &&
            it.interval == chosenInterval && !it.isBacktest && (direction == null || it.action == direction) }.sortedByDescending { it.openedAt }
    }
    val evidence = remember(trades, chosenSymbol, chosenStrategy, chosenInterval, direction) {
        SessionStrategyEvidence.build(trades, chosenSymbol, chosenStrategy, chosenInterval, direction)
    }
    var grouping by remember { mutableStateOf("استراتژی") }
    val groups = when (grouping) { "نماد" -> performance.bySymbol; "سشن" -> performance.bySession; else -> performance.byStrategy }
    fun exportFile(make: () -> File, mime: String) {
        scope.launch {
            try {
                message = "در حال ساخت گزارش…"
                val file = withContext(Dispatchers.IO) { make() }
                shareReport(context, file, mime)
                message = "گزارش آماده شد: ${file.name}"
            } catch (e: Exception) { message = "ساخت یا ارسال گزارش ناموفق بود: ${e.message ?: "خطای ناشناخته"}" }
        }
    }
    fun jsonFile(name: String, content: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(directory, name).apply { writeText(content, Charsets.UTF_8) }
    }

    if (confirmComprehensive) {
        AlertDialog(onDismissRequest = { confirmComprehensive = false },
            title = { Text("خروجی جامع بدون رمز") },
            text = { Text("فایل شامل همهٔ معاملات، اطلاعات حساب، تنظیمات، حافظه و وزن شبکه و دادهٔ بازار موجود است. بدون رمز فقط برای شخص مورد اعتماد بفرستید؛ حجم فایل ممکن است زیاد باشد. ادامه می‌دهید؟") },
            confirmButton = { Button(onClick = {
                confirmComprehensive = false
                exportFile({
                    val container = viewModel.container
                    check(container.journalStore.awaitShardMerge(120_000L)) { "ادغام ژورنال کامل نشد؛ دوباره تلاش کنید." }
                    ComprehensiveResearchExport.create(context, container.settingsStore.read(), container.neuralEngine.state.value,
                        container.journalStore.trades.value.toList(), container.neuralEngine.exportFullStateJson())
                }, "application/zip")
            }) { Text("بله، ساخت خروجی") } },
            dismissButton = { OutlinedButton(onClick = { confirmComprehensive = false }) { Text("انصراف") } })
    }

    LazyColumn(Modifier.fillMaxSize().background(AurumColors.Bg).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("📋 گزارش استراتژی‌ها و آزمایش‌ها", color = AurumColors.Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp))
            Text("مرجع = پیش‌فرض نسخهٔ فعلی؛ تاریخچهٔ قبل از تغییر ذخیره نشده است. مقدار ثبت‌شده، حل عمومی بدون نماد و پروفایل عصبی جدا نشان داده می‌شوند؛ موتور ممکن است بر اساس نماد و خود مدل مقدار دیگری استفاده کند. پروفایل لزوماً اعمال نشده است. نتایج حذف‌شده قابل بازیابی نیستند.", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("گیت عصبی: ${if (neural.isGateActive) "فعال" else "غیرفعال"} · ${neural.gatePolicySummaryFa}", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportFile({ StrategyReportExport.create(context, settings, neural, trades) }, "application/zip") }) { Text("خروجی همه ZIP") }
                OutlinedButton(onClick = { revision++ }) { Text("بازخوانی") }
            }
            Button(onClick = { confirmComprehensive = true }, modifier = Modifier.fillMaxWidth()) { Text("خروجی جامع تحلیل معاملات و هوش ZIP") }
            Text("معاملات و ویژگی‌ها، شبکه و حافظه، تنظیمات مجاز، آرشیوها و داده‌های بازار موجود. کلید و رمز صادر نمی‌شود؛ دادهٔ ذخیره‌نشده ساخته نمی‌شود. خروجی بدون رمز و شخصی است.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            if (message.isNotBlank()) Text(message, color = AurumColors.Gold, fontSize = 11.sp)
            Text("ZIP حاوی تنظیمات و پروفایل‌ها، گزارش کارها، آخرین نشست و آرشیوهای CSV/JSON موجود است؛ کلیدهای API صادر نمی‌شوند. داده‌ها می‌توانند شامل اطلاعات معاملات شخصی باشند.", color = AurumColors.TextSecondary, fontSize = 10.sp)
        }
        item {
            Text("🛡️ ممیزی حد سود و ضرر", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            Text("قواعد پلن: ریسک پایه ۵ دلار به‌علاوه ۰٫۵٪ سود واجد شرایط، هدف خالص ۲ تا ۳R؛ قفل سربه‌سر با تیک معتبر پس از ۱٫۵R خالص و سپس فقط بهبود. ممیزی پلن بر اساس مدل هزینهٔ فعلی است، نه تضمین اجرا یا گواه مسیر تاریخی. گپ/قطعی و بافر ۱۰ ثانیهٔ اولیهٔ برخی نمادهای کریپتو می‌تواند باعث فاصلهٔ اجرا از حد شود. اصلاح خودکار پلن باز متوقف است.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            Text("🔎 بررسی نماد × استراتژی × تایم‌فریم × جهت", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            Text("ژورنال Paper غیر‌بک‌تست؛ باز و بسته در بررسی معامله، فقط بستهٔ معتبر در آمار. همهٔ سشن‌ها UTC؛ نمونهٔ صفر نامعلوم است. این آمار پس‌نگر است، نه آزمون مستقل یا مجوز ورود.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            EvidenceSelector("نماد", chosenSymbol, symbols) { chosenSymbol = it }
            EvidenceSelector("استراتژی", chosenStrategy, strategyIds) { chosenStrategy = it }
            Row {
                EvidenceSelector("تایم", chosenInterval.label, Interval.entries.map { it.label }) { label -> chosenInterval = Interval.entries.first { it.label == label } }
                EvidenceSelector("جهت", chosenDirection, listOf("هر دو", "خرید", "فروش")) { chosenDirection = it }
            }
            Text("هر ترکیب از انتخاب‌های بالا جداگانه قابل بررسی است. اطلاعات شبکه و جزئیات کامل هر معامله نیز در خروجی جامع ZIP قرار دارد.", color = AurumColors.TextSecondary, fontSize = 10.sp)
        }
        items(evidence, key = { "matrix-session:" + it.session }) { row ->
            val fmt: (Double?) -> String = { x -> x?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "نامعلوم" }
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(8.dp)) {
                Text(SessionPerformanceReport.sessionLabel(row.session), color = AurumColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(if (row.count == 0) "INSUFFICIENT DATA — معامله بستهٔ معتبر ثبت نشده" else
                    "${row.count} معامله · برد ${row.wins} / باخت ${row.losses} / صفر ${row.breakeven} · نرخ برد ${fmt(if (row.wins + row.losses > 0) row.wins * 100.0 / (row.wins + row.losses) else null)}٪ · خالص ${fmt(row.netUsd)} دلار · سود ناخالص ${fmt(row.grossProfitUsd)} / زیان ناخالص ${fmt(row.grossLossUsd)} · PF ${fmt(row.profitFactor)}\nمیانگین R ${fmt(row.meanR)} (${row.rSamples} نمونه) · میانه ${fmt(row.medianR)} · افت تجمع R ${fmt(row.maxDrawdownR)} · باخت پیاپی ${row.losingStreak} · مدت ${fmt(row.averageDurationMinutes)} دقیقه · مشاهده معتبر شبکه در ورود ${row.neuralEntryCount}", color = AurumColors.TextSecondary, fontSize = 10.sp)
            }
        }
        item { Text("جزئیات معاملات انتخاب‌شده (${selectedTrades.size})", color = AurumColors.Gold, fontWeight = FontWeight.Bold) }
        items(selectedTrades, key = { "evidence-trade:" + it.id }) { t ->
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(8.dp)) {
                val n = t.neuralEntryObservation
                Text("${t.id} · ${t.action.name} · ${if (t.isOpen) "باز" else "بسته"} · ورود ${java.time.Instant.ofEpochMilli(t.openedAt)} UTC", color = AurumColors.TextPrimary, fontSize = 10.sp)
                Text("ورود ${t.entry} · حد اولیه ${t.initialStopLoss ?: "نامعلوم"} · حد فعلی ${t.stopLoss} · هدف ${t.takeProfit} · ریسک ${t.fixedRiskUsd} دلار · P/L ${t.pnlUsd ?: "نامعلوم"}\n${SessionStrategyEvidence.planReviewFa(t)}\nشبکه در ورود: ${if (n == null) "ثبت نشده" else "احتمال ${n.predictedWinProbability}, R پیش‌بینی ${n.expectedR}, ویژگی‌ها ${n.features.size}, اعتبار زمانی ${n.isUsableFor(t.openedAt)}"}", color = AurumColors.TextSecondary, fontSize = 10.sp)
            }
        }
        item {
            Text("📈 کارنامهٔ دسته‌بندی‌شده", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            val best = performance.bestSession
            Text(if (best == null) "سشن برتر توصیفی در داده‌های موجود نیست: هر سشن به حداقل ۳۰ معاملهٔ دارای ریسک معتبر و میانگین R مثبت نیاز دارد." else "سشن برتر تاریخی: ${best.labelFa} · ${best.rSamples} نمونه · میانگین ${String.format(java.util.Locale.US, "%+.2f", best.meanR)}R؛ تضمین آینده نیست.", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("رتبه‌بندی کلی فقط توصیفی است؛ سشن تاریخی مانع قطعی ورود نیست. UTC ثابت است، نه ساعت محلی بازار.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("استراتژی", "نماد", "سشن").forEach { mode ->
                    OutlinedButton(onClick = { grouping = mode }) { Text(if (mode == grouping) "✓ $mode" else mode, fontSize = 10.sp) }
                }
            }
            if (groups.isEmpty()) Text("معاملهٔ بستهٔ واجد شرایط وجود ندارد.", color = AurumColors.TextSecondary, fontSize = 11.sp)
        }
        items(groups, key = { "performance:$grouping:" + it.key }) { g ->
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(8.dp)) {
                Text(g.labelFa, color = AurumColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                val win = g.winRatePct?.let { String.format(java.util.Locale.US, "%.1f٪", it) } ?: "نامعلوم"
                val mean = g.meanR?.let { String.format(java.util.Locale.US, "%+.2fR", it) } ?: "نامعلوم"
                val pf = g.profitFactor?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "نامعلوم"
                Text("${g.count} معامله · ${g.wins} برد / ${g.losses} باخت / ${g.breakeven} سربه‌سر · برد $win · خالص ${String.format(java.util.Locale.US, "%+.2f", g.netUsd)} دلار · میانگین $mean (${g.rSamples} نمونه) · PF $pf", color = AurumColors.TextSecondary, fontSize = 10.sp)
                OutlinedButton(onClick = { exportFile({ jsonFile("performance_${g.key.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json", reportJson.encodeToString(SessionPerformanceReport.Group.serializer(), g)) }, "application/json") }) { Text("خروجی این گروه") }
            }
        }
        item {
            Text("تاریخچهٔ تغییرات ثبت‌شدهٔ هوش عصبی (${neural.knowledgeChangeLog.size})", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            if (neural.knowledgeChangeLog.isEmpty()) Text("تغییری در دفتر ثبت نشده است؛ نبود سابقه به معنی تغییر نکردن نیست.", color = AurumColors.TextSecondary, fontSize = 10.sp)
        }
        items(neural.knowledgeChangeLog, key = { "change:" + it.id }) { change ->
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(8.dp)) {
                Text("${change.titleFa} · ${DateFormat.getDateTimeInstance().format(Date(change.atMs))}", color = AurumColors.TextPrimary, fontSize = 11.sp)
                Text(change.detailFa, color = AurumColors.TextSecondary, fontSize = 10.sp)
            }
        }
        item { Text("استراتژی‌ها (${strategies.size})", color = AurumColors.Gold, fontWeight = FontWeight.Bold) }
        items(strategies, key = { "strategy:" + it.id }) { strategy ->
            var expanded by remember(strategy.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("${strategy.titleFa} · ${if (strategy.enabled) "انتخاب‌شده" else "غیرفعال"}", color = AurumColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded })
                val p = strategy.neuralProfile
                Text(if (p == null) "پروفایل عصبی ثبت نشده" else "پروفایل عصبی: نسل ${p.tuningGeneration} · ${p.samplesAnalyzed} نمونه · ${p.tuningSummaryFa}", color = AurumColors.TextSecondary, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "بستن مقایسه" else "مقایسهٔ تنظیمات") }
                    OutlinedButton(onClick = { exportFile({ jsonFile("strategy_${strategy.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json", reportJson.encodeToString(StrategyAuditReport.Strategy.serializer(), strategy)) }, "application/json") }) { Text("خروجی JSON") }
                }
                if (expanded) {
                    Text("پارامتر | مرجع نسخه | ذخیره‌شده | حل عمومی بدون نماد | پروفایل عصبی", color = AurumColors.Gold, fontSize = 10.sp)
                    strategy.parameters.forEach { row ->
                        Text("${row.key}: ${row.reference ?: "نامعلوم"} | ${row.saved ?: "—"} | ${row.resolved ?: "—"} | ${row.neuralProfile ?: "—"}", color = AurumColors.TextSecondary, fontSize = 10.sp)
                    }
                    p?.featureTunings?.forEach { feature ->
                        Text("ویژگی ${feature.featureTitleFa}: وزن ${feature.learnedWeight} · توجه ${feature.attentionSharePct}٪ · ضریب ${feature.tuningMultiplier} · دستی=${feature.isManuallyTuned}", color = AurumColors.TextSecondary, fontSize = 10.sp)
                    }
                }
            }
        }
        item {
            Text("آزمایش‌های ذخیره‌شده (${inventory?.jobs?.size ?: 0})", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            if (inventory == null) Text("در حال بارگذاری…", color = AurumColors.TextSecondary)
            else if (inventory!!.jobs.isEmpty()) Text("کار ذخیره‌شده‌ای یافت نشد.", color = AurumColors.TextSecondary, fontSize = 11.sp)
        }
        items(inventory?.jobs.orEmpty(), key = { "job:" + it.spec.id }) { job ->
            var expanded by remember(job.spec.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("${job.spec.strategyId} · ${job.spec.symbol} · ${job.status}", color = AurumColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("${DateFormat.getDateTimeInstance().format(Date(job.spec.createdAt))} · ${job.spec.dataSetName} · ${job.messageFa}", color = AurumColors.TextSecondary, fontSize = 10.sp)
                job.result?.let { r -> Text("${r.trades} معامله · برد ${r.winRatePct}٪ · خالص ${r.netPnlUsd} دلار · PF ${r.profitFactor} · امید ${r.expectancyR}R", color = AurumColors.TextSecondary, fontSize = 11.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "بستن" else "جزئیات") }
                    OutlinedButton(onClick = { exportFile({ jsonFile("experiment_${job.spec.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json", reportJson.encodeToString(BacktestJob.serializer(), job)) }, "application/json") }) { Text("خروجی JSON") }
                }
                if (expanded) Text(reportJson.encodeToString(BacktestJob.serializer(), job), color = AurumColors.TextSecondary, fontSize = 10.sp)
            }
        }
        item {
            inventory?.session?.let { session ->
                Text("آخرین نشست بک‌تست", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                Text(session.lastRunLinesFa.joinToString("\n").ifBlank { "خلاصهٔ اجرایی ثبت نشده است." }, color = AurumColors.TextSecondary, fontSize = 10.sp)
            }
            Text("فایل‌های آرشیو موجود (${inventory?.archives?.size ?: 0})", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
        }
        items(inventory?.archives.orEmpty(), key = { "archive:" + it.name }) { file ->
            OutlinedButton(onClick = { exportFile({ file }, if (file.extension.equals("csv", true)) "text/csv" else "application/json") }, modifier = Modifier.fillMaxWidth()) { Text(file.name, fontSize = 10.sp) }
        }
        item { Text("", modifier = Modifier.padding(bottom = 12.dp)) }
    }
}
