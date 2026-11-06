package com.aurum.edge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.aurum.edge.core.BitcoinStrategyModel
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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val status by vm.statusFa.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = if (busy) "…" else "➕ افزودن CSV",
                    enabled = !busy,
                    color = AurumColors.Gold,
                    onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
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
            status.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AurumColors.Cyan, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (progress <= 0.0) 0.05f else progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = AurumColors.Gold,
                    trackColor = AurumColors.SurfaceAlt,
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
                    trades = result?.trades.orEmpty(),
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
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "بک‌تست روی ${fmtCount(endIndex)} کندلِ تا این نقطه اجرا می‌شود؛ کندل‌های آیندهٔ " +
                        "فایل دیده نمی‌شوند (بدون نگاه به آینده).",
                    color = AurumColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
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

        // ── ۴. نتیجه ───────────────────────────────────────────────────────
        result?.let { res ->
            SectionCard(initiallyExpanded = true, title = "📊 نتیجهٔ بک‌تست") {
                res.totals?.let { totals -> StatsGrid(totals, res.config.startEquityUsd) }
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
                if (res.trades.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "این استراتژی در این بازه هیچ ورودی معتبری نداشت. بیشترین شرطی که هم‌زمان پاس شد: " +
                            "${res.maxConditionsPassed} از ۱۶. سدهای اصلی همین بازه:",
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
                Pill(text = "پاک کردن این گزارش", color = AurumColors.TextSecondary, modifier = Modifier.clickable { vm.clearTraining() })
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
