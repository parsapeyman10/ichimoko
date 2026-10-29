package com.aurum.edge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.notify.TradeAlarmKind
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs

@Composable
fun HomeScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onChartSymbol: (String) -> Unit,
    onJournal: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val tradesState by viewModel.trades.collectAsStateWithLifecycle()
    val openTradesList = remember(tradesState) { tradesState.filter { it.isOpen } }
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val scan by viewModel.pairScan.collectAsStateWithLifecycle()
    val strategyReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()
    val neuralState by viewModel.neuralState.collectAsStateWithLifecycle()

    val freshPrices = com.aurum.edge.data.OpenPositionQuotePolicy.prices(openTradesList, liveTicks, now)
    val frozenPrices = openTradesList.mapNotNull { trade ->
        OpenPositionDisplay.forTrade(trade, lastMarks)?.let { trade.symbol to it.price }
    }.toMap()
    val displayPrices = frozenPrices + freshPrices
    val displayPnl = LiveTradeValuation.total(openTradesList, displayPrices)
    val totalMarginUsed = openTradesList.sumOf { it.effectiveMarginUsd }
    val equity = displayPnl?.let { settings.accountBalance + it }
    val freeMargin = equity?.let { (it - totalMarginUsed).coerceAtLeast(0.0) }
    val readyCount = dualReports.values.sumOf { it.actionableReports.size }.takeIf { it > 0 }
        ?: strategyReports.values.count { it.isActionable }

    val balanceStr = String.format(Locale.US, "$%,.2f", settings.accountBalance)
    val equityStr = equity?.let { String.format(Locale.US, "$%,.2f", it) } ?: balanceStr
    val freeMarginStr = freeMargin?.let { String.format(Locale.US, "$%,.2f", it) } ?: balanceStr
    val netPnlStr = (if (stats.netPnl >= 0) "+" else "") + String.format(Locale.US, "$%,.2f", stats.netPnl)
    val floatingPnlStr = displayPnl?.let {
        (if (it >= 0) "+" else "-") + String.format(Locale.US, "$%,.2f", abs(it))
    } ?: "$0.00"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {
        // ── ۱. هدر مدرن موجودی و خلاصه پورتفو (Hero Portfolio Card) ──────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF141B26), Color(0xFF0C1017)),
                    ),
                )
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "موجودی کل حساب (Account Balance)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = balanceStr,
                        style = MaterialTheme.typography.titleLarge,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = "📅 ژورنال",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onJournal() },
                    )
                    Pill(
                        text = "⚙️ تنظیمات",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable { onOpenSettings() },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "اکوئیتی لحظه‌ای",
                    value = equityStr,
                    valueColor = if (displayPnl == null) AurumColors.TextPrimary else if (displayPnl >= 0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "سود/زیان شناور",
                    value = floatingPnlStr,
                    valueColor = if ((displayPnl ?: 0.0) >= 0.0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "مارجین آزاد",
                    value = freeMarginStr,
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "برآیند کل ژورنال",
                    value = netPnlStr,
                    valueColor = if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "نرخ برد (${stats.wins}/${stats.total})",
                    value = stats.winRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "ریسک هر معامله",
                    value = "-$${settings.fixedRiskUsd.toInt()} / +$${settings.fixedRiskUsd.toInt()}×R:R",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── ۲. کارت وضعیت موتور استراتژی‌های هم‌زمان و سشن‌های بازار ───────────────
        SectionCard(
            title = "رادار هم‌زمان ایچیموکو (15m + 5m)",
            subtitle = "${settings.activeStrategiesSummaryFa()} · سیگنال آماده ورود: $readyCount",
            trailing = {
                Pill(
                    text = if (settings.autoPaperTrading) "🟢 ربات فعال" else "⚪ ربات متوقف",
                    color = if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (settings.backgroundMonitor) "🟢 پایش ۲۴/۷" else "⚪ بک‌گراند خاموش",
                        color = if (settings.backgroundMonitor) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onOpenSettings() },
                    )
                    Pill(
                        text = if (settings.avalAiEnabled) "🤖 AI: فعال (بله/نه)" else "🤖 AI: غیرفعال",
                        color = if (settings.avalAiEnabled) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onOpenSettings() },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("BTC/USDT" to "رمزارز", "XAU/USD" to "طلا", "EUR/USD" to "فارکس").forEach { (sym, lbl) ->
                        val closed = MarketHours.sessionWindowFor(sym, now).closed
                        Pill("$lbl: ${if (closed) "بسته" else "باز"}", if (closed) AurumColors.Red else AurumColors.Green)
                    }
                }
            }

            // نوار ۳ آلارم صوتی مجزا (ورود، حد سود TP، حد ضرر SL) با نمایش ولوم و قابلیت تست سریع
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (settings.notifyOnSignal) "🔔 صدا (${settings.alarmVolumePercent}%):" else "🔕 آلارم خاموش:",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (settings.notifyOnSignal) AurumColors.Gold else AurumColors.TextMuted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(
                        text = "⚡ تست ورود",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { viewModel.triggerTestTradeAlarm(TradeAlarmKind.ENTRY) },
                    )
                    Pill(
                        text = "🎯 تست سود (TP)",
                        color = AurumColors.Green,
                        modifier = Modifier.clickable { viewModel.triggerTestTradeAlarm(TradeAlarmKind.TAKE_PROFIT) },
                    )
                    Pill(
                        text = "🛑 تست ضرر (SL)",
                        color = AurumColors.Red,
                        modifier = Modifier.clickable { viewModel.triggerTestTradeAlarm(TradeAlarmKind.STOP_LOSS) },
                    )
                }
            }

            // نوار وضعیت شبکه عصبی خودآموز (FT-Transformer + SwiGLU · 10,000 Trades) و اهرم ایمن ≤ 10x
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt.copy(alpha = 0.7f))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🧠 شبکه عصبی خودآموز (FT-Attention + SwiGLU): ${neuralState.totalLearnedTrades}/10,000 معامله",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (neuralState.isActiveFilterEnabled) {
                            "🛡️ فیلتر شرایط ممنوعه فعال (${neuralState.discoveredNoTradeClustersCount} الگو) · اهرم ایمن ≤ 10x"
                        } else {
                            "🎓 فاز یادگیری در هر ۴ دسته تا ۱۰,۰۰۰ معامله (${neuralState.discoveredNoTradeClustersCount} الگوی ممنوعه شناسایی شد) · اهرم ≤ 10x"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
                Pill(
                    text = "اهرم ≤ 10x",
                    color = AurumColors.Gold,
                )
            }
        }

        // ── ۳. بنر متوازن ۳ دسته بازار ──────────────────────────────────────────
        com.aurum.edge.ui.components.AssetClass4SlotsBanner(
            openTrades = openTradesList,
            livePrices = freshPrices,
            lastKnownPrices = frozenPrices,
            onSelectSymbol = onChartSymbol,
            entryReasons = AssetClass.entries.associateWith { asset ->
                com.aurum.edge.data.MarketEntryReason.forClass(
                    asset,
                    scan,
                    settings.autoPaperTrading,
                    now,
                )
            },
        )

        // ── ۳.۵ رادار زنده طلا، نقره، کریپتو و فارکس (15m + 5m) ─────────────────
        LiveMetalsCryptoForexRadarCard(
            viewModel = viewModel,
            liveTicks = liveTicks,
            lastMarks = lastMarks,
            dualReports = dualReports,
            openTrades = openTradesList,
            now = now,
            onSelectSymbol = onChartSymbol,
        )

        // ── ۴. معاملات باز زنده (با مدل نوار گیج افقی SL ── Entry ── TP و حالت کشویی جزئیات) ──
        LiveOpenTradesTrackingSection(
            viewModel = viewModel,
            currentMarket = market,
            lastMarks = lastMarks,
            now = now,
            onJournal = onJournal,
            onSelectTrade = onChartSymbol,
        )

        // ── ۵. دستیار ترید با بهترین متود ایچیموکو جهان + نکات قابل اصلاح بعدی (کشویی) ──
        val homeAssistantCoaching = remember(market.symbol, dualReports) {
            com.aurum.edge.core.MasterIchimokuTradingAssistant.analyzeSymbolWithMasterMethod(
                symbol = V1Universe.normalize(market.symbol),
                dualReport = dualReports[V1Universe.normalize(market.symbol)],
            )
        }
        val homeImprovementNotes = remember(tradesState, dualReports, settings.baseStrategyConfig) {
            com.aurum.edge.core.MasterIchimokuTradingAssistant.generateActionableImprovementNotes(
                trades = tradesState,
                dualReports = dualReports,
                currentConfig = settings.baseStrategyConfig,
            )
        }
        SectionCard(
            title = "🧭 دستیار استاد ایچیموکو + نکات قابل اصلاح بعدی (${homeImprovementNotes.size})",
            subtitle = "تحلیل ۴ ستون هوسودا روی نماد فعال (${market.symbol}) و لیست نکات فنی برای بهبود استراتژی‌ها",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                homeAssistantCoaching?.let { c ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${c.symbol} [${c.timeframeLabel}] · ${c.masterGradeFa}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Pill(text = "${c.worldClassIchimokuScore}/100", color = AurumColors.Cyan)
                        }
                        Text(text = "🌊 ${c.hosodaWaveTypeFa}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
                        Text(text = "⏳ ${c.hosodaTimeCycleStatusFa}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                        Text(text = "🎯 ${c.keisanchiTargetsSummaryFa}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🛠️ نکات قابل اصلاح برای ارتقای بعدی:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = "📋 کپی همه نکات",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { viewModel.copyAssistantImprovementNotes(homeImprovementNotes) },
                    )
                }
                homeImprovementNotes.take(4).forEach { note ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "${note.priority.badgeFa} · ${note.categoryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = note.problemDetectedFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            text = "🛠️ اصلاح: ${note.exactFixInstructionFa}",
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
private fun LiveMetalsCryptoForexRadarCard(
    viewModel: AurumViewModel,
    liveTicks: Map<String, PriceTick>,
    lastMarks: Map<String, DisplayMark>,
    dualReports: Map<String, com.aurum.edge.core.DualTimeframeSymbolReport>,
    openTrades: List<PaperTrade>,
    now: Long,
    onSelectSymbol: (String) -> Unit,
) {
    val isScanning by viewModel.isScanningBtcStrategy.collectAsStateWithLifecycle()
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var radarExpanded by rememberSaveable("radar_closed_default_v3918") { mutableStateOf(false) }

    val symbolsInCategory = remember(selectedCategory) {
        when (selectedCategory) {
            0 -> listOf("XAU/USD", "XAG/USD", "DOW")
            1 -> V1Universe.cryptoSymbols
            2 -> listOf("EUR/USD", "GBP/USD", "USD/JPY", "EUR/JPY", "GBP/JPY", "AUD/USD", "USD/CHF")
            else -> V1Universe.allSymbols
        }
    }

    SectionCard(
        title = "🥇 رادار زنده طلا، نقره، کریپتو و فارکس (15m + 5m)",
        subtitle = "قیمت اسپات هم‌تراز، وضعیت ۲۴ استراتژی ایچیموکو (۱۵ ترکیب + ۹ کتاب درایو) و اسکن فوری تمام بازارها",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = if (isScanning) "⏳ در حال اسکن..." else "🔄 اسکن فوری",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable {
                        if (!isScanning) viewModel.scanAndEvaluateBitcoinStrategyNow(userInitiated = true)
                    },
                )
                Pill(
                    text = if (radarExpanded) "▲ جمع کردن" else "▼ باز کردن",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { radarExpanded = !radarExpanded },
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "🥇 طلا، نقره و داوجونز (۳)",
                1 to "₿ رمزارزهای کریپتو (۵)",
                2 to "💱 جفت‌ارزهای فارکس (۷)",
                3 to "🌐 همه ۱۵ نماد",
            ).forEach { (idx, label) ->
                Pill(
                    text = label,
                    color = if (selectedCategory == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable {
                        selectedCategory = idx
                        radarExpanded = true
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = radarExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                symbolsInCategory.forEach { sym ->
                    val dual = dualReports[sym]
                    val tick = liveTicks[sym]
                    val mark = lastMarks[sym]
                    val displayPrice = tick?.price
                        ?: dual?.primaryReport?.livePrice?.takeIf { it > 0.0 }
                        ?: mark?.price
                    val openCount = openTrades.count { V1Universe.normalize(it.symbol) == sym }
                    val sessionClosed = MarketHours.closedFor(sym, now)

                    val rep15 = dual?.report15m
                    val rep5 = dual?.report5m
                    val primaryReadiness = dual?.primaryReport?.readiness
                    val badgeColor = when (primaryReadiness) {
                        StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green
                        StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red
                        StrategyReadinessState.WATCH_SETUP -> AurumColors.Gold
                        else -> if (sessionClosed) AurumColors.TextMuted else AurumColors.Cyan
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AurumColors.SurfaceAlt.copy(alpha = 0.75f))
                            .border(1.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .clickable { onSelectSymbol(sym) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sym,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = AurumColors.Gold,
                                )
                                if (openCount > 0) {
                                    Pill("$openCount باز", AurumColors.Green)
                                }
                                if (sessionClosed) {
                                    Pill("بازار بسته", AurumColors.Red)
                                }
                            }
                            Text(
                                text = buildString {
                                    append("15m: ")
                                    append(rep15?.readinessShortFa() ?: "در حال دریافت کندل")
                                    append(" · 5m: ")
                                    append(rep5?.readinessShortFa() ?: "در حال دریافت کندل")
                                    dual?.actionableReports?.size?.takeIf { it > 0 }?.let { cnt ->
                                        append(" ($cnt استراتژی آماده)")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = displayPrice?.let { formatPrice(it) } ?: "...",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AurumColors.TextPrimary,
                                )
                            }
                            Text(
                                text = tick?.provider ?: "📊 چارت زنده ↗",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveOpenTradesTrackingSection(
    viewModel: AurumViewModel,
    currentMarket: MarketState,
    lastMarks: Map<String, DisplayMark>,
    now: Long,
    onJournal: () -> Unit,
    onSelectTrade: (String) -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }
    var expandAllDrawers by rememberSaveable { mutableStateOf(false) }

    val totalOpenPnl = remember(openTrades, liveTicks, lastMarks, currentMarket, now) {
        openTrades.sumOf { trade ->
            val selectedTick = com.aurum.edge.data.OpenPositionQuotePolicy.selectedFallback(trade, currentMarket, now)
            val polledTick = com.aurum.edge.data.OpenPositionQuotePolicy.monitored(trade, liveTicks[trade.symbol], now)
            val mark = OpenPositionDisplay.forTrade(trade, lastMarks)
            val liveP = (polledTick ?: selectedTick)?.price ?: mark?.price
            LiveTradeValuation.from(trade, liveP)?.netPnlUsd ?: 0.0
        }
    }

    SectionCard(
        title = "⚡ میز معاملات باز زنده (با پنل کشویی جزئیات)",
        subtitle = "نمای خلاصه در حالت پیش‌فرض؛ برای مشاهده فرمول R:R، سلامت روند و کارمزدها دکمه کشویی هر معامله را بزنید",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (openTrades.isNotEmpty()) {
                    Pill(
                        text = if (expandAllDrawers) "▲ بستن همه کشوها" else "▼ باز کردن همه",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { expandAllDrawers = !expandAllDrawers },
                    )
                    val sign = if (totalOpenPnl >= 0.0) "+" else "-"
                    Pill(
                        text = "$sign$${String.format(Locale.US, "%.2f", abs(totalOpenPnl))}",
                        color = if (totalOpenPnl >= 0.0) AurumColors.Green else AurumColors.Red,
                    )
                }
                Pill(
                    text = "${openTrades.size} پوزیشن فعال",
                    color = if (openTrades.isNotEmpty()) AurumColors.Gold else AurumColors.TextMuted,
                )
            }
        },
    ) {
        if (openTrades.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                    .border(1.dp, AurumColors.Line, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "در حال حاضر معامله بازی وجود ندارد",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = "ژورنال معاملات ↗",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onJournal() },
                    )
                }
                Text(
                    text = "رادار ۱۵ نماد در تایم‌فریم‌های 15m و 5m در حال اسکن ۱۶ شرط ایچیموکو است؛ به محض تکمیل شروط و تایید عدد مناسب R:R (حداقل ۱:۱.۵)، معامله خودکار باز می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                openTrades.forEach { trade ->
                    val normSym = com.aurum.edge.core.V1Universe.normalize(trade.symbol)
                    val matchedReport = dualReports[normSym]?.reportFor(trade.interval, trade.strategyId)
                    LiveTradeGaugeCard(
                        trade = trade,
                        matchedReport = matchedReport,
                        currentMarket = currentMarket,
                        liveTicks = liveTicks,
                        lastMark = OpenPositionDisplay.forTrade(trade, lastMarks),
                        now = now,
                        forceExpanded = expandAllDrawers,
                        onSelect = { onSelectTrade(trade.symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTradeGaugeCard(
    trade: PaperTrade,
    matchedReport: com.aurum.edge.core.StrategyEvaluationReport?,
    currentMarket: MarketState,
    liveTicks: Map<String, PriceTick>,
    lastMark: DisplayMark?,
    now: Long,
    forceExpanded: Boolean = false,
    onSelect: () -> Unit,
) {
    var localDrawerOpen by rememberSaveable(trade.id) { mutableStateOf(false) }
    val isDrawerOpen = forceExpanded || localDrawerOpen
    val isBuy = trade.action == SignalAction.BUY
    val selectedTick = com.aurum.edge.data.OpenPositionQuotePolicy.selectedFallback(trade, currentMarket, now)
    val polledTick = com.aurum.edge.data.OpenPositionQuotePolicy.monitored(trade, liveTicks[trade.symbol], now)
    val verifiedLivePrice = (polledTick ?: selectedTick)?.price
    val observedAt = (polledTick ?: selectedTick)?.at ?: lastMark?.tickAt
    val valuation = LiveTradeValuation.from(trade, verifiedLivePrice ?: lastMark?.price)

    val entry = trade.entry
    val stop = trade.stopLoss
    val target = trade.takeProfit
    val initStop = trade.initialStopLoss ?: trade.stopLoss
    val oneR = abs(entry - initStop).coerceAtLeast(1e-8)
    val rewardDist = abs(target - entry)
    val livePriceVal = valuation?.price ?: entry
    val currentProfitDist = if (isBuy) livePriceVal - entry else entry - livePriceVal
    val currentLiveR = currentProfitDist / oneR

    val stopInProfitDist = if (isBuy) stop - entry else entry - stop
    val lockedProfitR = if (stopInProfitDist > 1e-8) stopInProfitDist / oneR else 0.0

    val totalRange = abs(target - stop).coerceAtLeast(0.000001)
    val entryFraction = if (isBuy) {
        ((entry - stop) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    } else {
        ((stop - entry) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    }

    val statusColor = when {
        valuation == null -> AurumColors.TextMuted
        valuation.netPnlUsd > 0.0 -> AurumColors.Green
        valuation.netPnlUsd < 0.0 -> AurumColors.Red
        else -> AurumColors.Gold
    }

    val riskBudget = trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0
    val targetProfitUsd = riskBudget * trade.riskReward

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.85f))
            .border(1.2.dp, statusColor.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickable { onSelect() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── ۱. هدر تمیز کارت: نماد، جهت و تایم‌فریم در راست | سود دلاری و ضریب R لحظه‌ای در چپ ──
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
                    text = trade.displayTradeNumberBadgeFa,
                    color = AurumColors.Gold,
                )
                Text(
                    text = trade.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Pill(
                    text = if (isBuy) "🟢 خرید LONG" else "🔴 فروش SHORT",
                    color = if (isBuy) AurumColors.Green else AurumColors.Red,
                )
                Pill(
                    text = "⏱ ${trade.interval.label}",
                    color = AurumColors.Cyan,
                )
            }

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = valuation?.let {
                        val sign = if (it.netPnlUsd >= 0) "+" else "-"
                        "$sign$${String.format(Locale.US, "%.2f", abs(it.netPnlUsd))}"
                    } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%s%.2fR",
                        if (currentLiveR >= 0.0) "+" else "",
                        currentLiveR,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── ۲. نوار نام استراتژی و دکمه باز کردن چارت ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.Surface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🎯 ${trade.strategyTitleFa}",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "📊 چارت زنده ↗",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
        }

        // ── ۳. گرید ۴ ستونی قیمت‌ها (Entry / Live / SL / TP) بدون به‌هم‌ریختگی ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenTradeMetricBox(
                label = "نقطه ورود (Entry)",
                value = formatPrice(entry),
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            OpenTradeMetricBox(
                label = "قیمت زنده (Live)",
                value = valuation?.let { formatPrice(it.price) } ?: "...",
                valueColor = statusColor,
                modifier = Modifier.weight(1f),
            )
            OpenTradeMetricBox(
                label = if (lockedProfitR > 0.05) "🛡️ قفل سود (+${String.format(Locale.US, "%.1f", lockedProfitR)}R)" else "حد ضرر (SL)",
                value = formatPrice(stop),
                valueColor = if (lockedProfitR > 0.05) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            OpenTradeMetricBox(
                label = "حد سود (TP)",
                value = formatPrice(target),
                valueColor = AurumColors.Green,
                modifier = Modifier.weight(1f),
            )
        }

        // ── ۴. نوار گیج گرافیکی مسیر قیمت از SL تا TP ──
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.Surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "SL (-$${String.format(Locale.US, "%.1f", riskBudget)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lockedProfitR > 0.05) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Entry",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "TP (+$${String.format(Locale.US, "%.1f", targetProfitUsd)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AurumColors.SurfaceAlt),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entryFraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AurumColors.Red.copy(alpha = 0.45f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(1f - entryFraction)
                            .height(8.dp)
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AurumColors.Green.copy(alpha = 0.45f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entryFraction)
                            .height(14.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(14.dp)
                                .background(AurumColors.Gold),
                        )
                    }
                    valuation?.let { last ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(last.progress.coerceIn(0.03f, 0.97f))
                                .height(16.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                                    .border(2.dp, AurumColors.TextPrimary, CircleShape),
                            )
                        }
                    }
                }
            }
        }

        // محاسبه وضعیت سلامت روند برای نمایش خلاصه روی دکمه کشویی و داخل پنل کشویی
        val trendHealth = remember(trade, matchedReport, livePriceVal) {
            com.aurum.edge.core.IchimokuStrategyEngine.evaluateTrendHealthAndProfitExit(
                trade = trade,
                report = matchedReport,
                livePriceOverride = livePriceVal,
            )
        }
        val trendColor = when (trendHealth.state) {
            com.aurum.edge.core.OpenTradeTrendState.STRONG_CONTINUATION -> AurumColors.Green
            com.aurum.edge.core.OpenTradeTrendState.MOMENTUM_COOLING_LOCK_ACTIVE -> AurumColors.Gold
            com.aurum.edge.core.OpenTradeTrendState.TREND_LOST_EXIT_IN_PROFIT -> AurumColors.Red
        }

        // ── دکمه حالت کشویی (Sliding Drawer Toggle) برای باز و بسته کردن اطلاعات تکمیلی هر معامله ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.Surface)
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .clickable { localDrawerOpen = !localDrawerOpen }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = "R:R 1:${String.format(Locale.US, "%.2f", trade.effectiveOptimalRr)}",
                    color = AurumColors.Gold,
                )
                Pill(
                    text = "${trendHealth.state.badgeFa} (${trendHealth.continuationScorePercent}%)",
                    color = trendColor,
                )
            }
            Text(
                text = if (isDrawerOpen) "▲ بستن جزئیات کشویی" else "▼ جزئیات کشویی معامله",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
            )
        }

        // ── پنل کشویی (AnimatedVisibility) شامل فرمول R:R، سلامت روند و قوانین کارمزد مرجع دیتا ──
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── ۵. باکس اختصاصی «بررسی نحوه بدست آمدن R:R و عدد مناسب این معامله» ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Surface)
                        .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📐 بررسی فرمول R:R و عدد مناسب این معامله",
                            style = MaterialTheme.typography.labelMedium,
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
                        text = "فاصله ریسک ۱R: ${formatPrice(oneR)} (-$${String.format(Locale.US, "%.2f", riskBudget)}) · فاصله پاداش: ${formatPrice(rewardDist)} (+$${String.format(Locale.US, "%.2f", targetProfitUsd)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = trade.buildOptimalRrReasonFa(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trade.riskReward >= 3.0) AurumColors.Green else AurumColors.Cyan,
                    )
                    if (observedAt != null) {
                        Text(
                            text = "آخرین بروزرسانی تیک زنده: ${formatDateTime(observedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                }

                // ── ۵.۵ دسته‌بندی و کالبدشکافی «۳ کندل قبل از ورود» (C-2, C-1, C0) ──
                val pre3Context = remember(trade.id, trade.preEntry3Candles) {
                    if (trade.preEntry3Candles.isNotEmpty()) {
                        com.aurum.edge.core.PreEntry3CandleContext(
                            categoryCode = trade.preEntry3CandleCategoryCode,
                            categoryBadgeFa = trade.preEntry3CandleCategoryFa.ifBlank {
                                com.aurum.edge.core.PreEntry3CandleCategory.fromCode(trade.preEntry3CandleCategoryCode).badgeFa
                            },
                            categoryTitleFa = com.aurum.edge.core.PreEntry3CandleCategory.fromCode(trade.preEntry3CandleCategoryCode).titleFa,
                            summaryFa = trade.preEntry3CandleSummaryFa,
                            candles = trade.preEntry3Candles,
                        )
                    } else {
                        val cachedBars = com.aurum.edge.data.LiveQuoteFetcher.peekCachedCandles(trade.symbol, trade.interval)
                        com.aurum.edge.core.IchimokuStrategyEngine.buildPreEntry3CandleContext(
                            candles = cachedBars,
                            action = trade.action,
                            activeModel = com.aurum.edge.core.BitcoinStrategyModel.fromId(trade.strategyId),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Surface)
                        .border(1.dp, AurumColors.Cyan.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "🕯️ دسته‌بندی ۳ کندل قبل از ورود به معامله",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = pre3Context.categoryBadgeFa,
                            color = AurumColors.Gold,
                        )
                    }
                    Text(
                        text = pre3Context.categoryTitleFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (pre3Context.candles.isNotEmpty()) {
                        pre3Context.candles.forEach { slot ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AurumColors.SurfaceAlt)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${slot.slotTitleFa} · ${slot.roleInSetupFa}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (slot.isBullish) AurumColors.Green else AurumColors.Red,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = "O:${formatPrice(slot.open)} C:${formatPrice(slot.close)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AurumColors.TextSecondary,
                                        )
                                    }
                                }
                                Text(
                                    text = "${slot.candleAnatomyFa} | ${slot.priceVsTenkanFa} · ${slot.priceVsKijunFa} · ${slot.priceVsKumoFa}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.TextSecondary,
                                )
                            }
                        }
                    } else if (pre3Context.summaryFa.isNotBlank()) {
                        Text(
                            text = pre3Context.summaryFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }

                // ── ۶. بررسی زنده احتمال از دست رفتن روند در سود (Trend Loss In Profit Detector) ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Surface)
                        .border(1.dp, trendColor.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "🧭 بررسی ادامه روند و خروج هوشمند در سود",
                            style = MaterialTheme.typography.labelMedium,
                            color = trendColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = "${trendHealth.state.badgeFa} · ${trendHealth.continuationScorePercent}%",
                            color = trendColor,
                        )
                    }
                    Text(
                        text = if (trendHealth.shouldExitNowInProfit) {
                            trendHealth.exitTriggerReasonFa
                        } else if (trendHealth.activeWarningSignalsFa.isNotEmpty()) {
                            "هشدار فعلی: ${trendHealth.activeWarningSignalsFa.joinToString(" · ")}"
                        } else {
                            trendHealth.state.summaryFa
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                    )
                    Text(
                        text = trendHealth.profitExitRulesSummaryFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }

                // ── ۷. قوانین خرید، کمیسیون، اسپرد و کارمزد مرجع دیتا ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.Surface)
                        .border(1.dp, AurumColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "🏦 قوانین خرید، کمیسیون و کارمزد مرجع دیتا",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = "کل کارمزد: $${String.format(Locale.US, "%.3f", trade.totalRoundTripFeeUsd)}",
                            color = AurumColors.Gold,
                        )
                    }
                    Text(
                        text = trade.buildVenueRulesAndFeesSummaryFa(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenTradeMetricBox(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AurumColors.Surface)
            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
