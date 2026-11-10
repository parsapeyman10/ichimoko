package com.aurum.edge.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.CandleBackfillSummary
import com.aurum.edge.core.DayPnlSummary
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AiTradeVerdict
import com.aurum.edge.data.AvalAiAdvisor
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.InstitutionalTearSheetReport
import com.aurum.edge.data.JournalExportEngine
import com.aurum.edge.data.JournalExportFormat
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.data.SavedJournalExportFile
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.TradingViewPositionBox
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.theme.AurumColors
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val WEEKDAY_HEADERS_FA = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")
private val RISK_PRESETS = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0)
private val RR_PRESETS = listOf(1.5, 2.0, 3.0)

/**
 * کف ارتفاع هر خانهٔ تقویم.
 *
 * قبلاً خانه‌ها فقط `aspectRatio(1f)` داشتند و ارتفاع ردیف از محتوای همان ردیف می‌آمد؛ نتیجه
 * این بود که ردیف بدون معامله کوتاه‌تر می‌شد و شبکه «دندانه‌دار» می‌شد. با کف ثابت، همهٔ
 * ردیف‌ها هم‌ارتفاع‌اند حتی وقتی هیچ خانه‌ای معامله ندارد.
 */
private val CALENDAR_CELL_MIN_HEIGHT = 34.dp

@Composable
fun JournalRiskScreen(
    viewModel: AurumViewModel,
    onChartSymbol: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val backfillSummaries by viewModel.backfillSummaries.collectAsStateWithLifecycle()
    val isBackfilling by viewModel.isBackfilling.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()
    val lastAiVerdict by viewModel.lastAiVerdict.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.activeRiskSymbol.collectAsStateWithLifecycle()
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val sessionExitEstimates by viewModel.sessionExitEstimates.collectAsStateWithLifecycle()
    val sessionExitNote by viewModel.sessionExitLearningNote.collectAsStateWithLifecycle()

    val openTrades = remember(trades) { trades.filter { it.isOpen } }
    val closedTrades = remember(trades) { trades.filter { !it.isOpen } }
    val minuteBucket = now / 60_000L

    val riskLimits = remember(settings, trades.size, openTrades.size, minuteBucket) {
        RiskAndCalendarEngine.evaluateRiskLimits(
            accountBalance = settings.accountBalance,
            initialCapital = settings.initialCapital,
            dayOpeningEquity = viewModel.dayOpeningEquity(now),
            dailyLossLimitPercent = settings.dailyLossLimitPercent,
            maxDrawdownLimitPercent = settings.maxDrawdownLimitPercent,
            trades = trades,
            now = now,
        )
    }

    LaunchedEffect(activeSymbol) {
        viewModel.refreshSymbolLivePrice(activeSymbol)
    }

    var subPage by rememberSaveable { mutableIntStateOf(0) }
    var selectedStrategyFilter by rememberSaveable { mutableStateOf("ALL") }

    val strategyCategories = remember(trades.size, trades.firstOrNull()?.id, trades.firstOrNull()?.closedAt, settings.customStrategies) {
        RiskAndCalendarEngine.categorizeTradesByStrategy(trades, settings.customStrategies)
    }

    val filteredTrades = remember(trades, selectedStrategyFilter) {
        if (selectedStrategyFilter == "ALL") trades else trades.filter { it.strategyId == selectedStrategyFilter }
    }
    val filteredOpenTrades = remember(filteredTrades) { filteredTrades.filter { it.isOpen } }
    val filteredClosedTrades = remember(filteredTrades) { filteredTrades.filter { !it.isOpen } }
    val filteredStats = remember(filteredTrades, stats, selectedStrategyFilter) {
        if (selectedStrategyFilter == "ALL") {
            stats
        } else {
            val closed = filteredClosedTrades.filter { it.pnlUsd != null }
            val wins = closed.filter { (it.pnlUsd ?: 0.0) > 0.0 }
            val losses = closed.filter { (it.pnlUsd ?: 0.0) <= 0.0 }
            val grossWin = wins.sumOf { it.pnlUsd ?: 0.0 }
            val grossLoss = abs(losses.sumOf { it.pnlUsd ?: 0.0 })
            val rMultiples = closed.mapNotNull { it.rMultiple }
            com.aurum.edge.data.JournalStats(
                total = closed.size,
                open = filteredOpenTrades.size,
                wins = wins.size,
                losses = losses.size,
                winRate = if (closed.isEmpty()) null else wins.size * 100.0 / closed.size,
                profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
                netPnl = closed.sumOf { it.pnlUsd ?: 0.0 },
                avgR = if (rMultiples.isEmpty()) null else rMultiples.average(),
            )
        }
    }

    val totalR = remember(filteredClosedTrades) {
        filteredClosedTrades.sumOf { it.rMultiple ?: 0.0 }
    }

    val filterScopeLabelFa = remember(selectedStrategyFilter, strategyCategories) {
        if (selectedStrategyFilter == "ALL") {
            "همهٔ روش‌های ژورنال در ۱۵ نماد (15m + 5m)"
        } else {
            strategyCategories.firstOrNull { it.strategyId == selectedStrategyFilter }?.strategyTitleFa
                ?: selectedStrategyFilter
        }
    }

    // محاسبه TearSheet فقط با تغییر واقعی لیست معاملات (بدون بازمحاسبه هر ۱ ثانیه با تیک ساعت!)
    val tearSheetReport = remember(
        filteredTrades.size,
        filteredTrades.firstOrNull()?.id,
        filteredTrades.firstOrNull()?.closedAt,
        settings.initialCapital,
        settings.accountBalance,
        settings.customStrategies,
        filterScopeLabelFa,
    ) {
        JournalExportEngine.buildInstitutionalTearSheet(
            trades = filteredTrades,
            initialCapital = settings.initialCapital,
            currentBalance = settings.accountBalance,
            customStrategies = settings.customStrategies,
            filterScopeLabelFa = filterScopeLabelFa,
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    // ── کارنامهٔ کمّی (منبع واحد اعداد کارنامه) ──────────────────────────────
    // همهٔ شاخص‌های کارنامه از یک موتور خالص می‌آید تا UI هیچ محاسبهٔ موازی و متناقضی نداشته باشد.
    val performanceReport = remember(
        filteredTrades.size,
        filteredTrades.firstOrNull()?.id,
        filteredTrades.firstOrNull()?.closedAt,
        settings.initialCapital,
        selectedStrategyFilter,
    ) {
        com.aurum.edge.core.TradingPerformanceAnalytics.analyze(
            trades = filteredTrades,
            nowMs = System.currentTimeMillis(),
            startingBalanceUsd = settings.initialCapital,
        )
    }

    var showInteractiveTearSheetModal by rememberSaveable { mutableStateOf(false) }
    val savedJournalFiles by viewModel.savedJournalFiles.collectAsStateWithLifecycle()
    val lastExportStatusFa by viewModel.lastExportStatusFa.collectAsStateWithLifecycle()
    var pendingSaveAsFormat by remember { mutableStateOf(JournalExportFormat.HTML_TEAR_SHEET) }
    var replaceOnImport by rememberSaveable { mutableStateOf(false) }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) {
            viewModel.saveJournalToCustomUri(
                uri = uri,
                report = tearSheetReport,
                format = pendingSaveAsFormat,
            )
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importJournalFromUri(
                uri = uri,
                replaceExisting = replaceOnImport,
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSavedJournalFiles()
    }

    if (showInteractiveTearSheetModal) {
        InteractiveTearSheetModalDialog(
            report = tearSheetReport,
            onDismiss = { showInteractiveTearSheetModal = false },
            onShareFormat = { fmt -> viewModel.exportAndShareJournal(tearSheetReport, fmt) },
            onOpenFormat = { fmt -> viewModel.exportAndOpenJournal(tearSheetReport, fmt) },
            onCopySummary = { viewModel.copyJournalTearSheetSummary(tearSheetReport) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
    ) {
        // ── ۳. نوار تب‌های داخلی صفحه ژورنال ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ترتیب تب‌ها = ترتیب منطقی کار: تقویم ← معاملات باز ← ماشین‌حساب ← خروجی
            // ژورنال: معاملات، تقویم و گزارش‌ها. هیچ تب تصویری در این صفحه نیست.
            listOf(
                0 to "📅 تقویم ژورنال (${filteredClosedTrades.size})",
                1 to "📜 معاملات باز (${filteredOpenTrades.size})",
                2 to "🧮 ماشین‌حساب حجم",
                3 to "📊 خروجی و بکاپ",
            ).forEach { (idx, label) ->
                Pill(
                    text = label,
                    color = if (subPage == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subPage = idx },
                )
            }
        }

        when (subPage) {
            0 -> {
                // تقویم، اولین چیزِ صفحهٔ ژورنال (خواستهٔ صریح: ژورنال در حالت تقویم)
                MonthlyPnlCalendarSection(
                    trades = filteredTrades,
                    closedTrades = filteredClosedTrades,
                    stats = filteredStats,
                    now = now,
                    onDeleteTrade = viewModel::deleteJournalTrade,
                    onClearAll = viewModel::clearAllJournalTrades,
                    onOpenChart = onChartSymbol,
                )

        // ── ۱. کارنامهٔ کلی بازطراحی‌شده (تحلیل کمّی: منحنی سرمایه، ریسک، تفکیک گروه‌ها) ──
        com.aurum.edge.ui.components.JournalPerformanceDashboard(
            report = performanceReport,
            scopeLabelFa = filterScopeLabelFa,
            onOpenExport = { subPage = 3 },
            onOpenArchive = {},
        )

        // ── ۲. جعبه‌ابزار ژورنال: همهٔ خروجی‌ها/بکاپ/تنظیمات در یک کارت تاشو ──────────
        // (در نسخهٔ قبل این ابزارها یک نوار افقی بلند همراه با اعداد تکراری کارنامه بودند که
        // صفحه را شلوغ و «به‌هم‌ریخته» می‌کرد.)
        var toolsExpanded by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AurumColors.Surface)
                .border(1.dp, AurumColors.Cyan.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { toolsExpanded = !toolsExpanded }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🧰 ابزارها و خروجی ژورنال",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = "📤 خروجی کوانت",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable { subPage = 3 },
                    )
                    if (onOpenSettings != null) {
                        Pill(
                            text = "⚙️ تنظیمات",
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.clickable { onOpenSettings() },
                        )
                    }
                    Text(
                        text = if (toolsExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                }
            }

            if (toolsExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = "🧠📄 PDF هوش مصنوعی + رگرسیون",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { viewModel.exportFullAiAndNeuralDataPdf(shareAfterExport = true) },
                    )
                    Pill(
                        text = "📊📄 ثبت همه با اسکرین‌شات + PDF",
                        color = AurumColors.Green,
                        modifier = Modifier.clickable { viewModel.recordAllTradesWithArchiveSnapshotsAndExportPdf() },
                    )
                    Pill(
                        text = "🔍 گزارش تعاملی",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable { showInteractiveTearSheetModal = true },
                    )
                    Pill(
                        text = "💾 ذخیره در Downloads",
                        color = AurumColors.Green,
                        modifier = Modifier.clickable {
                            viewModel.saveJournalDirectToDownloads(tearSheetReport, JournalExportFormat.HTML_TEAR_SHEET)
                            viewModel.saveJournalDirectToDownloads(tearSheetReport, JournalExportFormat.JSON_QUANT)
                        },
                    )
                    Pill(
                        text = "📂 Save As",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            pendingSaveAsFormat = JournalExportFormat.HTML_TEAR_SHEET
                            saveAsLauncher.launch(
                                JournalExportEngine.suggestedFileName(
                                    tearSheetReport,
                                    JournalExportFormat.HTML_TEAR_SHEET,
                                )
                            )
                        },
                    )
                    Pill(
                        text = "📥 بازگردانی بکاپ",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable {
                            replaceOnImport = false
                            importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                    )
                    Pill(
                        text = "🌐 HTML5",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            viewModel.exportAndShareJournal(tearSheetReport, JournalExportFormat.HTML_TEAR_SHEET)
                        },
                    )
                    Pill(
                        text = "📄 PDF",
                        color = AurumColors.Green,
                        modifier = Modifier.clickable {
                            viewModel.exportAndShareJournal(tearSheetReport, JournalExportFormat.PDF_REPORT)
                        },
                    )
                    Pill(
                        text = "📊 CSV",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable {
                            viewModel.exportAndShareJournal(tearSheetReport, JournalExportFormat.CSV_EXCEL)
                        },
                    )
                    Pill(
                        text = "🧬 JSON کوانت",
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.clickable {
                            viewModel.exportAndShareJournal(tearSheetReport, JournalExportFormat.JSON_QUANT)
                        },
                    )
                }
            }

            if (!lastExportStatusFa.isNullOrBlank()) {
                Text(
                    text = lastExportStatusFa.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Green,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ── ۲.۵. زمان‌بندی سشن و خروج هوشمند پیش از بسته شدن بازار ──────────────
        com.aurum.edge.ui.components.SessionExitPanel(
            estimates = sessionExitEstimates,
            lastLearningNote = sessionExitNote,
        )

        // ── ۳. فیلتر و دسته‌بندی استراتژی‌ها ───────────────────────────────
        StrategyJournalCategorizationCard(
            categories = strategyCategories,
            selectedStrategyFilter = selectedStrategyFilter,
            onSelectStrategyFilter = { selectedStrategyFilter = it },
        )

        // ── ۳.۵ مدیریت حافظه گوشی (شاردینگ روزانه + بک‌آپ خودکار روزانه + خزانه ۱ ماهه کندل + دسته‌بندی ۳ کندل قبل از ورود) ──
        PhoneVaultAnd3CandleSummaryCard(
            viewModel = viewModel,
            trades = filteredTrades,
        )

            }

            3 -> {
                InstitutionalQuantTearSheetSection(
                    report = tearSheetReport,
                    savedFiles = savedJournalFiles,
                    replaceOnImport = replaceOnImport,
                    onToggleReplaceOnImport = { replaceOnImport = it },
                    onOpenInteractiveModal = { showInteractiveTearSheetModal = true },
                    onSaveDirectToDownloads = { fmt -> viewModel.saveJournalDirectToDownloads(tearSheetReport, fmt) },
                    onSaveAsCustomFolder = { fmt ->
                        pendingSaveAsFormat = fmt
                        saveAsLauncher.launch(JournalExportEngine.suggestedFileName(tearSheetReport, fmt))
                    },
                    onPickBackupFileToImport = {
                        importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onImportFromClipboard = { viewModel.importJournalFromClipboard(replaceExisting = replaceOnImport) },
                    onRestoreSavedFile = { saved ->
                        viewModel.importJournalFromSavedFile(saved, replaceExisting = replaceOnImport)
                    },
                    onOpenSavedFile = { saved -> viewModel.openSavedJournalFile(saved) },
                    onShareSavedFile = { saved -> viewModel.shareSavedJournalFile(saved) },
                    onDeleteSavedFile = { saved -> viewModel.deleteSavedJournalFile(saved) },
                    onShareFormat = { fmt -> viewModel.exportAndShareJournal(tearSheetReport, fmt) },
                    onOpenFormat = { fmt -> viewModel.exportAndOpenJournal(tearSheetReport, fmt) },
                    onCopySummary = { viewModel.copyJournalTearSheetSummary(tearSheetReport) },
                    onOpenChart = onChartSymbol,
                )
            }

            1 -> {
                AutoMonitoredOpenTradesCard(
                    openTrades = filteredOpenTrades,
                    liveTicks = liveTicks,
                    lastMarks = lastMarks,
                    onRefreshAll = {
                        filteredOpenTrades.map { it.symbol }.distinct().forEach { viewModel.refreshSymbolLivePrice(it) }
                    },
                    onCloseAtPrice = viewModel::closeTradeManually,
                    onDelete = viewModel::deleteJournalTrade,
                    onOpenChart = onChartSymbol,
                )
            }

            else -> {
                RiskCalculatorAndAutoTradeCard(
                    activeSymbol = activeSymbol,
                    liveTick = liveTicks[activeSymbol],
                    accountBalance = settings.accountBalance,
                    riskPercent = settings.riskPercent,
                    fixedRiskUsdOverride = if (settings.useFixedDollarRisk) settings.fixedRiskUsd else null,
                    riskLimits = riskLimits,
                    avalAiEnabled = settings.avalAiEnabled,
                    aiChecking = aiChecking,
                    lastAiVerdict = lastAiVerdict,
                    onSelectSymbol = viewModel::selectRiskSymbol,
                    onRefreshLivePrice = { onResult -> viewModel.refreshSymbolLivePrice(activeSymbol, onResult) },
                    onAskAiOnly = { calc -> viewModel.checkTradeTimingWithAi(calc) },
                    onOpenAutoTrade = { calc, note ->
                        viewModel.openAutoTrackedTrade(
                            calc = calc,
                            note = note,
                            strategyId = settings.selectedBitcoinStrategyId,
                            strategyTitleFa = settings.activeBitcoinStrategyTitleFa(),
                        )
                    },
                    onOpenChart = { onChartSymbol(activeSymbol) },
                )
            }
        }
    }
}

@Composable
private fun StrategyJournalCategorizationCard(
    categories: List<com.aurum.edge.core.StrategyJournalCategoryStats>,
    selectedStrategyFilter: String,
    onSelectStrategyFilter: (String) -> Unit,
) {
    SectionCard(
        title = "📂 دسته‌بندی ژورنال بر اساس استراتژی‌ها (هم‌زمان فعال)",
        subtitle = "عملکرد و معاملات هر استراتژی به‌طور مجزا ثبت و تفکیک شده است؛ روی هر استراتژی بزنید تا ژورنال فیلتر شود",
        trailing = {
            Pill(
                text = if (selectedStrategyFilter == "ALL") "نمایش همه ✓" else "نمایش همه استراتژی‌ها",
                color = if (selectedStrategyFilter == "ALL") AurumColors.Gold else AurumColors.Cyan,
                modifier = Modifier.clickable { onSelectStrategyFilter("ALL") },
            )
        },
    ) {
        // نوار فیلتر سریع استراتژی‌ها
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(
                text = "همه استراتژی‌ها (${categories.sumOf { it.totalCount }})",
                color = if (selectedStrategyFilter == "ALL") AurumColors.Gold else AurumColors.TextMuted,
                modifier = Modifier.clickable { onSelectStrategyFilter("ALL") },
            )
            categories.forEach { cat ->
                val shortName = cat.strategyTitleFa.substringBefore('(').trim()
                Pill(
                    text = "$shortName (${cat.totalCount})",
                    color = if (selectedStrategyFilter == cat.strategyId) AurumColors.Cyan else AurumColors.TextMuted,
                    modifier = Modifier.clickable { onSelectStrategyFilter(cat.strategyId) },
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categories.forEach { cat ->
                val isSelected = selectedStrategyFilter == cat.strategyId
                val pnlColor = when {
                    cat.netPnlUsd > 0.0 -> AurumColors.Green
                    cat.netPnlUsd < 0.0 -> AurumColors.Red
                    else -> AurumColors.Gold
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) AurumColors.SurfaceAlt else AurumColors.Surface,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            if (isSelected) AurumColors.Cyan else AurumColors.Line.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            onSelectStrategyFilter(if (isSelected) "ALL" else cat.strategyId)
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cat.strategyTitleFa,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) AurumColors.Cyan else AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "باز: ${cat.openCount} · بسته: ${cat.closedCount} (برد: ${cat.winsCount} / باخت: ${cat.lossesCount} · وین‌ریت: ${cat.winRate.toInt()}%) · [15m: ${cat.count15m} | 5m: ${cat.count5m}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s$%.2f",
                                    if (cat.netPnlUsd >= 0.0) "+" else "-",
                                    abs(cat.netPnlUsd),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = pnlColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s%.2fR",
                                    if (cat.totalR >= 0.0) "+" else "",
                                    cat.totalR,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = pnlColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapitalAndRiskLimitsCard(
    settings: com.aurum.edge.core.AppSettings,
    riskLimits: com.aurum.edge.core.RiskLimitStatus,
    /**
     * پارامترها: موجودی، سرمایهٔ اولیه، درصد ریسک، سقف ضرر روزانه، سقف افت سرمایه،
     * سقف پوزیشن هم‌زمان و سقف پوزیشن هر نماد.
     */
    onSaveSettings: (Double, Double, Double, Double, Double, Int, Int) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var maxOpenTradesText by rememberSaveable(settings.maxOpenTrades) {
        mutableStateOf(settings.maxOpenTrades.toString())
    }
    var maxPerSymbolText by rememberSaveable(settings.maxOpenTradesPerSymbol) {
        mutableStateOf(settings.maxOpenTradesPerSymbol.toString())
    }
    var balanceText by rememberSaveable(settings.accountBalance) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.accountBalance))
    }
    var initialCapText by rememberSaveable(settings.initialCapital) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.initialCapital))
    }
    var riskPctText by rememberSaveable(settings.riskPercent) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.riskPercent).trimEnd('0').trimEnd('.'))
    }
    var dailyLimitText by rememberSaveable(settings.dailyLossLimitPercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", settings.dailyLossLimitPercent).trimEnd('0').trimEnd('.'))
    }
    var maxDdText by rememberSaveable(settings.maxDrawdownLimitPercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", settings.maxDrawdownLimitPercent).trimEnd('0').trimEnd('.'))
    }

    SectionCard(
        title = "🛡️ مدیریت ریسک و سرمایه",
        subtitle = "تنظیم سرمایه، درصد ریسک هر معامله و سقف ضرر روزانه و کلی",
        trailing = {
            Pill(
                text = if (editing) "بستن ویرایش" else "تنظیم سرمایه و ریسک",
                color = AurumColors.Gold,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = !editing },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "موجودی فعلی حساب",
                value = String.format(Locale.US, "$%.2f", settings.accountBalance),
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "ریسک هر معامله",
                value = "${String.format(Locale.US, "%.2f", settings.riskPercent).trimEnd('0').trimEnd('.')}% ($${String.format(Locale.US, "%.2f", settings.accountBalance * settings.riskPercent / 100.0)})",
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val todayColor = when {
                riskLimits.todayRealizedPnlUsd > 0.0 -> AurumColors.Green
                riskLimits.todayRealizedPnlUsd < 0.0 -> AurumColors.Red
                else -> AurumColors.TextPrimary
            }
            StatTile(
                label = "سود/زیان امروز",
                value = String.format(
                    Locale.US,
                    "%s$%.2f",
                    if (riskLimits.todayRealizedPnlUsd >= 0.0) "+" else "",
                    riskLimits.todayRealizedPnlUsd,
                ),
                valueColor = todayColor,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "ریسک باز فعلی",
                value = String.format(Locale.US, "$%.2f", riskLimits.openTradesRiskUsd),
                valueColor = if (riskLimits.openTradesRiskUsd > 0.0) AurumColors.Orange else AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        // Daily & Total Drawdown progress bars
        val dailyUsedRatio = if (riskLimits.dailyLossLimitUsd > 0.0) {
            ((riskLimits.dailyLossLimitUsd - riskLimits.remainingDailyRiskUsd) / riskLimits.dailyLossLimitUsd)
                .toFloat().coerceIn(0f, 1f)
        } else 0f

        val overallUsedRatio = if (riskLimits.maxDrawdownLimitUsd > 0.0) {
            ((riskLimits.maxDrawdownLimitUsd - riskLimits.remainingDrawdownUsd) / riskLimits.maxDrawdownLimitUsd)
                .toFloat().coerceIn(0f, 1f)
        } else 0f

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "سقف ضرر روزانه (${String.format(Locale.US, "%.1f", settings.dailyLossLimitPercent)}% = $${String.format(Locale.US, "%.2f", riskLimits.dailyLossLimitUsd)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    "باقی‌مانده: $${String.format(Locale.US, "%.2f", riskLimits.remainingDailyRiskUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (riskLimits.isDailyLimitBreached) AurumColors.Red else AurumColors.Green,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { dailyUsedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (dailyUsedRatio >= 0.85f) AurumColors.Red else AurumColors.Gold,
                trackColor = AurumColors.Surface,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "سقف ضرر کلی (${String.format(Locale.US, "%.1f", settings.maxDrawdownLimitPercent)}% = $${String.format(Locale.US, "%.2f", riskLimits.maxDrawdownLimitUsd)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    "باقی‌مانده: $${String.format(Locale.US, "%.2f", riskLimits.remainingDrawdownUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (riskLimits.isOverallDrawdownBreached) AurumColors.Red else AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { overallUsedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (overallUsedRatio >= 0.85f) AurumColors.Red else AurumColors.Cyan,
                trackColor = AurumColors.Surface,
            )
        }

        // Quick Risk Percent Selector Chips
        Text(
            "انتخاب سریع درصد ریسک در هر معامله:",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RISK_PRESETS.forEach { pct ->
                val selected = abs(settings.riskPercent - pct) < 0.01
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                        .border(
                            1.dp,
                            if (selected) AurumColors.Gold else AurumColors.Line,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            riskPctText = pct.toString()
                            onSaveSettings(
                                settings.accountBalance,
                                settings.initialCapital,
                                pct,
                                settings.dailyLossLimitPercent,
                                settings.maxDrawdownLimitPercent,
                                settings.maxOpenTrades,
                                settings.maxOpenTradesPerSymbol,
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${pct}%",
                        color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (editing) AurumColors.Cyan.copy(alpha = 0.2f) else AurumColors.SurfaceAlt)
                    .border(1.dp, AurumColors.Cyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { editing = !editing }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (editing) "بستن پنل تنظیمات ▲" else "ویرایش سرمایه و سقف ضرر ▼",
                    color = AurumColors.Cyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = balanceText,
                        onValueChange = { balanceText = it },
                        label = { Text("موجودی فعلی ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = initialCapText,
                        onValueChange = { initialCapText = it },
                        label = { Text("سرمایه اولیه ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = riskPctText,
                        onValueChange = { riskPctText = it },
                        label = { Text("ریسک هر معامله (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dailyLimitText,
                        onValueChange = { dailyLimitText = it },
                        label = { Text("سقف ضرر روزانه (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxDdText,
                        onValueChange = { maxDdText = it },
                        label = { Text("سقف ضرر کل (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxOpenTradesText,
                        onValueChange = { maxOpenTradesText = it.filter(Char::isDigit).take(2) },
                        label = { Text("سقف پوزیشن باز هم‌زمان") },
                        supportingText = { Text("۰ = بدون سقف (پیش‌فرض) · ۱ تا ۱۲ = سقف دستی") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxPerSymbolText,
                        onValueChange = { maxPerSymbolText = it.filter(Char::isDigit).take(1) },
                        label = { Text("سقف پوزیشن هر نماد") },
                        supportingText = { Text("۰ = بدون سقف (پیش‌فرض)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        val bal = balanceText.trim().toDoubleOrNull() ?: settings.accountBalance
                        val initCap = initialCapText.trim().toDoubleOrNull() ?: bal
                        val risk = riskPctText.trim().toDoubleOrNull() ?: settings.riskPercent
                        val daily = dailyLimitText.trim().toDoubleOrNull() ?: settings.dailyLossLimitPercent
                        val maxDd = maxDdText.trim().toDoubleOrNull() ?: settings.maxDrawdownLimitPercent
                        val maxOpen = maxOpenTradesText.trim().toIntOrNull() ?: settings.maxOpenTrades
                        val maxPerSymbol = maxPerSymbolText.trim().toIntOrNull() ?: settings.maxOpenTradesPerSymbol
                        onSaveSettings(bal, initCap, risk, daily, maxDd, maxOpen, maxPerSymbol)
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = Color(0xFF14100A),
                    ),
                ) {
                    Text("ذخیره تنظیمات سرمایه و ریسک", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun AvalAiPreTradeGateCard(
    settings: com.aurum.edge.core.AppSettings,
    aiChecking: Boolean,
    lastVerdict: AiTradeVerdict?,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveConfig: (String, String, String) -> Unit,
    onTestYesNo: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val effectiveSavedKey = settings.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var keyInput by rememberSaveable(effectiveSavedKey) { mutableStateOf(effectiveSavedKey) }
    var modelInput by rememberSaveable(settings.newsAiModel) {
        mutableStateOf(settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL })
    }
    var urlInput by rememberSaveable(settings.newsAiBaseUrl) {
        mutableStateOf(settings.newsAiBaseUrl.ifBlank { AvalAiAdvisor.DEFAULT_BASE_URL })
    }
    val (keyValid, keyStatusMsg) = remember(keyInput) {
        AvalAiAdvisor.validateApiKeyFormat(keyInput)
    }

    SectionCard(
        title = "🤖 هوش مصنوعی AvalAI (یک سوال با پاسخ بله / نه)",
        subtitle = "سوال از هوش مصنوعی: «الان با این شرایط مدل استراتژی معامله کنم یا نه؟» ← پاسخ: «بله» یا «نه»",
        trailing = {
            Pill(
                text = if (aiChecking) "در حال پرسش..." else if (settings.avalAiEnabled) "فعال (بله/نه) ✓" else "غیرفعال",
                color = if (settings.avalAiEnabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        // Activation Toggle Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = if (settings.avalAiEnabled) {
                        "فعال: قبل از باز شدن هر معامله، شرایط مدل استراتژی ارسال و پاسخ «بله» یا «نه» گرفته می‌شود"
                    } else {
                        "غیرفعال: معاملات استراتژی بدون پرسش از هوش مصنوعی ثبت می‌شوند"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.avalAiEnabled) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "وضعیت کلید: $keyStatusMsg · مدل: ${settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (keyValid) AurumColors.Cyan else AurumColors.Red,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = settings.avalAiEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }

        // Last AI Verdict Display (Yes / No)
        if (lastVerdict != null) {
            val vColor = if (lastVerdict.approved) AurumColors.Green else AurumColors.Red
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(vColor.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                    .border(1.dp, vColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "پاسخ هوش مصنوعی (${lastVerdict.symbol}): ${lastVerdict.yesNoLabelFa}",
                        style = MaterialTheme.typography.titleSmall,
                        color = vColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = "${lastVerdict.confidence}% · ${lastVerdict.modelUsed}",
                        color = vColor,
                    )
                }
                Text(
                    text = lastVerdict.reasonFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                )
            }
        }

        if (onTestYesNo != null) {
            Button(
                onClick = onTestYesNo,
                enabled = !aiChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.Gold,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    text = if (aiChecking) "در حال استعلام از AvalAI..." else "⚡ تست زنده کلید: الان با شرایط استراتژی معامله کنم یا نه؟",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Buttons to open AvalAI API Keys page or toggle settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    runCatching { uriHandler.openUri(AvalAiAdvisor.API_KEYS_PORTAL_URL) }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("پنل کلید chat.avalai.ir ↗", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            OutlinedButton(
                onClick = { showConfig = !showConfig },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (showConfig) "بستن تنظیمات کلید ▲" else "مشاهده / ویرایش کلید API ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )
            }
        }

        if (showConfig) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("کلید API سایت AvalAI (از chat.avalai.ir/platform/api-keys)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "انتخاب سریع مدل هوش مصنوعی AvalAI:",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AvalAiAdvisor.POPULAR_MODELS.forEach { presetModel ->
                        val selected = modelInput.trim() == presetModel
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.Surface)
                                .border(1.dp, if (selected) AurumColors.Gold else AurumColors.Line, RoundedCornerShape(8.dp))
                                .clickable { modelInput = presetModel }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = presetModel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("نام مدل (Model)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("آدرس پایه (Base URL)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Button(
                    onClick = {
                        onSaveConfig(keyInput, modelInput, urlInput)
                        showConfig = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = Color(0xFF14100A),
                    ),
                ) {
                    Text("ذخیره کلید API و فعال‌سازی هوش مصنوعی", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RiskCalculatorAndAutoTradeCard(
    activeSymbol: String,
    liveTick: PriceTick?,
    accountBalance: Double,
    riskPercent: Double,
    fixedRiskUsdOverride: Double?,
    riskLimits: com.aurum.edge.core.RiskLimitStatus,
    avalAiEnabled: Boolean,
    aiChecking: Boolean,
    lastAiVerdict: AiTradeVerdict?,
    onSelectSymbol: (String) -> Unit,
    onRefreshLivePrice: (((PriceTick?) -> Unit)?) -> Unit,
    onAskAiOnly: (com.aurum.edge.core.RiskCalculationResult) -> Unit,
    onOpenAutoTrade: (com.aurum.edge.core.RiskCalculationResult, String) -> Unit,
    onOpenChart: () -> Unit,
) {
    var selectedDirection by rememberSaveable { mutableStateOf(SignalAction.BUY.name) }
    val direction = if (selectedDirection == SignalAction.SELL.name) SignalAction.SELL else SignalAction.BUY

    var entryText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var slText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var tpText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }

    // Auto-fill entry/SL/TP when a symbol's first live tick arrives and fields are still blank
    LaunchedEffect(activeSymbol, liveTick?.price) {
        val p = liveTick?.price
        if (p != null && entryText.isBlank() && slText.isBlank() && tpText.isBlank()) {
            entryText = formatPrice(p)
            RiskAndCalendarEngine.suggestSlTp(activeSymbol, p, direction, 2.0)?.let { (sl, tp) ->
                slText = formatPrice(sl)
                tpText = formatPrice(tp)
            }
        }
    }

    val entryVal = entryText.replace(',', '.').trim().toDoubleOrNull()
    val slVal = slText.replace(',', '.').trim().toDoubleOrNull()
    val tpVal = tpText.replace(',', '.').trim().toDoubleOrNull()

    val calcResult = remember(activeSymbol, accountBalance, riskPercent, fixedRiskUsdOverride, entryVal, slVal, tpVal, direction) {
        if (entryVal != null && slVal != null && tpVal != null) {
            RiskAndCalendarEngine.calculatePosition(
                symbolInput = activeSymbol,
                accountBalance = accountBalance,
                riskPercent = riskPercent,
                entry = entryVal,
                stopLoss = slVal,
                takeProfit = tpVal,
                preferredAction = direction,
                fixedRiskUsdOverride = fixedRiskUsdOverride,
                triggerInterval = com.aurum.edge.domain.Interval.M15,
            )
        } else null
    }

    // داوری پلن دستی: آیا حد ضرر متناسب با نوسان ۱۵ دقیقه است و حد سود در دسترس؟
    val planAudit = remember(activeSymbol, entryVal, slVal, tpVal, direction) {
        if (entryVal != null && slVal != null && tpVal != null) {
            RiskAndCalendarEngine.auditTradePlan(
                symbolInput = activeSymbol,
                action = direction,
                entry = entryVal,
                stopLoss = slVal,
                takeProfit = tpVal,
                triggerInterval = com.aurum.edge.domain.Interval.M15,
            )
        } else null
    }

    SectionCard(
        title = "🧮 ماشین‌حساب حجم و ثبت خودکار معامله",
        subtitle = "ورود، SL و TP را وارد کنید؛ حجم خودکار محاسبه و تا برخورد به TP یا SL رصد می‌شود",
        trailing = {
            Pill(
                text = "نمایش چارت ↗",
                color = AurumColors.Cyan,
            )
        },
    ) {
        // Symbol Selector Horizontal Chips
        Text(
            "۱. انتخاب نماد معاملاتی:",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WatchCatalog.symbols.forEach { sym ->
                val isSelected = sym.id == activeSymbol
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                        .border(
                            1.dp,
                            if (isSelected) AurumColors.Gold else AurumColors.Line,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelectSymbol(sym.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sym.id,
                            color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = sym.label,
                            color = AurumColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Live market price banner + 1-tap fill button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "قیمت لحظه‌ای بازار ($activeSymbol): " +
                        (liveTick?.let { formatPrice(it.price) } ?: "در حال دریافت..."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (liveTick != null) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = liveTick?.let {
                        "منبع: ${it.provider ?: "بازار"} · ${formatDateTime(it.at)}"
                    } ?: "برای پر کردن خودکار قیمت ورود و SL/TP دکمهٔ روبرو را بزنید",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
            OutlinedButton(
                onClick = {
                    onRefreshLivePrice { fetched ->
                        val p = (fetched ?: liveTick)?.price ?: return@onRefreshLivePrice
                        entryText = formatPrice(p)
                        RiskAndCalendarEngine.suggestSlTp(activeSymbol, p, direction, 2.0)?.let { (sl, tp) ->
                            slText = formatPrice(sl)
                            tpText = formatPrice(tp)
                        }
                    }
                },
            ) {
                Text("قیمت لحظه‌ای ⚡", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
        }

        // Direction toggle (BUY / SELL) + Quick R:R ratio presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isBuy = direction == SignalAction.BUY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isBuy) AurumColors.Green.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                    .border(1.dp, if (isBuy) AurumColors.Green else AurumColors.Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selectedDirection = SignalAction.BUY.name
                        val e = entryVal ?: liveTick?.price
                        if (e != null) {
                            RiskAndCalendarEngine.suggestSlTp(activeSymbol, e, SignalAction.BUY, 2.0)?.let { (sl, tp) ->
                                slText = formatPrice(sl)
                                tpText = formatPrice(tp)
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "خرید (Long / BUY) ↗",
                    color = if (isBuy) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (!isBuy) AurumColors.Red.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                    .border(1.dp, if (!isBuy) AurumColors.Red else AurumColors.Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selectedDirection = SignalAction.SELL.name
                        val e = entryVal ?: liveTick?.price
                        if (e != null) {
                            RiskAndCalendarEngine.suggestSlTp(activeSymbol, e, SignalAction.SELL, 2.0)?.let { (sl, tp) ->
                                slText = formatPrice(sl)
                                tpText = formatPrice(tp)
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "فروش (Short / SELL) ↘",
                    color = if (!isBuy) AurumColors.Red else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Entry, SL, TP Inputs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = entryText,
                onValueChange = { entryText = it },
                label = { Text("قیمت ورود (Entry)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = slText,
                onValueChange = { slText = it },
                label = { Text("حد ضرر (SL)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tpText,
                onValueChange = { tpText = it },
                label = { Text("حد سود (TP)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }

        // Quick R:R buttons to auto-adjust TP from Entry & SL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "تنظیم خودکار TP بر اساس R:R:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            RR_PRESETS.forEach { rr ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(6.dp))
                        .clickable {
                            if (entryVal != null && slVal != null && abs(entryVal - slVal) > 1e-8) {
                                val dist = abs(entryVal - slVal) * rr
                                val newTp = if (slVal < entryVal) entryVal + dist else entryVal - dist
                                if (newTp > 0.0) tpText = formatPrice(newTp)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "1:$rr",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "چارت $activeSymbol ↗",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                modifier = Modifier.clickable { onOpenChart() },
            )
        }

        OutlinedTextField(
            value = noteText,
            onValueChange = { noteText = it },
            label = { Text("یادداشت / ستاپ معاملاتی (اختیاری)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        // Calculated Position Size, Lot, Risk $, Reward $, R:R Box
        if (calcResult != null) {
            val withinLimits = riskLimits.canOpenTrade(calcResult.riskBudgetUsd)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (withinLimits) AurumColors.Gold.copy(alpha = 0.45f) else AurumColors.Red,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "نتیجهٔ محاسبهٔ خودکار مدیریت ریسک",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = if (calcResult.action == SignalAction.BUY) "خرید (BUY)" else "فروش (SELL)",
                        color = if (calcResult.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "حجم پوزیشن / لات",
                        value = calcResult.lotDescription,
                        valueColor = AurumColors.Gold,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "نسبت ریسک به ریوارد",
                        value = String.format(Locale.US, "1 : %.2f", calcResult.riskReward),
                        valueColor = if (calcResult.riskReward >= 1.5) AurumColors.Green else AurumColors.Orange,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "مقدار دلار در ریسک (SL)",
                        value = String.format(Locale.US, "-$%.2f (%.1f%%)", calcResult.riskBudgetUsd, calcResult.riskPercent),
                        valueColor = AurumColors.Red,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "سود خالص در هدف (TP)",
                        value = String.format(Locale.US, "+$%.2f", calcResult.netProfitAtTpUsd),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "مارجین مورد نیاز: $${String.format(Locale.US, "%.2f", calcResult.marginRequiredUsd)} (اهرم ${calcResult.leverage}x) · کارمزد و اسپرد: $${String.format(Locale.US, "%.3f", calcResult.totalRoundTripFeeUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )

                Text(
                    text = "🔐 لیکوییدیشن: ${formatPriceFor(calcResult.symbol, calcResult.liquidationPrice)}" +
                        " (${String.format(Locale.US, "%.1f", calcResult.liquidationBufferR)}R دورتر از حد ضرر)" +
                        " · ۱R = ${String.format(Locale.US, "%.2f%%", calcResult.stopPctOfPrice * 100.0)} قیمت" +
                        " · ${calcResult.leverageReasonFa}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.SemiBold,
                )

                if (planAudit != null) {
                    Text(
                        text = "${planAudit.verdict.badgeFa} اعتبارسنجی پلن: ${planAudit.lineFa()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (planAudit.verdict) {
                            com.aurum.edge.core.TradePlanVerdict.VALID -> AurumColors.Green
                            com.aurum.edge.core.TradePlanVerdict.ADJUSTED -> AurumColors.Orange
                            com.aurum.edge.core.TradePlanVerdict.REJECTED -> AurumColors.Red
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = "🏦 ${calcResult.buildVenueRulesSummaryFa()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )

                if (!withinLimits) {
                    Text(
                        text = "⚠️ هشدار مدیریت ریسک: ریسک این معامله ($${String.format(Locale.US, "%.2f", calcResult.riskBudgetUsd)}) بیشتر از سقف ضرر مجاز باقی‌ماندهٔ روزانه ($${String.format(Locale.US, "%.2f", riskLimits.remainingDailyRiskUsd)}) یا کلی است.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onAskAiOnly(calcResult) },
                        enabled = !aiChecking,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (aiChecking) "در حال پرسش از AI..." else "🤖 نظر هوش مصنوعی",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        onClick = {
                            onOpenAutoTrade(calcResult, noteText)
                            noteText = ""
                        },
                        enabled = !aiChecking,
                        modifier = Modifier.weight(1.6f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (calcResult.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            contentColor = Color(0xFF07090D),
                        ),
                    ) {
                        Text(
                            text = if (avalAiEnabled) {
                                "⚡ بررسی AI و ثبت معامله"
                            } else {
                                "⚡ ثبت و رصد خودکار TP/SL"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        } else {
            Text(
                text = "جهت محاسبهٔ خودکار حجم، لات و نسبت R:R، قیمت ورود، حد ضرر (SL) و حد سود (TP) معتبر وارد کنید (در خرید: SL < Entry < TP و در فروش: TP < Entry < SL).",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Orange,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun OfflineGapBackfillCard(
    activeSymbol: String,
    summary: CandleBackfillSummary?,
    isBackfilling: Boolean,
    onSyncGap: (Int?) -> Unit,
) {
    SectionCard(
        title = "🔄 بازیابی دیتای ساعات قطعی (Backfill)",
        subtitle = "دریافت یکجای کندل‌های ساعاتی که آفلاین بودید و بررسی خودکار برخورد High/Low به TP و SL",
        trailing = {
            Pill(
                text = if (isBackfilling) "در حال دریافت..." else if (summary != null) "همگام تا این لحظه ✓" else "آماده همگام‌سازی",
                color = if (isBackfilling) AurumColors.Gold else if (summary != null) AurumColors.Green else AurumColors.Cyan,
            )
        },
    ) {
        if (summary != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "نماد ${summary.symbol} · ${summary.candlesCount} کندل بازیابی شد (${summary.provider})",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "بازهٔ پوشش: از ${formatDateTime(summary.fromMillis)} تا ${formatDateTime(summary.toMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "بالاترین قیمت بازه (High)",
                        value = formatPrice(summary.periodHigh),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "پایین‌ترین قیمت بازه (Low)",
                        value = formatPrice(summary.periodLow),
                        valueColor = AurumColors.Red,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "آخرین کلوز",
                        value = formatPrice(summary.lastClose),
                        valueColor = AurumColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Text(
                text = "به محض اتصال، کندل‌های ۱ دقیقه/۵ دقیقهٔ ساعات قطعی برای نماد انتخابی و معاملات باز دریافت و به ترتیب زمانی روی TP و SL بررسی می‌شوند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onSyncGap(6) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("بازیابی ۶ ساعت اخیر", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            OutlinedButton(
                onClick = { onSyncGap(24) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("بازیابی ۲۴ ساعت اخیر", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            }
            OutlinedButton(
                onClick = { onSyncGap(null) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("از آخرین اتصال", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
        }
    }
}

@Composable
private fun AutoMonitoredOpenTradesCard(
    openTrades: List<PaperTrade>,
    liveTicks: Map<String, PriceTick>,
    lastMarks: Map<String, DisplayMark>,
    onRefreshAll: () -> Unit,
    onCloseAtPrice: (String, Double, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenChart: (String) -> Unit,
) {
    SectionCard(
        title = "📡 معاملات باز در حال رصد خودکار (${openTrades.size})",
        subtitle = "قیمت لحظه‌ای بازار پیوسته چک می‌شود؛ با رسیدن به TP یا SL معامله خودکار بسته و در تقویم ثبت می‌شود",
        trailing = {
            Pill(
                text = "بروزرسانی قیمت ↻",
                color = AurumColors.Cyan,
            )
        },
    ) {
        if (openTrades.isEmpty()) {
            Text(
                text = "در حال حاضر معاملهٔ بازی وجود ندارد. از ماشین‌حساب بالا معاملهٔ جدید ثبت کنید تا به‌طور خودکار رصد شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRefreshAll() }
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "دریافت فوری آخرین قیمت همهٔ پوزیشن‌ها ↻",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                openTrades.forEach { trade ->
                    val tick = liveTicks[V1Universe.normalize(trade.symbol)]
                    val mark = OpenPositionDisplay.forTrade(trade, lastMarks)
                    val currentPrice = tick?.price ?: mark?.price

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TradingViewPositionBox(
                            trade = trade,
                            currentPrice = currentPrice,
                            onOpenChart = { onOpenChart(trade.symbol) },
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AurumColors.Surface, RoundedCornerShape(8.dp))
                                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "📐 کالبدشکافی محاسبه R:R و عدد مناسب",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.Gold,
                                    fontWeight = FontWeight.Bold,
                                )
                                Pill(
                                    text = "عدد مناسب: 1:${String.format(Locale.US, "%.2f", trade.effectiveOptimalRr)}",
                                    color = if (trade.effectiveOptimalRr >= 3.0) AurumColors.Green else AurumColors.Cyan,
                                )
                            }
                            Text(
                                text = trade.buildRrFormulaSummaryFa(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = trade.buildOptimalRrReasonFa(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                            )
                            Text(
                                text = "🧭 خروج هوشمند فقط بالای +۲R: تا سود معامله به ۲ برابر ریسک نرسد هیچ خروج هوشمندی اجرا نمی‌شود. بالای +۲R: شکست کیجون/TK، شدوی بازگشتی C-Clamp، چرخه هوسودا (9/17/26) با تنکان فلت، یا قفل چیکو و افت ER<20%",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                            Text(
                                text = "🏦 ${trade.buildVenueRulesAndFeesSummaryFa()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }

                        if (trade.note.isNotBlank()) {
                            Text(
                                text = "یادداشت: ${trade.note}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                        }

                        // Action buttons: Close at live price, Close at TP, Close at SL, Delete
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val exitP = currentPrice ?: trade.entry
                                    onCloseAtPrice(trade.id, exitP, "بستن با قیمت لحظه‌ای بازار")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("بستن با قیمت لحظه‌ای", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                            }
                            OutlinedButton(
                                onClick = {
                                    onCloseAtPrice(trade.id, trade.takeProfit, "برخورد به حد سود (TP)")
                                },
                            ) {
                                Text("TP ✓", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                            }
                            OutlinedButton(
                                onClick = {
                                    onCloseAtPrice(trade.id, trade.stopLoss, "برخورد به حد ضرر (SL)")
                                },
                            ) {
                                Text("SL ✕", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                            }
                            OutlinedButton(
                                onClick = { onDelete(trade.id) },
                            ) {
                                Text("حذف", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyPnlCalendarSection(
    trades: List<PaperTrade>,
    closedTrades: List<PaperTrade>,
    stats: com.aurum.edge.data.JournalStats,
    now: Long,
    onDeleteTrade: (String) -> Unit,
    onClearAll: () -> Unit,
    onOpenChart: (String) -> Unit,
) {
    val todayZoned = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()) }
    val todayYm = remember(todayZoned) { YearMonth.from(todayZoned) }
    var selectedYear by rememberSaveable { mutableIntStateOf(todayYm.year) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(todayYm.monthValue) }
    // ۰ = کل ماه، ۱..۳۱ = معاملات همان روز
    var selectedDayOfMonth by rememberSaveable { mutableIntStateOf(0) }

    // کلیدهای remember باید «نسخهٔ داده» را نشان دهند، نه چیزهایی که هر ۲۰ ثانیه عوض می‌شوند.
    // قبلاً `closedTrades.size` و شناسهٔ اولین معامله به‌عنوان کلید بودند: با هر تیک ساعت
    // recomposition رخ می‌داد و اگر یکی از این‌ها تغییر می‌کرد، کل تقویم ماه دوباره ساخته می‌شد.
    val journalRevision = remember(trades) {
        var hash = trades.size
        trades.forEach { trade ->
            hash = 31 * hash + trade.id.hashCode()
            hash = 31 * hash + (trade.closedAt ?: trade.openedAt).hashCode()
            hash = 31 * hash + (trade.pnlUsd?.hashCode() ?: 0)
            hash = 31 * hash + if (trade.isOpen) 1 else 0
        }
        hash
    }
    val calendar = remember(selectedYear, selectedMonth, journalRevision) {
        RiskAndCalendarEngine.buildMonthlyCalendar(
            year = selectedYear,
            month = selectedMonth,
            trades = trades,
        )
    }
    val tradingDays = remember(calendar) { calendar.days.filter { it.closedTradesCount > 0 } }
    val maxAbsDayPnl = remember(tradingDays) { tradingDays.maxOfOrNull { abs(it.netPnlUsd) } ?: 0.0 }
    val lastTradingMonth = remember(closedTrades) {
        closedTrades.maxOfOrNull { it.closedAt ?: it.openedAt }?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
    }
    val isCurrentMonth = selectedYear == todayYm.year && selectedMonth == todayYm.monthValue
    val monthLabel = calendar.monthTitleFa

    SectionCard(
        title = "📅 تقویم معاملات",
        subtitle = "نمای تقویمی ژورنال — هر خانه یک روز با سود/زیان همان روز؛ روی روز بزن تا معاملاتش باز شود",
        collapsible = false,
        trailing = {
            Pill(
                text = formatUsdExact(calendar.monthNetPnlUsd),
                color = when {
                    calendar.monthNetPnlUsd > 0.0 -> AurumColors.Green
                    calendar.monthNetPnlUsd < 0.0 -> AurumColors.Red
                    else -> AurumColors.Gold
                },
            )
        },
    ) {
        // ── نوار ناوبری ماه ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.SurfaceAlt)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ ماه قبل",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val prev = YearMonth.of(selectedYear, selectedMonth).minusMonths(1)
                        selectedYear = prev.year
                        selectedMonth = prev.monthValue
                        selectedDayOfMonth = 0
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isCurrentMonth) {
                        Text(
                            text = "برگرد به ماه جاری",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    selectedYear = todayYm.year
                                    selectedMonth = todayYm.monthValue
                                    selectedDayOfMonth = 0
                                }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    val lastMonth = lastTradingMonth
                    if (lastMonth != null && (lastMonth.year != selectedYear || lastMonth.monthValue != selectedMonth)) {
                        Text(
                            text = "آخرین ماه معاملاتی",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    selectedYear = lastMonth.year
                                    selectedMonth = lastMonth.monthValue
                                    selectedDayOfMonth = 0
                                }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = "ماه بعد ›",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val next = YearMonth.of(selectedYear, selectedMonth).plusMonths(1)
                        selectedYear = next.year
                        selectedMonth = next.monthValue
                        selectedDayOfMonth = 0
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // ── کارت‌های خلاصهٔ ماه ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "برآیند ماه",
                value = formatUsdExact(calendar.monthNetPnlUsd),
                valueColor = if (calendar.monthNetPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "روزهای سبز / قرمز",
                value = "${calendar.winningDays} / ${calendar.losingDays}",
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "وین‌ریت (${calendar.totalClosedTrades})",
                value = calendar.monthWinRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
        }

        // ── راهنمای خواندن تقویم ────────────────────────────────────────────
        Text(
            text = "سبز = سود · قرمز = ضرر · شدت رنگ هر خانه به بزرگی سود/زیان همان روز بستگی دارد · " +
                "همهٔ خانه‌ها مربعی و هم‌اندازه‌اند · حلقهٔ فیروزه‌ای = امروز",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )

        // ── سرستون روزهای هفته ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WEEKDAY_HEADERS_FA.forEach { dayName ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayName,
                        color = AurumColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── شبکهٔ تقویم (خانه‌های مربعی و کاملاً هم‌اندازه) ───────────────────
        // در نسخه‌های قبل ارتفاع خانه با بزرگی سود/زیان روز تغییر می‌کرد و اندازهٔ عدد داخل خانه
        // هم ثابت نبود؛ نتیجه «به‌هم‌ریختگی» بود. حالا هر خانه با aspectRatio(1f) مربع می‌شود،
        // همهٔ ردیف‌ها ارتفاع یکسان دارند و اندازهٔ همهٔ عددها ثابت است (۹sp برای مبلغ).
        val totalCells = calendar.firstDayOffsetSaturdayStart + calendar.daysInMonth
        val rowCount = (totalCells + 6) / 7
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until rowCount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    for (c in 0 until 7) {
                        val cellIndex = r * 7 + c
                        val dayNumber = cellIndex - calendar.firstDayOffsetSaturdayStart + 1
                        val daySummary = if (dayNumber in 1..calendar.daysInMonth) {
                            calendar.days[dayNumber - 1]
                        } else null
                        if (daySummary == null) {
                            // خانهٔ خالی فقط با aspectRatio(1f) هم‌ارتفاع بقیه نمی‌شود: ارتفاع
                            // ردیف از محتوای واقعی می‌آید. با کف ارتفاع، ردیف اول ماه‌هایی که
                            // روز خالی دارند کوتاه‌تر از ردیف‌های دیگر نمی‌شود.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .heightIn(min = CALENDAR_CELL_MIN_HEIGHT),
                            )
                        } else {
                            val intensity = if (maxAbsDayPnl <= 0.0) {
                                0.0
                            } else {
                                (abs(daySummary.netPnlUsd) / maxAbsDayPnl).coerceIn(0.0, 1.0)
                            }
                            CalendarDayCell(
                                summary = daySummary,
                                isSelected = selectedDayOfMonth == daySummary.dayOfMonth,
                                isToday = isCurrentMonth && todayZoned.dayOfMonth == daySummary.dayOfMonth,
                                intensity = intensity,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .heightIn(min = CALENDAR_CELL_MIN_HEIGHT),
                                onClick = {
                                    selectedDayOfMonth = if (selectedDayOfMonth == daySummary.dayOfMonth) {
                                        0
                                    } else {
                                        daySummary.dayOfMonth
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (calendar.totalClosedTrades == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "در $monthLabel معاملهٔ بسته‌شده‌ای ثبت نشده است.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "معاملات باز در بخش «معاملات باز» بالا دیده می‌شوند و با بسته‌شدن (TP/SL) به همین تقویم اضافه می‌شوند.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
        }

        // ── پنل روز انتخاب‌شده ──────────────────────────────────────────────
        val panelTrades = remember(calendar, selectedDayOfMonth) {
            if (selectedDayOfMonth in 1..calendar.daysInMonth) {
                calendar.days[selectedDayOfMonth - 1].trades.sortedByDescending { it.closedAt ?: it.openedAt }
            } else {
                emptyList()
            }
        }
        val monthTrades = remember(calendar) {
            calendar.days.flatMap { it.trades }.sortedByDescending { it.closedAt ?: it.openedAt }
        }
        val shownTrades = if (selectedDayOfMonth in 1..calendar.daysInMonth) panelTrades else monthTrades
        val shownNet = remember(shownTrades) { shownTrades.sumOf { it.pnlUsd ?: 0.0 } }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedDayOfMonth in 1..calendar.daysInMonth) {
                        "معاملات روز $selectedDayOfMonth"
                    } else {
                        "معاملات $monthLabel"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${shownTrades.size} معامله · برآیند ${formatUsdExact(shownNet)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (shownNet >= 0.0) AurumColors.Green else AurumColors.Red,
                )
            }
            if (selectedDayOfMonth != 0) {
                Pill(
                    text = "نمایش کل ماه",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { selectedDayOfMonth = 0 },
                )
            }
        }

        if (shownTrades.isEmpty()) {
            Text(
                text = if (selectedDayOfMonth == 0) {
                    "در این ماه معاملهٔ بسته‌شده‌ای نیست؛ برای دیدن معاملات یک روز، خانهٔ آن روز را انتخاب کن."
                } else {
                    "در این روز معامله‌ای بسته نشده است؛ برای بستن انتخاب، دوباره روی همان روز بزن."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            val visibleTrades = if (selectedDayOfMonth in 1..calendar.daysInMonth) {
                shownTrades
            } else {
                shownTrades.take(10)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                visibleTrades.forEach { trade ->
                    ClosedJournalTradeRow(
                        trade = trade,
                        onDelete = { onDeleteTrade(trade.id) },
                        onOpenChart = { onOpenChart(trade.symbol) },
                    )
                }
            }
            if (visibleTrades.size < shownTrades.size) {
                Text(
                    text = "برای دیدن بقیه، روی خانهٔ همان روز بزن — " +
                        "${shownTrades.size - visibleTrades.size} معاملهٔ دیگر در این ماه هست.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── آمار کل ژورنال ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.SurfaceAlt)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "📊 آمار کل ژورنال معاملاتی",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "برآیند کل",
                    value = formatUsdExact(stats.netPnl),
                    valueColor = if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "وین‌ریت کل",
                    value = stats.winRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "پرافیت فاکتور",
                    value = stats.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** قالب کوتاه و خوانا برای مبلغ دلاری با علامت (مثلاً `+$12.4` یا `−$1.2k`). */
private fun formatUsdExact(value: Double): String {
    val sign = if (value >= 0.0) "+" else "−"
    return ltrIsolated(sign + "$" + String.format(Locale.US, "%.2f", abs(value)))
}

/**
 * مبالغ و اعداد در متن فارسی نباید جابه‌جا شوند. با ایزوله‌کردن جهت (U+202A…U+202C) علامت و عدد
 * همیشه چپ‌به‌راست و به همان ترتیب دیده می‌شوند — ریشهٔ بخشی از «به‌هم‌ریختگی» گزارش‌شده.
 */
private fun ltrIsolated(text: String): String = "\u202A$text\u202C"

@Composable
private fun CalendarDayCell(
    summary: DayPnlSummary,
    isSelected: Boolean,
    isToday: Boolean,
    intensity: Double,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hasTrades = summary.closedTradesCount > 0
    val positive = summary.netPnlUsd > 0.0
    val negative = summary.netPnlUsd < 0.0
    val accent = when {
        !hasTrades -> AurumColors.TextMuted
        positive -> AurumColors.Green
        negative -> AurumColors.Red
        else -> AurumColors.Gold
    }
    val background = if (hasTrades) {
        accent.copy(alpha = (0.10 + 0.30 * intensity).toFloat())
    } else {
        AurumColors.SurfaceAlt.copy(alpha = 0.40f)
    }
    val borderColor = when {
        isSelected -> AurumColors.Gold
        isToday -> AurumColors.Cyan
        !hasTrades -> AurumColors.Line.copy(alpha = 0.45f)
        else -> accent.copy(alpha = 0.60f)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .border(
                width = if (isSelected || isToday) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.dayOfMonth.toString(),
                color = if (hasTrades) AurumColors.TextPrimary else AurumColors.TextMuted,
                fontWeight = FontWeight.Bold,
                // اندازهٔ ثابت و یکسان برای شمارهٔ روز در همهٔ خانه‌ها
                fontSize = 11.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasTrades) {
                Text(
                    text = "${summary.closedTradesCount}",
                    color = accent.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hasTrades) {
            Text(
                text = compactSignedUsd(summary.netPnlUsd),
                color = accent,
                fontWeight = FontWeight.Bold,
                // همان اندازهٔ ثابت ۹sp برای مبلغ روز؛ softWrap خاموش است تا عدد هرگز نشکند
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(intensity.toFloat().coerceIn(0.08f, 1f))
                        .height(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        } else {
            Text(
                text = "—",
                color = AurumColors.TextMuted.copy(alpha = 0.55f),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** قالب فشردهٔ مبلغ برای داخل خانهٔ تقویم (بدون شکستن عدد در عرض کم). */
private fun compactSignedUsd(value: Double): String {
    val sign = if (value >= 0.0) "+" else "−"
    val amount = abs(value)
    val body = when {
        amount >= 10_000 -> String.format(Locale.US, "%.0fk", amount / 1_000.0)
        amount >= 1_000 -> String.format(Locale.US, "%.1fk", amount / 1_000.0)
        amount >= 100 -> String.format(Locale.US, "%.0f", amount)
        amount >= 10 -> String.format(Locale.US, "%.1f", amount)
        else -> String.format(Locale.US, "%.2f", amount)
    }
    return ltrIsolated(sign + "$" + body)
}

@Composable
private fun ClosedJournalTradeRow(
    trade: PaperTrade,
    onDelete: () -> Unit,
    onOpenChart: () -> Unit,
) {
    var detailsExpanded by rememberSaveable("closed_row_${trade.id}") { mutableStateOf(false) }
    val pnl = trade.pnlUsd ?: 0.0
    val isWin = pnl > 0.0
    val isLoss = pnl < 0.0
    val isBuy = trade.action == SignalAction.BUY
    val color = when {
        isWin -> AurumColors.Green
        isLoss -> AurumColors.Red
        else -> AurumColors.Gold
    }
    val cardBgColor = when {
        isWin -> AurumColors.Green.copy(alpha = 0.13f)
        isLoss -> AurumColors.Red.copy(alpha = 0.13f)
        else -> AurumColors.SurfaceAlt
    }
    val outcomeBadgeText = when {
        isWin -> "✅ سودده (WIN)"
        isLoss -> "❌ ضررده (LOSS)"
        else -> "⚖️ سر‌به‌سر"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBgColor)
            .border(1.6.dp, color.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .clickable { detailsExpanded = !detailsExpanded }
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // ── ردیف ۱: نشان وضعیت سود/ضرر و شماره معامله در راست | مبلغ سود/ضرر و ضریب R در چپ (بدون شکستگی حروف) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = outcomeBadgeText,
                    color = color,
                )
                Pill(
                    text = trade.displayTradeNumberBadgeFa,
                    color = AurumColors.Gold,
                )
                // ردیف‌های بک‌تست در ژورنال می‌نشینند تا درسشان گرفته شود، ولی پولشان به
                // موجودی اضافه نمی‌شود؛ این نشان همین را بی‌ابهام می‌کند.
                if (trade.isBacktest) {
                    Pill(
                        text = "🧪 بک‌تست · بدون اثر روی پول",
                        color = AurumColors.TextMuted,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rText = trade.rMultiple?.let { r ->
                    String.format(Locale.US, " (%s%.2fR)", if (r >= 0) "+" else "", r)
                }.orEmpty()
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.22f))
                            .border(1.dp, color.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s$%.2f%s",
                                if (pnl >= 0) "+" else "-",
                                abs(pnl),
                                rText,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = color,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Pill(
                    text = if (detailsExpanded) "▲" else "▼",
                    color = AurumColors.TextSecondary,
                )
            }
        }

        // ── ردیف ۲: نماد (کاملاً افقی و خوانا)، جهت و تایم‌فریم در راست | نام استراتژی در چپ ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // نام شاخص (فارسی) بالا، کد دقیق نماد پایین — به‌جای کد تکی/دوتایی تنها.
            Column(modifier = Modifier.clickable { onOpenChart() }) {
                Text(
                    text = com.aurum.edge.core.SymbolDisplay.nameFa(trade.symbol),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = trade.symbol,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Pill(
                text = "${if (isBuy) "🟢 خرید" else "🔴 فروش"} · ${trade.interval.label}",
                color = if (isBuy) AurumColors.Green else AurumColors.Red,
            )
            Text(
                text = "🎯 ${trade.strategyTitleFa.substringBefore('(').trim()}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }

        // ── ردیف ۳: نوار قیمت ورود به خروج و زمان بسته شدن معامله ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AurumColors.Surface.copy(alpha = 0.72f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ورود: \u200E${formatPrice(trade.entry)}  ←  خروج: \u200E${trade.exitPrice?.let { formatPrice(it) } ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = formatDateTime(trade.closedAt ?: trade.openedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (detailsExpanded) {
            Text(
                text = "ورود: ${formatPrice(trade.entry)} · خروج: ${trade.exitPrice?.let { formatPrice(it) } ?: "—"} · SL: ${formatPrice(trade.stopLoss)} · TP: ${formatPrice(trade.takeProfit)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
            )

            Text(
                text = "📐 ${trade.buildRrFormulaSummaryFa()} · ${trade.buildOptimalRrReasonFa()}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
            )

            if (trade.preEntry3CandleCategoryFa.isNotBlank() || trade.preEntry3Candles.isNotEmpty()) {
                val catBadge = trade.preEntry3CandleCategoryFa.ifBlank {
                    com.aurum.edge.core.PreEntry3CandleCategory.fromCode(trade.preEntry3CandleCategoryCode).badgeFa
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AurumColors.Surface)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "🕯️ ۳ کندل قبل از ورود: $catBadge",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    if (trade.preEntry3Candles.isNotEmpty()) {
                        trade.preEntry3Candles.forEach { slot ->
                            Text(
                                text = "• ${slot.slotTitleFa}: ${slot.candleAnatomyFa} (${slot.priceVsTenkanFa} · ${slot.priceVsKumoFa})",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }
                    } else if (trade.preEntry3CandleSummaryFa.isNotBlank()) {
                        Text(
                            text = trade.preEntry3CandleSummaryFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }
            }

            if (trade.isBacktest && trade.lessonFa.isNotBlank()) {
                Text(
                    text = "🎓 درس این معامله: ${trade.lessonFa}",
                    color = AurumColors.Gold,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
            if (trade.note.isNotBlank()) {
                Text(
                    text = "یادداشت: ${trade.note}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Gold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = "📈 مشاهده چارت نماد",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { onOpenChart() },
                )
                Pill(
                    text = "🗑️ حذف معامله",
                    color = AurumColors.Red,
                    modifier = Modifier.clickable { onDelete() },
                )
            }
        }
    }
}

@Composable
private fun PhoneVaultAnd3CandleSummaryCard(
    viewModel: AurumViewModel,
    trades: List<PaperTrade>,
) {
    val vaultStatus by viewModel.phoneVaultStatus.collectAsStateWithLifecycle()
    val dailyBackups by viewModel.phoneDailyBackups.collectAsStateWithLifecycle()

    val threeCandleStats = remember(trades) {
        com.aurum.edge.core.PreEntry3CandleCategory.entries.mapNotNull { cat ->
            val matched = trades.filter {
                val code = it.preEntry3CandleCategoryCode.ifBlank {
                    com.aurum.edge.core.PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.code
                }
                code == cat.code
            }
            if (matched.isEmpty()) return@mapNotNull null
            val closed = matched.filter { !it.isOpen }
            val wins = closed.count { (it.pnlUsd ?: 0.0) > 0.0 }
            val wr = if (closed.isNotEmpty()) (wins * 100.0) / closed.size else 0.0
            val netPnl = closed.sumOf { it.pnlUsd ?: 0.0 }
            Triple(cat, matched.size to wr, netPnl)
        }
    }

    SectionCard(
        title = "💾 خزانه حافظه گوشی (بک‌آپ روزانه + کندل ۱ ماهه + دسته‌بندی ۳ کندل قبل)",
        subtitle = "شاردینگ روزانه معاملات برای سرعت لود بالا، بک‌آپ خودکار هر روز، خزانه ۳۵ روزه کندل‌ها و آمار ۳ کندل قبل از ورود",
        initiallyExpanded = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "بک‌آپ‌های روزانه",
                    value = "${vaultStatus.totalDailyBackups} روز",
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "شاردهای معاملات",
                    value = "${vaultStatus.totalShardedDays} پارتیشن",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "خزانه ۱ ماهه کندل",
                    value = "${vaultStatus.totalStoredCandlesCount}",
                    valueColor = AurumColors.Green,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "📂 مسیر حافظه گوشی: ${vaultStatus.vaultPathDisplay} · آخرین بک‌آپ روزانه: ${vaultStatus.latestBackupDateKey}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.triggerManualDailyBackupNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("💾 ثبت بک‌آپ امروز در گوشی", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { viewModel.restoreAllDataAndSnapshotsFromPhone() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("📂 بازخوانی از حافظه گوشی", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                }
            }

            if (dailyBackups.isNotEmpty()) {
                Text(
                    text = "📅 آخرین بک‌آپ‌های خودکار روزانه در گوشی:",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                dailyBackups.take(5).forEach { bk ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📅 ${bk.dateKey} · ${bk.dayTradesCount} معامله روز (${bk.totalTradesCount} کل)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                        val sign = if (bk.dayNetPnlUsd >= 0.0) "+" else ""
                        Text(
                            text = String.format(Locale.US, "%s$%.2f", sign, bk.dayNetPnlUsd),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bk.dayNetPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (threeCandleStats.isNotEmpty()) {
                Text(
                    text = "🕯️ دسته‌بندی معاملات بر اساس «۳ کندل قبل از ورود» (C-2, C-1, C0):",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                threeCandleStats.forEach { (cat, countAndWr, netPnl) ->
                    val (cnt, wr) = countAndWr
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cat.badgeFa,
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = cat.titleFa,
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "$cnt معامله · برد ${wr.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            val sign = if (netPnl >= 0.0) "+" else ""
                            Text(
                                text = String.format(Locale.US, "%s$%.2f", sign, netPnl),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (netPnl >= 0.0) AurumColors.Green else AurumColors.Red,
                            )
                        }
                    }
                }
            }

            if (vaultStatus.mistakeLessons.isNotEmpty()) {
                Text(
                    text = "🔬 درس‌های استخراج‌شده از کندل‌های ۱ ماه اخیر روی معاملات ضررده:",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                vaultStatus.mistakeLessons.take(4).forEach { lesson ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "${lesson.symbol} [${lesson.intervalLabel}] · ${lesson.mistakeBadgeFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = lesson.mistakeExplanationFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            text = "💡 ${lesson.lessonLearnedFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Green,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionalQuantTearSheetSection(
    report: InstitutionalTearSheetReport,
    savedFiles: List<SavedJournalExportFile>,
    replaceOnImport: Boolean,
    onToggleReplaceOnImport: (Boolean) -> Unit,
    onOpenInteractiveModal: () -> Unit,
    onSaveDirectToDownloads: (JournalExportFormat) -> Unit,
    onSaveAsCustomFolder: (JournalExportFormat) -> Unit,
    onPickBackupFileToImport: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onRestoreSavedFile: (SavedJournalExportFile) -> Unit,
    onOpenSavedFile: (SavedJournalExportFile) -> Unit,
    onShareSavedFile: (SavedJournalExportFile) -> Unit,
    onDeleteSavedFile: (SavedJournalExportFile) -> Unit,
    onShareFormat: (JournalExportFormat) -> Unit,
    onOpenFormat: (JournalExportFormat) -> Unit,
    onCopySummary: () -> Unit,
    onOpenChart: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── ۱. مرکز خروجی چهارگانه، ذخیره مستقیم در گوشی و پیش‌نمایش تعاملی ──
        SectionCard(
            title = "📤 مرکز خروجی، ذخیره مستقیم در گوشی و کارنامه کوانت (Tear Sheet)",
            subtitle = "دامنه گزارش: ${report.filterScopeLabelFa} · ${report.closedTradesCount} معامله بسته‌شده و ${report.openTradesCount} معامله باز",
            trailing = {
                Pill(
                    text = "📋 کپی خلاصه",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { onCopySummary() },
                )
            },
        ) {
            // دکمه بزرگ بررسی تعاملی در داخل اپ
            Button(
                onClick = onOpenInteractiveModal,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.Gold,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "🔍 باز کردن داشبورد تعاملی بررسی کامل ژورنال (جستجو، فیلتر و کارنامه کامل در داخل اپ)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // کارت‌های ۴ فرمت خروجی استاندارد جهانی
            JournalExportFormat.entries.forEach { fmt ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = fmt.titleFa,
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(
                            text = ".${fmt.extension.uppercase()}",
                            color = AurumColors.Cyan,
                        )
                    }
                    Text(
                        text = fmt.subtitleFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    // ردیف اول: ذخیره مستقیم در پوشه Downloads و انتخاب پوشه دلخواه (Save As)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onSaveDirectToDownloads(fmt) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Green.copy(alpha = 0.22f),
                                contentColor = AurumColors.Green,
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = "💾 ذخیره در پوشه Downloads",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Button(
                            onClick = { onSaveAsCustomFolder(fmt) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Gold.copy(alpha = 0.22f),
                                contentColor = AurumColors.Gold,
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = "📂 انتخاب پوشه دلخواه (Save As)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    // ردیف دوم: اشتراک‌گذاری و باز کردن مستقیم
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onShareFormat(fmt) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Cyan.copy(alpha = 0.2f),
                                contentColor = AurumColors.Cyan,
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = "📤 اشتراک‌گذاری فایل",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        OutlinedButton(
                            onClick = { onOpenFormat(fmt) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = "🌐 باز کردن مستقیم",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                        }
                    }
                }
            }
        }

        // ── ۱.۵. مرکز پشتیبان‌گیری، بازگردانی (Import / Restore) و مدیریت فایل‌های ذخیره‌شده ──
        SectionCard(
            title = "📥 بازگردانی بکاپ ژورنال (Import / Restore) و مدیریت فایل‌های ذخیره‌شده",
            subtitle = "بازگردانی معاملات از فایل JSON یا CSV ذخیره‌شده در حافظه گوشی یا کلیپ‌بورد و مدیریت تمام خروجی‌ها",
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.Background)
                    .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (replaceOnImport) "حالت بازگردانی: 🔄 جایگزینی کامل ژورنال فعلی" else "حالت بازگردانی: ➕ ادغام هوشمند با معاملات فعلی (بدون حذف)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (replaceOnImport) AurumColors.Orange else AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "با روشن کردن کلید، معاملات فعلی پاک و فایل بکاپ جایگزین می‌شود؛ در حالت خاموش، بکاپ با معاملات فعلی ادغام می‌گردد.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
                Switch(
                    checked = replaceOnImport,
                    onCheckedChange = onToggleReplaceOnImport,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPickBackupFileToImport,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Cyan,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "📂 انتخاب فایل بکاپ (.JSON / .CSV) از گوشی",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = onImportFromClipboard,
                    modifier = Modifier.weight(0.75f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "📋 بازگردانی از کلیپ‌بورد",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Gold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "🗂️ فایل‌های خروجی و بکاپ ذخیره‌شده در اپ (${savedFiles.size}):",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )

            if (savedFiles.isEmpty()) {
                Text(
                    text = "هنوز فایلی ذخیره نشده است. با زدن «💾 ذخیره در پوشه Downloads» روی هر کدام از فرمت‌های بالا، فایل هم در پوشه Download/AurumEdge گوشی و هم در این فهرست ذخیره می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            } else {
                savedFiles.take(12).forEach { saved ->
                    val kb = max(1L, saved.sizeBytes / 1024L)
                    val canRestore = saved.format == JournalExportFormat.JSON_QUANT || saved.format == JournalExportFormat.CSV_EXCEL
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.Background)
                            .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = saved.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = AurumColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(
                                text = "${kb}KB · .${saved.format.extension.uppercase()}",
                                color = AurumColors.Cyan,
                            )
                        }
                        Text(
                            text = "زمان ذخیره: ${saved.modifiedAtFormatted}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Pill(
                                text = "🌐 باز کردن",
                                color = AurumColors.Gold,
                                modifier = Modifier.clickable { onOpenSavedFile(saved) },
                            )
                            Pill(
                                text = "📤 اشتراک‌گذاری",
                                color = AurumColors.Cyan,
                                modifier = Modifier.clickable { onShareSavedFile(saved) },
                            )
                            if (canRestore) {
                                Pill(
                                    text = "♻️ بازگردانی این بکاپ",
                                    color = AurumColors.Green,
                                    modifier = Modifier.clickable { onRestoreSavedFile(saved) },
                                )
                            }
                            Pill(
                                text = "🗑️ حذف",
                                color = AurumColors.Red,
                                modifier = Modifier.clickable { onDeleteSavedFile(saved) },
                            )
                        }
                    }
                }
            }
        }

        // ── ۲. کارت امتیاز کیفیت سیستم (Van Tharp SQN + 12 شاخص کوانت) ─────────
        SectionCard(
            title = "🧬 شاخص‌های کوانت و ریسک نهادی (Institutional Quant Metrics)",
            subtitle = "ارزیابی ریاضی کیفیت سیستم معاملاتی بر اساس استانداردهای صندوق‌های کوانت و Van Tharp SQN",
            trailing = {
                Pill(
                    text = report.sqnGradeFa,
                    color = if ((report.sqnScore ?: 0.0) >= 1.6) AurumColors.Green else AurumColors.Gold,
                )
            },
        ) {
            val fmt2: (Double?) -> String = { v -> v?.let { String.format(Locale.US, "%.2f", it) } ?: "—" }
            val fmtPct: (Double?) -> String = { v -> v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—" }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "امتیاز SQN",
                    value = fmt2(report.sqnScore),
                    valueColor = if ((report.sqnScore ?: 0.0) >= 1.6) AurumColors.Green else AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "شارپ (Sharpe)",
                    value = fmt2(report.sharpeRatio),
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "سورتینو (Sortino)",
                    value = fmt2(report.sortinoRatio),
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "فرمول کلی (Kelly)",
                    value = fmtPct(report.kellyCriterionPct),
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "امید ریاضی ($)",
                    value = report.expectancyUsd?.let {
                        String.format(Locale.US, "%s$%.2f", if (it >= 0) "+" else "-", abs(it))
                    } ?: "—",
                    valueColor = if ((report.expectancyUsd ?: 0.0) >= 0.0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "امید ریاضی (R)",
                    value = report.expectancyR?.let {
                        String.format(Locale.US, "%s%.2fR", if (it >= 0) "+" else "", it)
                    } ?: "—",
                    valueColor = if ((report.expectancyR ?: 0.0) >= 0.0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "نسبت Payoff",
                    value = fmt2(report.payoffRatio),
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "ضریب ریکاوری",
                    value = fmt2(report.recoveryFactor),
                    valueColor = AurumColors.Green,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "حداکثر افت (Max DD)",
                    value = String.format(Locale.US, "-$%.2f (%.2f%%)", report.maxDrawdownUsd, report.maxDrawdownPct),
                    valueColor = AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "میانگین R:R بهینه",
                    value = report.avgOptimalRr?.let { String.format(Locale.US, "1:%.2f", it) } ?: "—",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "کل کارمزد مرجع",
                    value = String.format(Locale.US, "$%.2f", report.totalVenueFeesUsd),
                    valueColor = AurumColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "میانگین زمان باز",
                    value = report.avgHoldDurationMinutes?.let {
                        String.format(Locale.US, "%.0f دقیقه", it)
                    } ?: "—",
                    valueColor = AurumColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── ۳. منحنی رشد سرمایه و افت سرمایه (Native Equity Curve & Drawdown) ──
        NativeEquityAndDrawdownChartCard(report = report)

        // ── ۴. مقایسه تایم‌فریم 15m در برابر 5m و جهت Long در برابر Short ─────
        SectionCard(
            title = "⏱️ ماتریس مقایسه‌ای تایم‌فریم‌ها (15m vs 5m) و جهت معاملات (Long vs Short)",
            subtitle = "بررسی دقیق سودآوری و نرخ برد در تایم‌فریم‌های ورود و جهت‌های خرید و فروش",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 15m vs 5m
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "تایم‌فریم ۱۵ دقیقه (15m)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "تعداد: ${report.tf15mCount} · برد: ${report.tf15mWinRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = String.format(Locale.US, "%s$%.2f", if (report.tf15mNetPnl >= 0) "+" else "-", abs(report.tf15mNetPnl)),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.tf15mNetPnl >= 0) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "تایم‌فریم ۵ دقیقه (5m)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Cyan,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "تعداد: ${report.tf5mCount} · برد: ${report.tf5mWinRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = String.format(Locale.US, "%s$%.2f", if (report.tf5mNetPnl >= 0) "+" else "-", abs(report.tf5mNetPnl)),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.tf5mNetPnl >= 0) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "معاملات خرید (LONG)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "تعداد: ${report.longTradesCount} · برد: ${report.longWinRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = String.format(Locale.US, "%s$%.2f", if (report.longNetPnl >= 0) "+" else "-", abs(report.longNetPnl)),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.longNetPnl >= 0) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "معاملات فروش (SHORT)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "تعداد: ${report.shortTradesCount} · برد: ${report.shortWinRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = String.format(Locale.US, "%s$%.2f", if (report.shortNetPnl >= 0) "+" else "-", abs(report.shortNetPnl)),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.shortNetPnl >= 0) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── ۵. جدول رتبه‌بندی نمادهای معاملاتی در کارنامه ──────────────────────
        SectionCard(
            title = "🌍 تفکیک عملکرد بر اساس ۱۵ نماد بازار (Symbol Tear-Sheet Matrix)",
            subtitle = "روی هر نماد بزنید تا چارت آن باز شود",
        ) {
            val activeSymbols = report.symbolLeaderboard.filter { it.closedTrades > 0 || it.openTrades > 0 }
                .ifEmpty { report.symbolLeaderboard.take(6) }
            activeSymbols.forEach { sym ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Background)
                        .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                        .clickable { onOpenChart(sym.symbol) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${sym.symbol} · ${sym.labelFa}",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "بسته: ${sym.closedTrades} (${sym.wins}W/${sym.losses}L) · باز: ${sym.openTrades} · کارمزد: $${String.format(Locale.US, "%.2f", sym.totalFeesUsd)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.US, "%s$%.2f", if (sym.netPnlUsd >= 0) "+" else "-", abs(sym.netPnlUsd)),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (sym.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = sym.winRatePct?.let { String.format(Locale.US, "Win: %.1f%%", it) } ?: "بدون معامله",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeEquityAndDrawdownChartCard(report: InstitutionalTearSheetReport) {
    SectionCard(
        title = "📈 منحنی رشد سرمایه و افت سرمایه (Equity Curve & Drawdown)",
        subtitle = "روند تجمعی سرمایه از $${String.format(Locale.US, "%,.0f", report.initialCapital)} تا $${String.format(Locale.US, "%,.2f", report.currentEquity)}",
    ) {
        val points = report.equityCurve
        if (points.size <= 1) {
            Text(
                text = "هنوز معامله بسته‌شده‌ای برای رسم منحنی رشد سرمایه ثبت نشده است. به‌محض بسته شدن اولین معامله، منحنی رشد سرمایه و Drawdown رسم می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        } else {
            val equities = points.map { it.cumulativeEquityUsd }
            val minEq = equities.minOrNull() ?: report.initialCapital
            val maxEq = equities.maxOrNull() ?: report.initialCapital
            val span = (maxEq - minEq).coerceAtLeast(1.0)
            val isNetPositive = (points.last().cumulativePnlUsd) >= 0.0
            val lineColor = if (isNetPositive) AurumColors.Green else AurumColors.Red

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AurumColors.Background)
                    .border(1.dp, AurumColors.Border, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val n = points.size
                    val path = Path()
                    points.forEachIndexed { idx, pt ->
                        val x = if (n <= 1) 0f else (idx.toFloat() / (n - 1).toFloat()) * w
                        val y = h - (((pt.cumulativeEquityUsd - minEq) / span).toFloat() * (h * 0.84f) + h * 0.08f)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    // Baseline initial capital
                    val baseY = h - (((report.initialCapital - minEq) / span).toFloat() * (h * 0.84f) + h * 0.08f)
                    drawLine(
                        color = Color(0xFF475569),
                        start = Offset(0f, baseY.coerceIn(0f, h)),
                        end = Offset(w, baseY.coerceIn(0f, h)),
                        strokeWidth = 1.5f,
                    )
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveTearSheetModalDialog(
    report: InstitutionalTearSheetReport,
    onDismiss: () -> Unit,
    onShareFormat: (JournalExportFormat) -> Unit,
    onOpenFormat: (JournalExportFormat) -> Unit,
    onCopySummary: () -> Unit,
) {
    var viewTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Interactive HTML5 WebView, 1 = Native Searchable Audit Table, 2 = Raw CSV/JSON Inspector
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var outcomeFilter by rememberSaveable { mutableStateOf("ALL") } // ALL, WIN, LOSS, OPEN

    val htmlContent = remember(report) {
        JournalExportEngine.generateInteractiveHtmlTearSheet(report)
    }
    val csvContent = remember(report) {
        JournalExportEngine.generateCsvContent(report)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AurumColors.Background)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Surface)
                        .border(1.dp, AurumColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📊 داشبورد تعاملی کارنامه کوانت (Tear-Sheet Inspector)",
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "SQN: ${report.sqnScore?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} (${report.sqnGradeFa}) · سود خالص: $${String.format(Locale.US, "%,.2f", report.netPnlUsd)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(
                            text = "📤 ارسال HTML",
                            color = AurumColors.Cyan,
                            modifier = Modifier.clickable { onShareFormat(JournalExportFormat.HTML_TEAR_SHEET) },
                        )
                        Pill(
                            text = "📄 ارسال PDF",
                            color = AurumColors.Green,
                            modifier = Modifier.clickable { onShareFormat(JournalExportFormat.PDF_REPORT) },
                        )
                        Pill(
                            text = "✖ بستن",
                            color = AurumColors.Red,
                            modifier = Modifier.clickable { onDismiss() },
                        )
                    }
                }

                // Mode Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(
                        text = "🌐 نمای تعاملی HTML5 Tear-Sheet",
                        color = if (viewTab == 0) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { viewTab = 0 },
                    )
                    Pill(
                        text = "🔎 جدول ممیزی و فیلتر معاملات (${report.trades.size})",
                        color = if (viewTab == 1) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { viewTab = 1 },
                    )
                    Pill(
                        text = "📊 پیش‌نمایش داده خام CSV اکسل",
                        color = if (viewTab == 2) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { viewTab = 2 },
                    )
                    Pill(
                        text = "📋 کپی خلاصه",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onCopySummary() },
                    )
                }

                when (viewTab) {
                    0 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AurumColors.Border, RoundedCornerShape(12.dp)),
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        webViewClient = WebViewClient()
                                        loadDataWithBaseURL(
                                            "https://aurum.edge.local/",
                                            htmlContent,
                                            "text/html",
                                            "UTF-8",
                                            null,
                                        )
                                    }
                                },
                            )
                        }
                    }

                    1 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("جستجوی نماد، استراتژی، تایم‌فریم یا دلیل خروج...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "ALL" to "همه (${report.trades.size})",
                                    "WIN" to "بردها (${report.winsCount})",
                                    "LOSS" to "باخت‌ها (${report.lossesCount})",
                                    "OPEN" to "معاملات باز (${report.openTradesCount})",
                                ).forEach { (k, label) ->
                                    Pill(
                                        text = label,
                                        color = if (outcomeFilter == k) AurumColors.Gold else AurumColors.TextMuted,
                                        modifier = Modifier.clickable { outcomeFilter = k },
                                    )
                                }
                            }

                            val matchingTrades = remember(report.trades, searchQuery, outcomeFilter) {
                                report.trades.filter { t ->
                                    val matchesOutcome = when (outcomeFilter) {
                                        "WIN" -> !t.isOpen && (t.pnlUsd ?: 0.0) > 0.0
                                        "LOSS" -> !t.isOpen && (t.pnlUsd ?: 0.0) <= 0.0
                                        "OPEN" -> t.isOpen
                                        else -> true
                                    }
                                    val q = searchQuery.trim().lowercase(Locale.US)
                                    val matchesQuery = q.isEmpty() ||
                                        t.symbol.lowercase(Locale.US).contains(q) ||
                                        t.strategyTitleFa.lowercase(Locale.US).contains(q) ||
                                        t.entryTimeframe.lowercase(Locale.US).contains(q) ||
                                        (t.exitReason ?: "").lowercase(Locale.US).contains(q)
                                    matchesOutcome && matchesQuery
                                }
                            }

                            matchingTrades.forEach { t ->
                                val pnl = t.pnlUsd ?: 0.0
                                val c = if (t.isOpen) AurumColors.Cyan else if (pnl >= 0.0) AurumColors.Green else AurumColors.Red
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AurumColors.Surface)
                                        .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "${t.symbol} · ${if (t.action == SignalAction.BUY) "خرید (LONG)" else "فروش (SHORT)"} · تایم ${t.entryTimeframe}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = c,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = if (t.isOpen) "باز (OPEN)" else String.format(Locale.US, "%s$%.2f (%s%.2fR)", if (pnl >= 0) "+" else "-", abs(pnl), if ((t.rMultiple ?: 0.0) >= 0) "+" else "", t.rMultiple ?: 0.0),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = c,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        text = "استراتژی: ${t.strategyTitleFa}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AurumColors.Gold,
                                    )
                                    Text(
                                        text = "ورود: ${formatPrice(t.entry)} · خروج: ${t.exitPrice?.let { formatPrice(it) } ?: "—"} · SL: ${formatPrice(t.stopLoss)} · TP: ${formatPrice(t.takeProfit)} · R:R بهینه: 1:${String.format(Locale.US, "%.2f", t.optimalRiskReward)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AurumColors.TextPrimary,
                                    )
                                    Text(
                                        text = "مرجع: ${t.venueNameFa} · کارمزد: $${String.format(Locale.US, "%.2f", t.estimatedRoundTripFeeUsd)} · وضعیت: ${t.exitReason ?: "در جریان"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AurumColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AurumColors.Surface)
                                .border(1.dp, AurumColors.Border, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "پیش‌نمایش فایل CSV استاندارد اکسل (۲۹ ستون ممیزی)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.Gold,
                                    fontWeight = FontWeight.Bold,
                                )
                                Pill(
                                    text = "📤 اشتراک‌گذاری فایل CSV",
                                    color = AurumColors.Cyan,
                                    modifier = Modifier.clickable { onShareFormat(JournalExportFormat.CSV_EXCEL) },
                                )
                            }
                            Text(
                                text = csvContent.removePrefix("\uFEFF"),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

