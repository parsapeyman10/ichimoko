package com.aurum.edge.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aurum.edge.data.BacktestJob
import com.aurum.edge.data.BacktestJobStore
import com.aurum.edge.data.BacktestSession
import com.aurum.edge.data.BacktestSessionStore
import com.aurum.edge.data.StrategyReportExport
import com.aurum.edge.data.SessionResearchExport
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
fun StrategyReportsScreen(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val neural by viewModel.neuralState.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val inventory by produceState<ExperimentInventory?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            ExperimentInventory(BacktestJobStore(context).list(), BacktestSessionStore(context).read(), StrategyReportExport.archives(context))
        }
    }
    val strategies = remember(settings, neural) { StrategyAuditReport.build(settings, neural) }
    val performance = remember(trades) { SessionPerformanceReport.build(trades) }
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
            Button(onClick = {
                exportFile({
                    val store = viewModel.container.journalStore
                    check(store.awaitShardMerge(120_000L)) { "ادغام ژورنال کامل نشد؛ دوباره تلاش کنید." }
                    SessionResearchExport.create(context, viewModel.settings.value, viewModel.neuralState.value, store.trades.value.toList())
                }, "application/zip")
            }, modifier = Modifier.fillMaxWidth()) { Text("خروجی کامل تحلیل زمان معاملات ZIP") }
            Text("شامل معاملات باز، بسته و بک‌تست ژورنال، ویژگی‌های ثبت‌شده و آرشیوهای موجود؛ کندل خام و ویژگی‌های ثبت‌نشده را نمی‌سازد. فایل بدون رمز و حاوی اطلاعات شخصی است؛ فقط برای فرد مورد اعتماد ارسال کنید.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            if (message.isNotBlank()) Text(message, color = AurumColors.Gold, fontSize = 11.sp)
            Text("ZIP حاوی تنظیمات و پروفایل‌ها، گزارش کارها، آخرین نشست و آرشیوهای CSV/JSON موجود است؛ کلیدهای API صادر نمی‌شوند. داده‌ها می‌توانند شامل اطلاعات معاملات شخصی باشند.", color = AurumColors.TextSecondary, fontSize = 10.sp)
        }
        item {
            Text("📈 کارنامهٔ دسته‌بندی‌شده", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            val best = performance.bestSession
            Text(if (best == null) "سشن برتر قابل اتکا در داده‌های موجود نیست: هر سشن به حداقل ۳۰ معاملهٔ دارای ریسک معتبر و میانگین R مثبت نیاز دارد." else "سشن برتر تاریخی: ${best.labelFa} · ${best.rSamples} نمونه · میانگین ${String.format(java.util.Locale.US, "%+.2f", best.meanR)}R؛ تضمین آینده نیست.", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("این رتبه‌بندی کلی است؛ ورود خودکار برای هر نماد و استراتژی جداگانه، پس از حداقل ۳۰ نمونه در هر یک از دو سشن، بهترین سشن با میانگین R مثبت را انتخاب می‌کند؛ در کمبود داده محدودیتی اعمال نمی‌شود. UTC ثابت است، نه ساعت محلی بازار.", color = AurumColors.TextSecondary, fontSize = 10.sp)
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
