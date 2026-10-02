package com.aurum.edge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.time.YearMonth
import java.time.ZoneOffset

/** Native candle chart/replay: app-owned candles in, app-owned SignalEngine out. */
@Composable
fun LearnScreen(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val market by viewModel.market.collectAsStateWithLifecycle()
    val learn by viewModel.learn.collectAsStateWithLifecycle()
    val replay by viewModel.replay.collectAsStateWithLifecycle()
    val replayDecisions by viewModel.replayDecisions.collectAsStateWithLifecycle()
    val walkForward by viewModel.walkForward.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var interval by remember { mutableStateOf(settings.interval) }
    var bars by remember { mutableStateOf(HistoryPolicy.chartTargetCandles(settings.symbol, settings.interval)) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var spread by remember { mutableStateOf(settings.spreadPrice.toString()) }
    var commission by remember { mutableStateOf(settings.commissionPerOz.toString()) }

    var mtLink by remember { mutableStateOf("") }
    var mtSymbol by remember { mutableStateOf(settings.symbol) }
    var mtTimezone by remember { mutableStateOf("+00:00") }
    var mtUri by remember { mutableStateOf<Uri?>(null) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> mtUri = uri }

    val lastCompleteMonth = remember { YearMonth.now(ZoneOffset.UTC).minusMonths(1) }
    var histYear by remember { mutableStateOf(lastCompleteMonth.year.toString()) }
    var histMonth by remember { mutableStateOf(lastCompleteMonth.monthValue.toString()) }
    var histUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var histInterval by remember { mutableStateOf(Interval.M5) }
    val histPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        histUris = uris.sortedBy { it.toString() }
    }

    val runBalance = balance.toDoubleOrNull() ?: settings.accountBalance
    val runRisk = (risk.toDoubleOrNull() ?: settings.riskPercent).coerceIn(0.1, 5.0)
    val runSpread = spread.toDoubleOrNull() ?: settings.spreadPrice
    val runCommission = commission.toDoubleOrNull() ?: settings.commissionPerOz

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard(
            title = "چارت داخلی موتور",
            subtitle = "${market.symbol} · ${market.interval.label} · ${market.candles.size} کندل · ${market.feed.provider}",
        ) {
            if (market.candles.isEmpty()) {
                Text("کندل واقعی هنوز آماده نیست.", style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            } else {
                CandleChart(
                    candles = market.candles,
                    interval = market.interval,
                    signal = market.signal,
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    showIchimoku = true,
                    showLevels = true,
                    showVolume = true,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("آخر: ${market.lastPrice?.let(::formatPrice) ?: "—"}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary, modifier = Modifier.weight(1f))
                    Text("موتور: ${market.signal?.action?.name ?: "—"}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan, modifier = Modifier.weight(1f))
                }
            }
        }

        SectionCard(
            title = "Replay / Backtest با همین موتور",
            subtitle = "کندل واقعی دانلود می‌شود؛ SignalEngine داخل اپ دوباره روی آن محاسبه می‌کند",
        ) {
            IntervalRows(interval) { interval = it }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(HistoryPolicy.TARGET_CANDLES, HistoryPolicy.MAX_TWELVE_CANDLES, HistoryPolicy.DEEP_CHART_CANDLES).distinct().forEach { count ->
                    FilterChip(
                        selected = bars == count,
                        onClick = { bars = count },
                        label = { Text("$count", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AurumColors.Cyan.copy(alpha = 0.18f),
                            selectedLabelColor = AurumColors.Cyan,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(balance, { balance = it }, label = { Text("موجودی") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(risk, { risk = it }, label = { Text("ریسک %") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(spread, { spread = it }, label = { Text("اسپرد") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(commission, { commission = it }, label = { Text("کمیسیون") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runLearn(interval, bars, runBalance, runRisk, runSpread, runCommission, settings.minConfidence) },
                    enabled = learn !is LearnState.Loading && walkForward !is WalkForwardState.Loading,
                    modifier = Modifier.weight(1f),
                ) { Text("ساخت چارت و محاسبه موتور", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { viewModel.runWalkForward(interval, bars, runBalance, runRisk, runSpread, runCommission, settings.minConfidence) },
                    enabled = learn !is LearnState.Loading && walkForward !is WalkForwardState.Loading,
                    modifier = Modifier.weight(1f),
                ) { Text("۷۰/۳۰") }
            }
        }

        LearnResult(learn)
        ReplayPanel(replay, viewModel, replayDecisions)
        WalkForwardResult(walkForward)

        SectionCard("ورود دیتای کندل", "CSV/MT یا HistData؛ فقط برای پژوهش و همین چارت") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mtSymbol, { mtSymbol = it }, label = { Text("نماد") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(mtTimezone, { mtTimezone = it }, label = { Text("UTC") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                mtLink,
                { mtLink = it },
                label = { Text("لینک HTTPS CSV اختیاری") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) },
                    modifier = Modifier.weight(1f),
                ) { Text("انتخاب CSV") }
                Button(
                    onClick = { viewModel.importMetaTrader(mtUri, mtLink, mtSymbol, interval, mtTimezone, runBalance, runRisk, runSpread, runCommission, settings.minConfidence) },
                    modifier = Modifier.weight(1f),
                ) { Text("محاسبه فایل") }
            }
            mtUri?.let { Text("CSV: ${it.lastPathSegment?.takeLast(42) ?: "انتخاب شد"}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }

            Text("HistData XAU/USD M1", style = MaterialTheme.typography.labelMedium, color = AurumColors.Gold, modifier = Modifier.padding(top = 12.dp))
            IntervalRows(histInterval, entries = listOf(Interval.M1, Interval.M5, Interval.M15, Interval.M30, Interval.H1)) { histInterval = it }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(histYear, { histYear = it.take(4) }, label = { Text("سال") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(histMonth, { histMonth = it.take(2) }, label = { Text("ماه") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            val period = runCatching { YearMonth.of(histYear.toInt(), histMonth.toInt()) }.getOrNull()
                ?.takeIf { it.year >= 2009 && it <= lastCompleteMonth }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (period != null) runCatching {
                            uriHandler.openUri("https://www.histdata.com/download-free-forex-historical-data/?/ascii/1-minute-bar-quotes/xauusd/${period.year}/${period.monthValue}")
                        }
                    },
                    enabled = period != null,
                    modifier = Modifier.weight(1f),
                ) { Text("دانلود رسمی") }
                OutlinedButton(
                    onClick = { histPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                ) { Text("انتخاب ZIP/CSV") }
            }
            if (histUris.isNotEmpty()) Text("${histUris.size} فایل انتخاب شد", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            Button(
                onClick = { viewModel.importHistData(histUris, histInterval, runBalance, runRisk, runSpread, runCommission, settings.minConfidence) },
                enabled = histUris.isNotEmpty() && learn !is LearnState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("محاسبه HistData روی چارت") }
        }
    }
}

@Composable
private fun IntervalRows(
    selected: Interval,
    entries: List<Interval> = Interval.entries.toList(),
    onSelect: (Interval) -> Unit,
) {
    entries.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { entry ->
                FilterChip(
                    selected = selected == entry,
                    onClick = { onSelect(entry) },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
            repeat(4 - row.size) { Column(Modifier.weight(1f)) {} }
        }
    }
}

@Composable
private fun LearnResult(state: LearnState) {
    when (state) {
        LearnState.Idle -> Unit
        is LearnState.Loading -> SectionCard("دریافت کندل", state.step) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = AurumColors.Gold, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                Text("بدون کندل معتبر نتیجه ساخته نمی‌شود.", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
        }
        is LearnState.Failed -> SectionCard("محاسبه نشد", "دیتا معتبر نبود") {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        }
        is LearnState.Done -> {
            BacktestReport(state)
            PerformancePanel(PerformanceMetrics.fromBacktest(state.result), "${state.result.symbol} · ${state.result.dataSource}")
        }
    }
}

@Composable
private fun WalkForwardResult(state: WalkForwardState) {
    when (state) {
        WalkForwardState.Idle -> Unit
        is WalkForwardState.Loading -> SectionCard("تست خارج از نمونه", state.step) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = AurumColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                Text("۷۰٪ داخل نمونه، ۳۰٪ خارج نمونه.", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
        }
        is WalkForwardState.Failed -> SectionCard("۷۰/۳۰ اجرا نشد", "خطای دیتا") {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        }
        is WalkForwardState.Done -> {
            WalkForwardReport(state)
            PerformancePanel(PerformanceMetrics.fromBacktest(state.result.outOfSample), "${state.result.outOfSample.symbol} · خارج نمونه · ${formatDateTime(state.result.splitTime)}")
        }
    }
}
