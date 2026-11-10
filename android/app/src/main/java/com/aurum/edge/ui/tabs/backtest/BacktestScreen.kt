package com.aurum.edge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BacktestTradeArchive
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.domain.Interval
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/**
 * «آزمایشگاه بک‌تست» — تب مستقل.
 *
 * سه بخش دارد و ترتیبش عمداً همین است:
 *  ۱. **داده** — وارد کردن CSV داکاس‌کپی، نماد و تایم‌فریمش.
 *  ۲. **چارت و پیمایش** — دونه‌دونه جلو رفتن، انتخاب استراتژی، اجرای بک‌تست روی همان بازه.
 *  ۳. **نتیجه و تمرین عصبی** — آمار و آنچه شبکه در اتاق تمرین یاد گرفت.
 *
 * در تمام مسیر، ژورنال و حساب برنامه خوانده یا نوشته نمی‌شوند؛ این را هم در متن صفحه
 * می‌نویسیم تا کاربر مطمئن باشد.
 */
@Composable
fun BacktestScreen(container: com.aurum.edge.app.AppContainer) {
    val vm: BacktestViewModel = viewModel(factory = BacktestViewModel.Factory(container))
    val dataSets by vm.dataSets.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val candles by vm.candles.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val visibleCount by vm.visibleCount.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val resultIsStale by vm.resultIsStale.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val etaFa by vm.etaFa.collectAsStateWithLifecycle()
    val heavyRunFa by vm.heavyRunFa.collectAsStateWithLifecycle()
    val status by vm.statusFa.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val archiveFiles by vm.archiveFiles.collectAsStateWithLifecycle()
    val appliedEntry by vm.appliedEntry.collectAsStateWithLifecycle()
    val htfCandles by vm.htfCandles.collectAsStateWithLifecycle()
    val backgroundJobs by vm.jobs.collectAsStateWithLifecycle()
    val restoredLines by vm.restoredLinesFa.collectAsStateWithLifecycle()
    val restoredAt by vm.restoredAtMs.collectAsStateWithLifecycle()

    var toastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> toastText = msg }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importCsv(uri, symbolOverride = "", dropFlatBars = true)
        }
    }

    // تایم‌فریم‌های بالای «واقعی» — جدا از فایل ماشه. اگر خالی بمانَد، رفتار قبلی است.
    val csvMime = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
    val htf1hLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importHtfCsv(uri, role = Interval.H1, dropFlatBars = true) }
    val htf4hLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importHtfCsv(uri, role = Interval.H4, dropFlatBars = true) }

    val selectedSet = dataSets.firstOrNull { it.id == selectedId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        // ── ۰. بک‌آزمون پس‌زمینه ────────────────────────────────────────────
        // «وقتی داخل اپ نیستم بک‌تست کار کنه و نتیجه را نگه داره تا تغییرش بدم.»
        BackgroundJobsSection(
            jobs = backgroundJobs,
            enabled = candles.isNotEmpty() && !busy,
            onQueue = { vm.queueBackgroundRun() },
            onRefresh = { vm.refreshJobs() },
            onOpen = { vm.openJobResult(it) },
            onEdit = { vm.loadJobSettings(it) },
            onCancel = { vm.cancelBackgroundJob(it) },
            onDelete = { vm.deleteBackgroundJob(it) },
            onClearFinished = { vm.clearFinishedJobs() },
        )
        // یک‌بار در هر ورود به صفحه، فهرست کارها از حافظه خوانده می‌شود تا وضعیت کارهایی
        // که وقتی در اپ نبودی تمام شده‌اند تازه باشد.
        LaunchedEffect(Unit) { vm.refreshJobs() }

        // ── نشست قبلی ────────────────────────────────────────────────────────
        // شکایت کاربر: «می‌روم برمی‌گردم همه‌چیز از اول.» فایل داده، نقطهٔ چارت و همهٔ کلیدها
        // برگشته‌اند؛ این‌جا خلاصهٔ آخرین اجرا هم نشان داده می‌شود تا بدانی از کجا ادامه می‌دهی.
        if (restoredLines.isNotEmpty() && result == null) {
            SectionCard(initiallyExpanded = true, title = "🗂 نشست قبلی برگشت") {
                Text(
                    text = "همان فایل و همان تنظیمات که رفته بودی برگشته‌اند" +
                        if (restoredAt > 0) {
                            " · آخرین اجرا: " + java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm",
                                java.util.Locale.US,
                            ).format(java.util.Date(restoredAt))
                        } else {
                            ""
                        } + ". برای چارت و آرشیو، یک‌بار «اجرای بک‌تست» را بزن.",
                    color = AurumColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                restoredLines.forEach { line ->
                    Text(
                        "• $line",
                        color = AurumColors.TextMuted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                }
            }
        }

        // ── ۰. سرصفحه و مرز جدایی ───────────────────────────────────────────
        SectionCard(initiallyExpanded = true, title = "🧪 آزمایشگاه بک‌تست (dukascopy-node)") {
            Text(
                text = "فایل CSV داکاس‌کپی را وارد کنید، چارت را دونه‌دونه جلو ببرید، یک استراتژی " +
                    "انتخاب کنید و ببینید روی همان داده چه می‌کرد. آموزش شبکهٔ عصبی در یک فایل جدا " +
                    "انجام می‌شود: ژورنال، حساب کاغذی و پارامترهای معاملات واقعی دست نمی‌خورند.",
                color = AurumColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = if (busy) "…" else "➕ افزودن CSV",
                    enabled = !busy,
                    color = AurumColors.Gold,
                    onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                )
                /** یک دکمه برای هر نقش: خالی = انتخاب فایل، پر = حذف فایل. */
                ActionButton(
                    label = if (htfCandles.containsKey(Interval.H1)) "✖ ۱ساعته" else "＋ ۱ساعته",
                    enabled = !busy,
                    color = if (htfCandles.containsKey(Interval.H1)) AurumColors.Green else AurumColors.TextSecondary,
                    onClick = {
                        if (htfCandles.containsKey(Interval.H1)) vm.clearHtf(Interval.H1)
                        else htf1hLauncher.launch(csvMime)
                    },
                )
                ActionButton(
                    label = if (htfCandles.containsKey(Interval.H4)) "✖ ۴ساعته" else "＋ ۴ساعته",
                    enabled = !busy,
                    color = if (htfCandles.containsKey(Interval.H4)) AurumColors.Green else AurumColors.TextSecondary,
                    onClick = {
                        if (htfCandles.containsKey(Interval.H4)) vm.clearHtf(Interval.H4)
                        else htf4hLauncher.launch(csvMime)
                    },
                )
                ActionButton(
                    label = "↻ بازخوانی",
                    enabled = !busy,
                    color = AurumColors.TextSecondary,
                    onClick = { vm.refresh() },
                )
                if (dataSets.isNotEmpty()) {
                    ActionButton(
                        label = "🗑 همه را پاک کن",
                        enabled = !busy,
                        color = AurumColors.Red,
                        onClick = { vm.clearAllData() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "دستور ساخت فایل روی کامپیوتر:\n" +
                    "npx dukascopy-node -i xauusd -from 2024-01-01 -to 2024-06-01 -t m15 -f csv -v",
                color = AurumColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (htfCandles.isEmpty()) {
                    "تایم‌فریم‌های بالا از خودِ فایل ۱۵دقیقه‌ای ساخته می‌شوند. برای «چند تایم‌فریمیِ واقعی» " +
                        "فایل ۱ساعته و ۴ساعتهٔ همان نماد را هم اضافه کنید."
                } else {
                    "چند تایم‌فریمیِ واقعی: " + htfCandles.entries
                        .sortedBy { it.key.minutes }
                        .joinToString(" · ") { "${it.key.label}=${it.value.size} کندل" }
                },
                color = if (htfCandles.isEmpty()) AurumColors.TextMuted else AurumColors.Green,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            status.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AurumColors.Cyan, fontSize = 11.sp, lineHeight = 15.sp)
            }
            // هشدار «اجرای سنگین» **پیش از** شروع و بی‌درنگ با تغییر بازه/حالت نمایش داده
            // می‌شود: کاربر باید بداند ۸ دقیقه منتظر می‌ماند یا بازه را کوچک کند.
            val heavy = heavyRunFa
            if (heavy.isNotBlank() && !busy) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = heavy,
                    color = AurumColors.Gold,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (progress <= 0.0) 0.05f else progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = AurumColors.Gold,
                    trackColor = AurumColors.SurfaceAlt,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${String.format(java.util.Locale.US, "%.0f", progress * 100)}%" +
                        (etaFa.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = AurumColors.TextMuted,
                    fontSize = 9.sp,
                )
            }
            toastText?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = AurumColors.Orange, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Pill(text = "بستن", color = AurumColors.TextMuted, modifier = Modifier.clickable { toastText = null })
                }
            }
        }

        // ── ۱. داده‌ها ─────────────────────────────────────────────────────
        if (dataSets.isNotEmpty()) {
            SectionCard(initiallyExpanded = true, title = "📁 داده‌های وارد‌شده (${dataSets.size})") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dataSets.forEach { set ->
                        val isSelected = set.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) AurumColors.Gold.copy(alpha = 0.10f) else AurumColors.SurfaceAlt)
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) AurumColors.Gold.copy(alpha = 0.5f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable { vm.select(set.id) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${set.symbol} · ${set.intervalLabel}",
                                    color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${fmtCount(set.candleCount)} کندل · " +
                                        "${String.format(java.util.Locale.US, "%.0f", set.spanDays)} روز · " +
                                        "${fmtBytes(set.bytes)}",
                                    color = AurumColors.TextMuted,
                                    fontSize = 10.sp,
                                )
                                Text(set.displayName, color = AurumColors.TextMuted, fontSize = 9.sp, maxLines = 1)
                            }
                            Pill(
                                text = "حذف",
                                color = AurumColors.Red,
                                modifier = Modifier.clickable { vm.delete(set.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("مسیر ذخیره: ${vm.storePathFa}", color = AurumColors.TextMuted, fontSize = 9.sp)
            }
        }

        // ── ۲. چارت و پیمایش ───────────────────────────────────────────────
        if (candles.isNotEmpty()) {
            val endIndex = cursor.coerceIn(1, candles.size)
            val currentCandle = candles.getOrNull(endIndex - 1)
            SectionCard(initiallyExpanded = true, title = "📈 چارت — ${selectedSet?.symbol ?: settings.symbol} ${selectedSet?.intervalLabel ?: ""}") {
                BacktestChartLegend(candle = currentCandle, intervalLabel = selectedSet?.intervalLabel ?: "")
                BacktestCandleChart(
                    candles = candles,
                    trades = if (resultIsStale) emptyList() else result?.trades.orEmpty(),
                    visibleCount = visibleCount,
                    endIndex = endIndex,
                    onVisibleCountChange = vm::setVisibleCount,
                    onScrub = { target ->
                        if (target < 1) vm.setCursor(1) else vm.setCursor(target)
                    },
                    highlightIndex = endIndex - 1,
                )
                Spacer(Modifier.height(8.dp))
                // نوار پیمایش: دونه‌دونه جلو/عقب، پرش و اسلایدر
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionButton("⏮", enabled = true, color = AurumColors.TextSecondary) { vm.stepCursor(-50) }
                    ActionButton("◀", enabled = true, color = AurumColors.TextSecondary) { vm.stepCursor(-1) }
                    Text(
                        text = "${fmtCount(endIndex)} / ${fmtCount(candles.size)}",
                        color = AurumColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton("▶", enabled = true, color = AurumColors.Gold) { vm.stepCursor(1) }
                    ActionButton("⏭", enabled = true, color = AurumColors.TextSecondary) { vm.stepCursor(50) }
                    ActionButton("آخر", enabled = true, color = AurumColors.Cyan) { vm.setCursor(candles.size) }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 5, 20, 100).forEach { step ->
                        Pill(
                            text = "+$step کندل",
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.clickable { vm.stepCursor(step) },
                        )
                    }
                    Pill(
                        text = "بزرگ‌نمایی: $visibleCount کندل",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            vm.setVisibleCount(if (visibleCount >= 400) 80 else (visibleCount * 2))
                        },
                    )
                }
                // ── معامله‌ها روی چارت ──────────────────────────────────────
                // کاربر باید بتواند «کجا معامله شد» را نه از روی حدس، بلکه با یک ضربه ببیند:
                // هر تراشه، چارت را دقیقاً روی کندل ورود همان معامله می‌برد.
                val chartTrades = if (resultIsStale) emptyList() else result?.trades.orEmpty()
                if (chartTrades.isNotEmpty()) {
                    val sortedCandles = candles
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "📍 معامله‌ها روی چارت (${chartTrades.size}) — با ضربه، چارت روی کندل ورود همان معامله می‌رود:",
                        color = AurumColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        chartTrades.forEach { trade ->
                            val entryIdx = sortedCandles.indexOfFirst { it.time == trade.entryTime }
                            val isBuy = trade.action == com.aurum.edge.domain.SignalAction.BUY
                            Pill(
                                text = "${if (isBuy) "▲" else "▼"} ${formatShortTime(trade.entryTime)} · " +
                                    "${if (trade.netPnlUsd >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.0f", trade.netPnlUsd)}\$",
                                color = when {
                                    trade.isOpen -> AurumColors.Cyan
                                    trade.netPnlUsd >= 0 -> AurumColors.Green
                                    else -> AurumColors.Red
                                },
                                modifier = Modifier.clickable {
                                    if (entryIdx >= 0) {
                                        // کمی فضا بعد از ورود تا خروج هم داخل کادر باشد.
                                        vm.setCursor((entryIdx + 12).coerceIn(1, sortedCandles.size))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ── ۳. استراتژی و اجرا ─────────────────────────────────────────
            SectionCard(initiallyExpanded = true, title = "🎯 استراتژی و اجرای بک‌تست") {
                StrategyPicker(
                    selectedId = settings.strategyId,
                    runAll = settings.runAllStrategies,
                    onPick = { vm.updateSettings { s -> s.copy(strategyId = it, runAllStrategies = false) } },
                    onAll = { vm.updateSettings { s -> s.copy(runAllStrategies = true) } },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "همه" to "ALL",
                        "بیس ۴گانه" to BitcoinStrategyModel.BASE_ICHIMOKU.id,
                        "هیدن کراس" to BitcoinStrategyModel.HIDDEN_CROSS.id,
                        "پاتل" to BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id,
                        "تاتل" to BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT.id,
                        "هوسودا" to BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE.id,
                        "پلوییل" to BitcoinStrategyModel.PELOILLE_MTF_PLATEAU.id,
                    ).forEach { (label, id) ->
                        Pill(
                            text = label,
                            color = if ((id == "ALL" && settings.runAllStrategies) ||
                                (!settings.runAllStrategies && settings.strategyId == id)
                            ) AurumColors.Gold else AurumColors.TextSecondary,
                            modifier = Modifier.clickable {
                                if (id == "ALL") vm.updateSettings { s -> s.copy(runAllStrategies = true) }
                                else vm.updateSettings { s -> s.copy(strategyId = id, runAllStrategies = false) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // ریسک هر معامله: همین عدد حجم پوزیشن و در نتیجه منحنی سرمایه را می‌سازد.
                Text(
                    "ریسک هر معامله: ${settings.riskPercent}٪ سرمایه · سرمایهٔ شروع: " +
                        String.format(java.util.Locale.US, "%.0f$", settings.startEquityUsd),
                    color = AurumColors.TextSecondary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25, 0.5, 1.0, 2.0).forEach { pct ->
                        Pill(
                            text = "$pct٪",
                            color = if (kotlin.math.abs(settings.riskPercent - pct) < 1e-9) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable { vm.updateSettings { s -> s.copy(riskPercent = pct) } },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("خروج هوشمند (بالای ۲R)", settings.useSmartExit) { on ->
                    vm.updateSettings { s -> s.copy(useSmartExit = on) }
                }
                ToggleRow("قفل سود (Preserve Mode)", settings.usePreserveMode) { on ->
                    vm.updateSettings { s -> s.copy(usePreserveMode = on) }
                }
                ToggleRow("توقف زمانی (۲۰ کندل)", settings.useTimeStop) { on ->
                    vm.updateSettings { s -> s.copy(useTimeStop = on) }
                }
                ToggleRow("در لمس هم‌زمان، سود فرض شود", settings.optimisticSameBarFill) { on ->
                    vm.updateSettings { s -> s.copy(optimisticSameBarFill = on) }
                }
                // مقایسهٔ A/B «بیس کامل» با «بیس خالص چهارگانه» (بدون دیوار تاج هیدن کراس).
                ToggleRow("لایهٔ هیدن کراس در بیس فعال باشد", settings.includeHiddenCrossLayer) { on ->
                    vm.updateSettings { s -> s.copy(includeHiddenCrossLayer = on) }
                }
                // ماشهٔ «فشردگی → انبساط» فقط روی «بیس». خاموش = خروجی بیس مو‌به‌مو مثل قبل.
                ToggleRow("ماشهٔ فشردگی → انبساط (بیس)", settings.compressionExpansion) { on ->
                    vm.updateSettings { s -> s.copy(compressionExpansion = on) }
                }
                // موتور «پیمان کریپتو»: خاموش = رفتار قبلی؛ روشن = معاملهٔ کاغذی روی رمزارز.
                ToggleRow("پیمان کریپتو حرفه‌ای (فقط رمزارز)", settings.peymanCryptoPaper) { on ->
                    vm.updateSettings { s -> s.copy(peymanCryptoPaper = on) }
                }
                // ── هزینهٔ محافظه‌کارانه ──────────────────────────────────────────
                // «اگر به سوددهی کمک می‌کند انجام بده»: این کلید سود را بیشتر نشان نمی‌دهد،
                // عمداً سخت‌گیرتر می‌شود (کارمزد و اسپرد ×۳) تا معلوم شود سود از استراتژی
                // می‌آید یا از تخمین خوش‌بینانهٔ هزینه. با ×۳ هم مثبت ماند ⇒ سود واقعی است.
                ToggleRow("💸 هزینهٔ محافظه‌کارانه (کارمزد و اسپرد ×۳)", settings.conservativeCosts) { on ->
                    vm.updateSettings { s -> s.copy(conservativeCosts = on) }
                }
                if (settings.conservativeCosts) {
                    Text(
                        text = "با این کلید، همان استراتژی روی XAU ۱۵دقیقه PF ۱٫۷۴ و +۰٫۳۳۰R داد " +
                            "(به‌جای ۱٫۸۶ و +۰٫۳۸۰R). عدد کمتر است ولی به اسپرد واقعی همان فایل " +
                            "(میانهٔ ۰٫۶۱۷ دلار) نزدیک‌تر — یعنی سود از استراتژی می‌آید، نه از هزینهٔ کم‌شمرده.",
                        color = AurumColors.Orange,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "بک‌تست روی ${fmtCount(endIndex)} کندلِ تا این نقطه اجرا می‌شود؛ کندل‌های آیندهٔ " +
                        "فایل دیده نمی‌شوند (بدون نگاه به آینده).",
                    color = AurumColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                // سقف کندل: تنها اهرم صادقانه برای کوتاه‌کردن زمان اجرا. این عدد **نتیجه را عوض
                // می‌کند** (بازهٔ کوتاه‌تر = نمونهٔ کمتر)، پس کنارش نوشته می‌شود، نه پنهان.
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "سقف کندل برای این اجرا (کوتاه‌تر = سریع‌تر، ولی نمونهٔ کمتر):",
                    color = AurumColors.TextSecondary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(0 to "همه", 2_000 to "۲٬۰۰۰", 5_000 to "۵٬۰۰۰", 10_000 to "۱۰٬۰۰۰").forEach { (cap, label) ->
                        Pill(
                            text = label,
                            color = if (settings.maxBars == cap) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable {
                                vm.updateSettings { st -> st.copy(maxBars = cap) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = if (busy) "در حال اجرا…" else "▶ اجرای بک‌تست",
                        enabled = !busy,
                        color = AurumColors.Green,
                        onClick = { vm.runBacktest() },
                    )
                    ActionButton(
                        label = "🧠 آموزش شبکهٔ عصبی",
                        enabled = !busy && result != null,
                        color = AurumColors.Purple,
                        onClick = { vm.trainNeural() },
                    )
                }
            }
        } else {
            SectionCard(initiallyExpanded = true, title = "📈 چارت") {
                Text(
                    "هنوز داده‌ای وارد نشده. یک فایل CSV از dukascopy-node بگیرید و با دکمهٔ «افزودن CSV» " +
                        "واردش کنید. نمونهٔ دستور در بالای همین صفحه هست.",
                    color = AurumColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        // ── ۳.۵ آرشیو معاملاتی (پایان هر بک‌تست) ─────────────────────────────
        archive?.let { arc ->
            SectionCard(initiallyExpanded = true, title = "🗂 آرشیو معاملاتی این بک‌تست") {
                Text(
                    text = "${arc.strategyLabel} · ${arc.symbol} ${arc.intervalLabel} · " +
                        "${arc.metrics.trades} معاملهٔ بسته‌شده در ${arc.days.size} روز معاملاتی",
                    color = AurumColors.TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MiniStat(
                        "سود خالص",
                        fmtUsd(arc.metrics.netUsd),
                        if (arc.metrics.netUsd >= 0) AurumColors.Green else AurumColors.Red,
                    )
                    MiniStat(
                        "کارمزد",
                        String.format(java.util.Locale.US, "%.2f$", arc.metrics.feesUsd),
                        AurumColors.Orange,
                    )
                    MiniStat(
                        "برد",
                        "${String.format(java.util.Locale.US, "%.0f", arc.metrics.winRatePct)}%",
                        AurumColors.Gold,
                    )
                }
                Spacer(Modifier.height(8.dp))

                // دکمهٔ اعمال — تنها راه تغییر موجودی از این صفحه، با قفل تکرار.
                val applied = appliedEntry
                if (applied != null) {
                    Text(
                        text = "✅ قبلاً اعمال شده: " + fmtUsd(applied.netUsd) +
                            " در " + BacktestTradeArchive.dayOf(applied.appliedAt) +
                            " · موجودی آن زمان " + fmtUsd(applied.balanceAfter),
                        color = AurumColors.Green,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Text(
                        text = "همین آرشیو دوباره اعمال نمی‌شود. برای اعمال دوباره، بک‌تست را با " +
                            "داده یا تنظیم متفاوت اجرا کنید (شناسهٔ آرشیو عوض می‌شود).",
                        color = AurumColors.TextMuted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                } else {
                    ActionButton(
                        label = if (busy) "…" else "💰 اعمال ${fmtUsd(arc.metrics.netUsd)} روی سرمایه",
                        enabled = !busy && arc.metrics.trades > 0,
                        color = if (arc.metrics.netUsd >= 0) AurumColors.Green else AurumColors.Red,
                        onClick = { vm.applyArchiveToBalance() },
                    )
                    Text(
                        text = "این دکمه فقط موجودی حساب را جابه‌جا می‌کند؛ هیچ معاملهٔ کاغذی ساخته " +
                            "نمی‌شود و ژورنال واقعی دست نمی‌خورد. هر آرشیو فقط یک‌بار قابل اعمال است.",
                        color = AurumColors.TextMuted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                }
                // ── ژورنال بدون پول ───────────────────────────────────────────────
                // «سود هر معامله برود در ژورنال بنشیند، ولی پولش وارد پولم نشود.»
                // هر ردیف با نشان «بک‌تست» ثبت می‌شود، درس خودش را می‌گیرد، و موجودی
                // حساب دست‌نخورده می‌ماند.
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    label = if (busy) "…" else "📓 ثبت معامله‌ها در ژورنال (بدون تغییر موجودی)",
                    enabled = !busy && arc.rows.isNotEmpty(),
                    color = AurumColors.Gold,
                    onClick = { vm.recordArchiveToJournal() },
                )
                Text(
                    text = "${fmtCount(arc.rows.size)} ردیف با نشان «بک‌تست» در ژورنال ثبت می‌شود؛ " +
                        "از هر کدام یک درس ساخته می‌شود و هوش با آموخته‌های تازه به‌روز می‌شود. " +
                        "موجودی حساب حتی یک سنت هم تغییر نمی‌کند.",
                    color = AurumColors.TextMuted,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )

                val files = archiveFiles
                if (files != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "فایل‌ها: ${files.csv.name} · ${files.json.name}",
                        color = AurumColors.Cyan,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ActionButton("📤 ارسال CSV", !busy, AurumColors.Cyan) { vm.shareArchive(csv = true) }
                        ActionButton("📄 ارسال JSON", !busy, AurumColors.TextSecondary) { vm.shareArchive(csv = false) }
                        ActionButton("👁 باز کردن CSV", !busy, AurumColors.Gold) { vm.openArchive(csv = true) }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // جدول روزانه: «سود متناسب با آن روز».
                if (arc.days.isNotEmpty()) {
                    Text(
                        "📅 سود/زیان به‌تفکیک روز بسته‌شدن معامله (UTC):",
                        color = AurumColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt).padding(6.dp),
                    ) {
                        Text("روز", color = AurumColors.TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text("معامله", color = AurumColors.TextMuted, fontSize = 9.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center)
                        Text("برد", color = AurumColors.TextMuted, fontSize = 9.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center)
                        Text("خالص", color = AurumColors.TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                    arc.days.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                BacktestTradeArchive.prettyDay(d.day),
                                color = AurumColors.TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${d.trades}",
                                color = AurumColors.TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(0.8f),
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "${String.format(java.util.Locale.US, "%.0f", d.winRatePct)}%",
                                color = AurumColors.TextMuted,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(0.8f),
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                fmtUsd(d.netUsd),
                                color = if (d.netUsd >= 0) AurumColors.Green else AurumColors.Red,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }

                // فهرست کامل معامله‌ها — همان چیزی که در CSV هم هست.
                if (arc.rows.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    var showAll by remember(arc.contentHash) { mutableStateOf(false) }
                    Text(
                        "🧾 معامله‌ها (${arc.rows.size}):",
                        color = AurumColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    val visible = if (showAll) arc.rows else arc.rows.take(12)
                    visible.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "#${r.tradeNumber} ${if (r.action == com.aurum.edge.domain.SignalAction.BUY) "▲" else "▼"} " +
                                    BacktestTradeArchive.prettyDay(r.entryDateFa) +
                                    " · ${r.exitReasonFa}",
                                color = AurumColors.TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                "${fmtPrice(r.entryPrice)} → ${r.exitPrice?.let { fmtPrice(it) } ?: "—"}",
                                color = AurumColors.TextMuted,
                                fontSize = 8.sp,
                                modifier = Modifier.weight(1.1f),
                                maxLines = 1,
                            )
                            Text(
                                "${String.format(java.util.Locale.US, "%+.2f", r.rMultiple)}R",
                                color = AurumColors.TextMuted,
                                fontSize = 9.sp,
                                modifier = Modifier.width(44.dp),
                                textAlign = TextAlign.End,
                            )
                            Text(
                                fmtUsd(r.netPnlUsd),
                                color = if (r.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(72.dp),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                    if (arc.rows.size > 12) {
                        Spacer(Modifier.height(4.dp))
                        Pill(
                            text = if (showAll) "بستن فهرست" else "نمایش همهٔ ${arc.rows.size} معامله",
                            color = AurumColors.Gold,
                            modifier = Modifier.clickable { showAll = !showAll },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "شناسهٔ آرشیو: ${arc.contentHash}",
                        color = AurumColors.TextMuted,
                        fontSize = 8.sp,
                        lineHeight = 12.sp,
                    )
                }
            }
        }

        // ── ۴. نتیجه ───────────────────────────────────────────────────────
        result?.let { res ->
            SectionCard(initiallyExpanded = true, title = "📊 نتیجهٔ بک‌تست") {
                if (resultIsStale) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.Orange.copy(alpha = 0.12f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "این نتیجه برای نقطهٔ قبلیِ چارت است. برای بازهٔ فعلی دوباره «اجرای بک‌تست» را بزنید.",
                            color = AurumColors.Orange,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                res.totals?.let { totals -> StatsGrid(totals, res.config.startEquityUsd) }
                // ── گزارش ماه‌به‌ماه ──────────────────────────────────────────────
                // دادهٔ سنگین ماه‌به‌ماه اجرا می‌شود؛ نتیجهٔ هر ماه باید دیده شود، نه فقط عدد کل.
                if (res.periodSummariesFa.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "📅 اجرای ماه‌به‌ماه (${res.periodSummariesFa.size} ماه) — هر ماه مستقل سنجیده شد",
                        color = AurumColors.TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    res.periodSummariesFa.forEach { row ->
                        Text(
                            "• $row",
                            color = AurumColors.TextMuted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                    }
                }
                // هزینهٔ اجرا، پنهان‌ترین دلیل «چرا سود نمی‌دهد» است: کارمزد درصدی از قیمت است
                // ولی ۱R از ATR می‌آید؛ روی نماد ارزان با ۱R کوچک، کارمزد نصف ریسک را می‌خورد.
                // عدد این‌جا همان چیزی است که شرط ۱۷ در موتور می‌سنجد.
                run {
                    val costPct = IchimokuStrategyEngine.roundTripCostPct(
                        res.config.symbol,
                        res.trades.lastOrNull()?.entryPrice ?: 0.0,
                    )
                    val medianOneRPct = res.trades
                        .map { kotlin.math.abs(it.entryPrice - it.stopLoss) / it.entryPrice * 100.0 }
                        .sorted()
                        .let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                    if (costPct > 0.0) {
                        Spacer(Modifier.height(6.dp))
                        val feeInR = if (medianOneRPct > 0.0) costPct * 100.0 / medianOneRPct else 0.0
                        Text(
                            text = "💸 هزینهٔ رفت‌وبرگشت ${String.format(java.util.Locale.US, "%.3f", costPct * 100.0)}٪ قیمت" +
                                " · میانهٔ ۱R ${String.format(java.util.Locale.US, "%.2f", medianOneRPct)}٪" +
                                if (feeInR > 0.0) " · کارمزد ≈ ${String.format(java.util.Locale.US, "%.2f", feeInR)}R از هر معامله" else "",
                            color = if (feeInR > 0.35) AurumColors.Orange else AurumColors.TextMuted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                        if (feeInR > 0.35) {
                            Text(
                                text = "برای این نماد و تایم‌فریم، ۱R کوچک‌تر از آن است که کارمزد را بپوشاند؛ " +
                                    "تایم‌فریم بالاتر یا نمادی با اسپرد کمتر نتیجهٔ واقعی‌تری می‌دهد (شرط ۱۷ همین را می‌سنجد).",
                                color = AurumColors.Orange,
                                fontSize = 9.sp,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "بازه: ${formatShortTime(res.firstTime)} → ${formatShortTime(res.lastTime)} · " +
                        "${fmtCount(res.evaluatedSteps)} قدم ارزیابی‌شده · ${res.elapsedMs} میلی‌ثانیه",
                    color = AurumColors.TextMuted,
                    fontSize = 9.sp,
                )
                if (res.stats.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text("مقایسهٔ استراتژی‌ها", color = AurumColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        res.stats.filter { it.trades > 0 }.sortedByDescending { it.netPnlUsd }.forEach { st ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AurumColors.SurfaceAlt)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(st.strategyTitleFa, color = AurumColors.TextPrimary, fontSize = 10.sp, maxLines = 2)
                                    Text(
                                        "${st.trades} معامله · برد ${st.winRatePct}% · PF ${st.profitFactor}",
                                        color = AurumColors.TextMuted,
                                        fontSize = 9.sp,
                                    )
                                }
                                Text(
                                    fmtUsd(st.netPnlUsd),
                                    color = if (st.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("آخرین معامله‌ها", color = AurumColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    res.trades.takeLast(15).reversed().forEach { trade ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AurumColors.SurfaceAlt)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (trade.action == com.aurum.edge.domain.SignalAction.BUY) "▲" else "▼",
                                color = if (trade.action == com.aurum.edge.domain.SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                                fontSize = 12.sp,
                                modifier = Modifier.width(16.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${formatShortTime(trade.entryTime)} → ${
                                        trade.exitTime?.let { formatShortTime(it) } ?: "باز"
                                    } · ${trade.exitReason.labelFa}",
                                    color = AurumColors.TextPrimary,
                                    fontSize = 10.sp,
                                )
                                Text(
                                    "ورود ${fmtPrice(trade.entryPrice)} · SL ${fmtPrice(trade.stopLoss)} · " +
                                        "TP ${fmtPrice(trade.takeProfit)} · ${trade.barsHeld} کندل",
                                    color = AurumColors.TextMuted,
                                    fontSize = 9.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    fmtUsd(trade.netPnlUsd),
                                    color = if (trade.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("${trade.rMultiple}R", color = AurumColors.TextSecondary, fontSize = 9.sp)
                            }
                        }
                    }
                }
                // ── دروازهٔ هم‌گرایی مکتبی ────────────────────────────────────
                // «۰ معامله» بدون عدد، ابهام است. این‌جا هم بهترین امتیاز بازه و هم میانگین
                // نشان داده می‌شود تا معلوم باشد استراتژی چقدر نزدیک بوده و کدام لایه کم آورده.
                if (res.bestBookGatePct > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniStat(
                            "بهترین هم‌گرایی",
                            "${String.format(java.util.Locale.US, "%.0f", res.bestBookGatePct)}%",
                            if (res.bestBookGatePct >= 80.0) AurumColors.Green else AurumColors.Gold,
                        )
                        MiniStat(
                            "میانگین بازه",
                            "${String.format(java.util.Locale.US, "%.0f", res.averageBookGatePct)}%",
                            AurumColors.TextSecondary,
                        )
                        MiniStat(
                            "برترین حالت",
                            if (res.trades.isNotEmpty()) "✓ ورود" else "زیر آستانه",
                            if (res.trades.isNotEmpty()) AurumColors.Green else AurumColors.Orange,
                        )
                    }
                    if (res.bestBookGateNoteFa.isNotBlank()) {
                        Text(
                            text = "🧭 ${res.bestBookGateNoteFa}",
                            color = AurumColors.TextMuted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                    }
                    Text(
                        text = "آستانه‌ها: کل ≥ ${IchimokuStrategyEngine.BOOK_MIN_TOTAL_PCT.toInt()}٪ · " +
                            "لایهٔ تعادل و ماشه هرکدام ≥ ${IchimokuStrategyEngine.BOOK_MIN_LAYER_PCT.toInt()}٪ · " +
                            "رژیم و اقتصاد اجرا وتوی سخت‌اند.",
                        color = AurumColors.TextMuted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                }
                // چه با معامله و چه بی‌معامله، سدهای اصلی باید دیده شوند: برای گرفتن بک‌تست
                // معنادار باید بدانیم کدام شرط جلوی ورود را گرفته است.
                if (res.trades.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "🔎 سدهای اصلی این بازه (شرط‌هایی که بیشترین بار جلوی ورود را گرفتند):",
                        color = AurumColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    res.topBlockers.forEach { (title, count) ->
                        Text(
                            "• $title — $count بار رد شد",
                            color = AurumColors.TextMuted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                    }
                }
                if (res.trades.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // تفکیک دو حالت، چون درمانشان یکی نیست: «هیچ‌جا نزدیک نشد» یعنی آستانه/بازه،
                    // «نزدیک شد ولی وتو شد» یعنی یک شرط ساختاری جلوی ورود را گرفته است.
                    val nearMiss = res.bestBookGatePct >= IchimokuStrategyEngine.BOOK_WATCH_TOTAL_PCT
                    Text(
                        if (nearMiss)
                            "این استراتژی تا آستانهٔ ورود رسید ولی وتوی سخت یا یک لایه جلوی آن را گرفت. " +
                                "بیشترین شرطی که هم‌زمان پاس شد: ${res.maxConditionsPassed} از ${17} — ولی " +
                                "دروازهٔ مکتبی «امتیاز» را می‌سنجد، نه شمارش شرط. سدهای اصلی همین بازه:"
                        else
                            "این استراتژی در این بازه هیچ ورودی معتبری نداشت و به آستانهٔ آماده هم نرسید " +
                                "(بهترین هم‌گرایی ${String.format(java.util.Locale.US, "%.0f", res.bestBookGatePct)}٪ " +
                                "در برابر آستانهٔ ${IchimokuStrategyEngine.BOOK_WATCH_TOTAL_PCT.toInt()}٪). " +
                                "بیشترین شرطی که هم‌زمان پاس شد: ${res.maxConditionsPassed} از ${17}. سدهای اصلی:",
                        color = AurumColors.Orange,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    res.topBlockers.forEach { (title, count) ->
                        Text(
                            "• $title — $count بار رد شد",
                            color = AurumColors.TextMuted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "راه‌حل: بازهٔ بزرگ‌تری از CSV بگیرید (چند ماه)، تایم‌فریم پایه را با استراتژی " +
                            "هم‌خوان کنید، یا «همهٔ استراتژی‌ها» را روشن کنید تا استراتژی دیگری شانس داشته باشد.",
                        color = AurumColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }

        // ── ۵. گزارش تمرین عصبی ─────────────────────────────────────────────
        training?.let { report ->
            SectionCard(initiallyExpanded = true, title = "🧠 آنچه شبکهٔ عصبی از این بک‌تست یاد گرفت") {
                Text(
                    text = "این تمرین در اتاق تمرین جدا انجام شد. برای اثر گذاشتن روی معاملات واقعی، " +
                        "تصمیمش با شماست — این صفحه هیچ پارامتری را خودکار عوض نمی‌کند.",
                    color = AurumColors.Cyan,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniStat("نمونه", "${report.samplesFed}", AurumColors.Gold)
                    MiniStat("برد", "${report.wins}", AurumColors.Green)
                    MiniStat("باخت", "${report.losses}", AurumColors.Red)
                    MiniStat("میانگین R", "${report.avgR}", AurumColors.Cyan)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniStat("احتمال برد پیش‌بینی", "${report.predictedWinRatePct}%", AurumColors.Purple)
                    MiniStat("دقت درون‌نمونه‌ای", "${report.inSampleAccuracyPct}%", AurumColors.TextSecondary)
                }
                Spacer(Modifier.height(10.dp))
                Text("وزن ستون‌های ایچیموکو در این بازه", color = AurumColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                report.columns.forEach { col ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(col.titleFa, color = AurumColors.TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text(
                            String.format(java.util.Locale.US, "%+.3f", col.weight),
                            color = when {
                                col.weight > 0.08 -> AurumColors.Green
                                col.weight < -0.08 -> AurumColors.Red
                                else -> AurumColors.TextMuted
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(col.directionFa, color = AurumColors.TextMuted, fontSize = 8.sp, modifier = Modifier.width(120.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("پارامترهای پیشنهادی (اعمال نشده)", color = AurumColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                report.parameters.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.titleFa, color = AurumColors.TextSecondary, fontSize = 10.sp)
                            Text(p.basisFa, color = AurumColors.TextMuted, fontSize = 8.sp)
                        }
                        Text(
                            fmtPrice(p.currentValue) + " → " + fmtPrice(p.suggestedValue),
                            color = if (kotlin.math.abs(p.suggestedValue - p.currentValue) < 1e-9) AurumColors.TextMuted else AurumColors.Gold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                report.notesFa.forEach { note ->
                    Text("• $note", color = AurumColors.TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(text = "پاک کردن این گزارش", color = AurumColors.TextSecondary, modifier = Modifier.clickable { vm.clearTraining() })
                    // پاک‌کردن حافظه: حالا که نمونه‌ها انباشته می‌شوند، بدون این دکمه یک
                    // دورهٔ دادهٔ بد برای همیشه در حافظه می‌ماند.
                    Pill(
                        text = "🧠 پاک کردن حافظهٔ یادگیری",
                        color = AurumColors.Orange,
                        modifier = Modifier.clickable { vm.resetNeuralMemory() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "⚠️ بک‌تست با دادهٔ تاریخی، وعدهٔ آینده نیست: اسپرد واقعی، لغزش قیمت و اخبار " +
                "ناشناخته در آن ساده‌سازی شده‌اند.",
            color = AurumColors.TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun StrategyPicker(
    selectedId: String,
    runAll: Boolean,
    onPick: (String) -> Unit,
    onAll: () -> Unit,
) {
    val models = remember { BitcoinStrategyModel.entries.filterNot { it.isTemporarilyDisabled } }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(if (runAll) AurumColors.Purple.copy(alpha = 0.15f) else AurumColors.SurfaceAlt)
                .clickable { onAll() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("همهٔ استراتژی‌ها با هم (مقایسه)", color = AurumColors.TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (runAll) Pill("انتخاب‌شده", AurumColors.Purple)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            models.forEach { model ->
                val isSelected = !runAll && model.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) AurumColors.Gold.copy(alpha = 0.12f) else AurumColors.SurfaceAlt)
                        .clickable { onPick(model.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.titleFa,
                            color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                        )
                        Text(model.badgeFa, color = AurumColors.TextMuted, fontSize = 8.sp)
                    }
                    if (isSelected) Pill("✓", AurumColors.Gold)
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: BacktestReplayEngine.Stats, startEquity: Double) {
    val pnlColor = if (stats.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("معاملات", "${stats.trades}", AurumColors.Gold)
            MiniStat("نرخ برد", "${stats.winRatePct}%", AurumColors.Cyan)
            MiniStat("سود خالص", fmtUsd(stats.netPnlUsd), pnlColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("ضریب سود", "${stats.profitFactor}", AurumColors.Purple)
            MiniStat("انتظار R", "${stats.expectancyR}", AurumColors.Cyan)
            MiniStat("شارپ/معامله", "${stats.sharpePerTrade}", AurumColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("حداکثر افت", fmtUsd(-stats.maxDrawdownUsd), AurumColors.Orange)
            MiniStat("افت %", "${stats.maxDrawdownPct}%", AurumColors.Orange)
            MiniStat("سرمایهٔ پایانی", fmtUsd(startEquity + stats.netPnlUsd), pnlColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("خرید/فروش", "${stats.longTrades}/${stats.shortTrades}", AurumColors.TextSecondary)
            MiniStat("برد خرید", "${stats.longWinRatePct}%", AurumColors.Green)
            MiniStat("برد فروش", "${stats.shortWinRatePct}%", AurumColors.Red)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("حد ضرر", "${stats.stopLossExits}", AurumColors.Red)
            MiniStat("حد سود", "${stats.takeProfitExits}", AurumColors.Green)
            MiniStat("مدیریت/زمان", "${stats.smartExits + stats.timeStopExits}", AurumColors.TextSecondary)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MiniStat(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(AurumColors.SurfaceAlt)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = AurumColors.TextMuted, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AurumColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.size(width = 36.dp, height = 22.dp),
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) color.copy(alpha = 0.16f) else AurumColors.SurfaceAlt)
            .border(1.dp, if (enabled) color.copy(alpha = 0.5f) else AurumColors.Line, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) color else AurumColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun fmtCount(v: Int): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(v)

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun fmtPrice(v: Double): String = when {
    v >= 1000.0 -> String.format(java.util.Locale.US, "%.2f", v)
    v >= 10.0 -> String.format(java.util.Locale.US, "%.3f", v)
    v >= 1.0 -> String.format(java.util.Locale.US, "%.4f", v)
    else -> String.format(java.util.Locale.US, "%.5f", v)
}


/**
 * بخش «بک‌آزمون پس‌زمینه».
 *
 * هر کار یک ردیف است: نام داده/استراتژی، وضعیت، خلاصهٔ نتیجه و سه دکمه —
 * «دیدن نتیجه»، «تغییر تنظیمات» (همان اجرا را در فرم می‌نشاند) و «لغو/حذف».
 * نتیجه روی حافظهٔ داخلی می‌مانَد تا هر وقت خواستی برگردی و پارامتر را عوض کنی.
 */
@Composable
private fun BackgroundJobsSection(
    jobs: List<com.aurum.edge.data.BacktestJob>,
    enabled: Boolean,
    onQueue: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    SectionCard(initiallyExpanded = true, title = "🌙 بک‌آزمون پس‌زمینه") {
        Text(
            text = "کار را این‌جا صف کن، از اپ بیرون برو؛ اجرا در پس‌زمینه ادامه پیدا می‌کند " +
                "(شرط: باتری و حافظه کم نباشد) و نتیجه روی گوشی ذخیره می‌شود. بعداً همان کار را " +
                "باز کن، تنظیماتش را تغییر بده و دوباره اجرا کن. این بخش به ژورنال و حساب واقعی دست نمی‌زند.",
            color = AurumColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("🚀 صف‌کردن اجرا", enabled = enabled, color = AurumColors.Cyan) { onQueue() }
            ActionButton("🔄 تازه‌سازی", enabled = true, color = AurumColors.TextSecondary) { onRefresh() }
            if (jobs.any { it.isFinished }) {
                ActionButton("🧹 پاک‌کردن تمام‌شده‌ها", enabled = true, color = AurumColors.TextSecondary) {
                    onClearFinished()
                }
            }
        }
        if (jobs.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "هنوز کار پس‌زمینه‌ای ثبت نشده است.",
                color = AurumColors.TextMuted,
                fontSize = 11.sp,
            )
            return@SectionCard
        }
        Spacer(Modifier.height(8.dp))
        val statusFa = mapOf(
            com.aurum.edge.data.BacktestJob.STATUS_QUEUED to "در صف",
            com.aurum.edge.data.BacktestJob.STATUS_RUNNING to "در حال اجرا",
            com.aurum.edge.data.BacktestJob.STATUS_DONE to "تمام‌شده",
            com.aurum.edge.data.BacktestJob.STATUS_FAILED to "ناموفق",
            com.aurum.edge.data.BacktestJob.STATUS_CANCELLED to "لغوشده",
        )
        jobs.forEach { job ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("${statusFa[job.status] ?: job.status}", taskStatusColor(job.status))
                    Pill(job.spec.dataSetName.take(26), AurumColors.TextSecondary)
                    Pill(job.spec.strategyId.take(22), AurumColors.TextMuted)
                }
                val r = job.result
                Text(
                    text = r?.summaryFa ?: job.messageFa.ifBlank { "—" },
                    color = if (r != null) AurumColors.TextPrimary else AurumColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("👁 نتیجه", enabled = r != null, color = AurumColors.Gold) { onOpen(job.spec.id) }
                    ActionButton("✏️ تغییر تنظیمات", enabled = true, color = AurumColors.Cyan) { onEdit(job.spec.id) }
                    if (!job.isFinished) {
                        ActionButton("✖ لغو", enabled = true, color = AurumColors.TextSecondary) { onCancel(job.spec.id) }
                    } else {
                        ActionButton("🗑 حذف", enabled = true, color = AurumColors.TextSecondary) { onDelete(job.spec.id) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** رنگ وضعیت کار پس‌زمینه؛ فقط برای خوانایی فهرست، بدون معنی استراتژیک. */
private fun taskStatusColor(status: String): Color = when (status) {
    com.aurum.edge.data.BacktestJob.STATUS_DONE -> AurumColors.Green
    com.aurum.edge.data.BacktestJob.STATUS_RUNNING -> AurumColors.Gold
    com.aurum.edge.data.BacktestJob.STATUS_FAILED -> AurumColors.Red
    else -> AurumColors.TextSecondary
}
