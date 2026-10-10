package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AlertCheck
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.MarketTrendRead
import com.aurum.edge.core.PlaybookDecision
import com.aurum.edge.core.RiskTone
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.SymbolTrend
import com.aurum.edge.core.TrendAlignment
import com.aurum.edge.core.TrendContext
import com.aurum.edge.core.TrendDirection
import com.aurum.edge.core.TradeMethod
import com.aurum.edge.data.AiConnectionState
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.data.ScanRanking
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/**
 * صفحه معامله و سیگنال (Signal / Trading Tab):
 * ۱. نمایش برجسته برترین فرصت (#1 Best Opportunity) با تشریح کامل شروط تکنیکال ایچیموکو
 * ۲. سه کاندیدای برتر بازار با تفکیک دقیق نماد، دسته دارایی و امتیاز شروط
 * ۳. خلاصه سیگنال و دکمه‌های ورود دستی به معامله برای نماد فعلی
 * ۴. چک‌لیست شواهد و شروط تکنیکال نماد فعلی
 * ۵. وضعیت پایش واچ‌لیست فعال در پس‌زمینه
 */
@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState,
                 onChartSymbol: (String) -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
    val decisionLog by viewModel.decisionLog.collectAsStateWithLifecycle()
    val playbook by viewModel.playbook.collectAsStateWithLifecycle()
    val marketTrend by viewModel.marketTrend.collectAsStateWithLifecycle()
    val symbolTrend by viewModel.symbolTrend.collectAsStateWithLifecycle()
    val trendContext by viewModel.trendContext.collectAsStateWithLifecycle()
    val entryDiagnostics by viewModel.entryDiagnostics.collectAsStateWithLifecycle()
    val aiConnection by viewModel.aiConnection.collectAsStateWithLifecycle()
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
    var showAllDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var visibleDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(12) }
    var scanClock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            scanClock = System.currentTimeMillis() // expire historical recommendations without a new sweep
        }
    }
    val freshCandidates = ScanRanking.rank(scanState.statuses, settings.interval, scanClock)
    val signal = market.signal

    val openTrades = trades.filter { it.isOpen }
    val positionBlocker = com.aurum.edge.core.PaperPortfolioPolicy.blocker(
        trades, market.symbol, settings.accountBalance, 0.0, signal?.barTime)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {

        // ── ۱. بنر وضعیت ۴ دسته دارایی (رمزارز، فارکس، طلا/کالا، سهام) در ۴ مستطیل بالا ──
        com.aurum.edge.ui.components.AssetClass4SlotsBanner(
            openTrades = openTrades,
            livePrices = livePrices,
            onSelectSymbol = { symbol -> viewModel.selectChartSymbol(symbol) },
        )

        // ── ۲. بهترین فرصت معاملاتی (#1 Best Opportunity) با تشریح کامل شروط ──
        BestOpportunityDetailedCard(
            best = freshCandidates.firstOrNull(),
            sweeping = scanState.sweeping,
            onScan = { viewModel.scanPairs() },
            // A scanner candle is not a fresh verified entry; navigate for review only.
            onTrade = onChartSymbol,
        )

        // ── ۳. سه کاندیدای برتر بازار (Top 3 Candidates) با تفکیک دسته و شروط ──
        if (freshCandidates.isNotEmpty()) {
            TopCandidatesDetailedSection(
                candidates = freshCandidates.take(3),
                currentSymbol = market.symbol,
                onSelectSymbol = { viewModel.selectChartSymbol(it) },
            )
        }

        // Rankings for all four markets, not only the eight default watchlist rows.
        PerMarketRecommendations(scanState, settings.interval, scanClock, onChartSymbol)
        EvidenceTimingCard(market, positionBlocker)

        // ── ۴. خلاصه سیگنال نماد انتخابی و موتور واحد چهارلایه ─────────
        SignalSummaryCard(
            signal = signal,
            symbol = market.symbol,
            onOpenPaperTrade = { signal?.let { viewModel.openPaperTrade(it) } },
            entryBlocker = positionBlocker,
            allowManualPaperTrade = true,
        )

        // ── ۵. وضعیت معامله خودکار کاغذی ─────────────────────────────────
        SectionCard("معاملهٔ خودکار کاغذی (تخصیص متوازن ۴ بازار)") {
            Text(
                if (settings.autoPaperTrading) "$autoStatus · نماد منتخب و کاتالوگ به‌صورت خودکار بررسی می‌شوند؛ " +
                    "ورود کاتالوگ فقط با تیک تازهٔ مستقل و پایش خروج (فعلاً فارکس و XAU/XAG). سقف ریسک کل پورتفو مشترک است." else "خاموش (از بخش تنظیمات یا صفحه اصلی قابل فعال‌سازی است)",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.autoPaperTrading) AurumColors.TextSecondary else AurumColors.Gold,
            )
        }

        // ── ۵٫۱ «کدام روش برای کدام بازار» — روتر متد بازار ──────────────────
        MarketPlaybookCard(playbook, market.symbol)

        // ── ۵٫۲ «روند کلی بازار» — چطور به دست می‌آید و چطور به معامله اضافه می‌شود ──
        MarketTrendCard(marketTrend, symbolTrend, trendContext, market.symbol)

        // ── ۵٫۳ «چرا الان معامله/هشدار نداریم؟» — پیش‌نیازهای صادقانه ─────────
        WhyNoTradeCard(entryDiagnostics, aiConnection)

        // ── ۶. چک‌لیست کامل شواهد و شروط تکنیکال نماد انتخابی ──────────────
        signal?.let { s ->
            SectionCard(
                title = "چهار لایهٔ امتیاز فنی · ${market.symbol}",
                subtitle = "EMA/ایچیموکو · RSI/MACD · حمایت/مقاومت · هفت بازهٔ M1 تا D1",
                trailing = {
                    val count = s.confluence.count { it.ok }
                    Pill("${s.confidence.toInt()}/۱۰۰ · $count لایهٔ کامل", if (s.isActionable) AurumColors.Green else AurumColors.Gold)
                },
            ) {
                if (s.confluence.isEmpty()) {
                    Text("در حال پردازش شروط تکنیکال...", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.confluence.forEach { ConfluenceRow(it) }
                    }
                }
            }
        }

        // ── ۷. وضعیت پایش واچ‌لیست فعال در پس‌زمینه (رادار خودکار) ───────
        PairRadarSummaryCard(
            scan = scanState,
            onScan = { viewModel.scanPairs() },
            now = System.currentTimeMillis(),
        )

        // Keep the audit trail after every signal and radar card, at the bottom of Trading.
        SectionCard("Decision Log · دلیل هر بررسی", "روی همین دستگاه؛ بدون کلید API یا سفارش واقعی") {
            if (decisionLog.isEmpty()) Text("هنوز بررسی ثبت نشده است")
            val latestBySymbol = decisionLog.distinctBy { it.symbol }
            if (decisionLog.size > latestBySymbol.size) OutlinedButton(onClick = { showAllDecisions = !showAllDecisions }) {
                Text(if (showAllDecisions) "آخرین دلیل هر نماد" else "تاریخچهٔ ${decisionLog.size} بررسی اخیر")
            }
            val records = if (showAllDecisions) decisionLog else latestBySymbol
            records.take(visibleDecisions).forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${record.symbol} · ${record.interval} · ${formatTime(record.checkedAt)} · " +
                    "${record.action} · ${record.score?.toInt() ?: "—"}/۱۰۰",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onChartSymbol(record.symbol) })
                Text(record.reason, style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary)
                record.method?.let { method ->
                    Text("متد: $method · ${record.methodReason.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                record.layers.forEach { layer ->
                    Text("${layer.name}: ${layer.points ?: "—"}/۲۵ · ${layer.status} · ${layer.detail}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                }
            }
            if (records.size > visibleDecisions) OutlinedButton(onClick = { visibleDecisions += 12 }) {
                Text("نمایش ۱۲ بررسیٔ دیگر · ${records.size - visibleDecisions} باقی‌مانده")
            }
        }

    }
}

/** Live, read-only timing evidence; no fabricated buy window or forex volume. */
@Composable
private fun EvidenceTimingCard(market: MarketState, entryBlocker: String?) {
    val now = System.currentTimeMillis()
    val live = market.feed.mode in setOf(com.aurum.edge.core.FeedMode.LIVE,
        com.aurum.edge.core.FeedMode.POLLING) && !market.showingCachedData &&
        com.aurum.edge.core.FeedLiveness.hasRecentReceipt(market.feed, now)
    val bar = market.candles.lastOrNull { it.closed }
    val freshBar = bar != null && bar.time == market.signal?.barTime &&
        now - (bar.time + market.interval.millis) in 0L..(market.interval.millis + 90_000L)
    val buy = live && freshBar && entryBlocker == null && market.signal?.isActionable == true &&
        market.signal?.action == SignalAction.BUY
    val hours = if (live) com.aurum.edge.core.VolumeSessionStats.cryptoHours(
        market.symbol, market.interval, market.candles, now) else emptyList()
    SectionCard("زمان خرید و ساعات پرحجم · ${market.symbol}",
        "بر پایهٔ دادهٔ همین نماد؛ توصیف آماری، نه پیش‌بینی سود") {
        Text(if (buy) "اکنون سیگنال خرید چهارلایهٔ تازه و قابل بررسی وجود دارد · " +
            "کندل ${market.signal?.barTime?.let { formatTime(it) } ?: "—"}"
             else "اکنون زمان خرید تأییدشده‌ای نداریم؛ " +
                 (entryBlocker ?: if (live) "سیگنال خرید چهارلایهٔ معتبر وجود ندارد" else "فید تازه در دسترس نیست"),
            style = MaterialTheme.typography.bodySmall,
            color = if (buy) AurumColors.Green else AurumColors.Gold)
        Text("منبع فعلی: ${market.feed.provider} · آخرین دریافت ${market.feed.lastSuccessAt?.let { formatTime(it) } ?: "—"} · " +
            "بازه ${market.interval.label} · قیمت/حجم فقط از همان جفتِ منبع، بدون تبدیل دلار به تتر. " +
            "دسترسی از اینترنت ایران فقط با آزمون روی دستگاه و شبکهٔ همان کاربر قابل تأیید است.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 5.dp))
        if (hours.isEmpty()) {
            Text("ساعت اوج حجم قابل محاسبه نیست: فقط رمزارز با فید تازه، کندل H1 بستهٔ واقعی، " +
                "حجم مثبت و حداقل ۵ نمونه در تک‌تک ۲۴ ساعت UTC طی ۱۴ روز پذیرفته می‌شود. " +
                "در فارکس/نفت حجم قراردادِ هم‌هویت تأیید نشده؛ عددی حدس نمی‌زنیم.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("۳ ساعت با میانگین بیشترین حجم ثبت‌شده در ۱۴ روز گذشته (UTC، نه پیشنهاد خرید):",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            hours.forEachIndexed { index, hour ->
                Text("${index + 1}. ${"%02d".format(java.util.Locale.US, hour.utcHour)}:00–" +
                    "${"%02d".format(java.util.Locale.US, (hour.utcHour + 1) % 24)}:00 UTC · " +
                    "میانگین ${"%.2f".format(java.util.Locale.US, hour.meanVolume)} واحد پایه · ${hour.samples} کندل",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Cyan)
            }
        }
    }
}

/**
 * «کدام روش برای کدام بازار»: the per-market method router, measured from real closed candles and
 * real session clocks. Read-only — it says which method is legal now, and why not when it isn't.
 */
@Composable
private fun MarketPlaybookCard(decision: PlaybookDecision?, symbol: String) {
    val method = decision?.method
    val color = when (method) {
        TradeMethod.TREND_PULLBACK -> AurumColors.Green
        TradeMethod.BREAKOUT_MOMENTUM -> AurumColors.Cyan
        TradeMethod.OPENING_DRIVE -> AurumColors.Gold
        TradeMethod.RANGE_MEAN_REVERSION -> AurumColors.Gold
        TradeMethod.STAND_ASIDE -> AurumColors.Red
        null -> AurumColors.TextMuted
    }
    SectionCard(
        title = "متد مناسب این بازار · $symbol",
        subtitle = "خوانش زمینه‌ای بازار؛ مجوز ورود فقط از موتور چهارلایه و قفل‌های ریسک/بازار صادر می‌شود",
        trailing = { Pill(method?.label ?: "در حال ارزیابی", color) },
    ) {
        if (decision == null) {
            Text("دادهٔ کافی برای تعیین متد نیست؛ منتظر کندل‌های بستهٔ واقعی هستیم.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("خانوادهٔ بازار: ${decision.family.label} · سشن واقعی: ${decision.session.label} · رژیم اندازه‌گیری‌شده: ${decision.regime.label}" +
                    if (decision.thinLiquidity) " · نقدشوندگی نازک (آخر هفتهٔ کریپتو)" else "",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("اندازه‌گیری‌های واقعی: ER=${fmt2(decision.efficiencyRatio)} · ATR/میانه=${fmt2(decision.atrRatio)} · اسپرد/ATR=" +
                    (decision.spreadAtrRatio?.let { fmt2(it * 100.0) + "٪" } ?: "—"),
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text("خوانش پژوهشی رژیم بازار (نه قفل ورود): کف R:R اولیهٔ واقعی ۱٫۵ است؛ خروج ثابت یا سقف نهایی برای معاملات V1 وجود ندارد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text("کف سود واقعی: ${fmt2(decision.minRewardBps)}bps و حداقل ${fmt2(decision.costRewardMultiple)} برابر هزینهٔ رفت‌وبرگشت · مرجع هزینه: ${decision.venue}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text(decision.reasonFa, style = MaterialTheme.typography.labelSmall, color = color)
            decision.blockers.forEach { blocker ->
                Text("زمینهٔ بازار: $blocker", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
            }
            if (decision.allowed) {
                Text("این خوانش صرفاً زمینهٔ بازار است؛ امتیاز چهارلایه، تازگی داده و قفل‌های مطلق مرجع ورود هستند.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

/**
 * «روند کلی بازار» — how the market-wide trend is measured and exactly how it is added to a trade.
 * Everything on this card is a measurement of real closed candles: breadth across the scanned
 * universe, the dollar direction from the six majors, and the risk tone from risk assets versus
 * gold. A missing read is shown as missing — never replaced with a guess.
 */
@Composable
private fun MarketTrendCard(
    read: MarketTrendRead?,
    symbolTrend: SymbolTrend?,
    context: TrendContext?,
    symbol: String,
) {
    val stale = read != null && System.currentTimeMillis() - read.computedAt !in 0L..10 * 60_000L
    val currentRead = read?.takeUnless { stale }
    val biasColor = when (currentRead?.bias) {
        TrendDirection.UP -> AurumColors.Green
        TrendDirection.DOWN -> AurumColors.Red
        TrendDirection.SIDEWAYS -> AurumColors.Gold
        else -> AurumColors.TextMuted
    }
    SectionCard(
        title = "روند کلی بازار · $symbol",
        subtitle = "سه لایهٔ اندازه‌گیری از کندل‌های بستهٔ واقعی: روندِ تایم‌فریم مرجعِ خودِ نماد، " +
            "عرض نمادهای واقعاً سنجیده‌شده (هر نماد یک رأی)، و جهت دلار + جوّ ریسک‌پذیری",
        trailing = {
            Pill(
                if (currentRead == null) "هنوز اندازه گرفته نشد" else "${currentRead.bias.label} · ${currentRead.strength}٪",
                biasColor,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("نمونهٔ هر بازار متغیر است؛ این عدد ارزش‌وزن/حجم‌وزن کل بازار نیست. " +
                "نبود دادهٔ هم‌نماد از ایران به معنی روند خنثی نیست.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            Text("چطور به دست می‌آید؟ (۱) کندل‌های تایم‌فریم پایه به تایم‌فریم مرجع تجمیع می‌شوند " +
                    "(M5→H1، M15→H4، H1→H4، H4→D1) و شش رأی مستقل شمرده می‌شود: قیمت نسبت به EMA50، " +
                    "EMA20 نسبت به EMA50، شیب EMA50 بر حسب ATR، Efficiency Ratio، ساختار سقف/کف " +
                    "(HH/HL یا LH/LL) و جابه‌جایی خالص بر حسب ATR. مجموع ≥+۲ صعودی و ≤−۲ نزولی است. " +
                    "(۲) همان اندازه‌گیری برای همهٔ نمادهای پویش‌شده تکرار می‌شود و «عرض بازار» را می‌سازد. " +
                    "(۳) جهت دلار از شش جفت اصلی و جوّ ریسک از رمزارز/سهام در برابر طلا خوانده می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("جهت بازار", currentRead?.bias?.label ?: "—", biasColor, Modifier.weight(1f))
                StatTile("قدرت", currentRead?.let { "${it.strength}٪" } ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("عرض بازار", currentRead?.let { "${it.breadthUp}↑/${it.breadthDown}↓" } ?: "—",
                    AurumColors.Cyan, Modifier.weight(1f))
                StatTile("دلار", currentRead?.dollarBias?.label ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("جوّ بازار", currentRead?.riskTone?.label ?: "—",
                    when (currentRead?.riskTone) {
                        RiskTone.RISK_ON -> AurumColors.Green
                        RiskTone.RISK_OFF -> AurumColors.Red
                        else -> AurumColors.Gold
                    }, Modifier.weight(1f))
                StatTile("نمادِ سنجیده", currentRead?.let { "${it.measured}" } ?: "—",
                    AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("روندِ خودِ نماد", symbolTrend?.direction?.label ?: "—",
                    when (symbolTrend?.direction) {
                        TrendDirection.UP -> AurumColors.Green
                        TrendDirection.DOWN -> AurumColors.Red
                        TrendDirection.SIDEWAYS -> AurumColors.Gold
                        else -> AurumColors.TextMuted
                    }, Modifier.weight(1f))
                StatTile("قدرتِ نماد", symbolTrend?.let { "${it.strength}٪" } ?: "—",
                    AurumColors.TextPrimary, Modifier.weight(1f))
            }

            if (stale) Text("خوانش قبلی مربوط به ${read?.computedAt?.let { formatTime(it) } ?: "—"} است و " +
                "بیش از ۱۰ دقیقه عمر دارد؛ تا تکمیل پویش جدید، جهت کلی به‌عنوان دادهٔ زنده نمایش/اعمال نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            if (currentRead == null) {
                Text("هنوز هیچ پویشی چیزی اندازه نگرفته است (یا کندل بستهٔ کافی نرسیده). تا وقتی " +
                        "روند کلی بازار مجهول است، معامله‌ها فقط با روندِ خودِ نماد و بقیهٔ گیت‌ها " +
                        "سنجیده می‌شوند و هیچ جهتِ کلیِ حدسی ساخته نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            } else {
                currentRead.drivers.forEach { driver ->
                    Text("• $driver", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                }
                currentRead.families.forEach { family ->
                    Text(family.summaryFa + " · قدرت ${family.strength}٪",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
            }

            if (symbolTrend != null) {
                Text("روند $symbol: ${symbolTrend.shortFa} · " +
                        (symbolTrend.higherLabel?.let { "مرجع $it" } ?: "بدون تایم‌فریم بالاتر") +
                        " · ER=${symbolTrend.efficiencyRatio?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}" +
                        if (symbolTrend.conflict) " · پایه و مرجع هم‌جهت نیستند" else "",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                Text(symbolTrend.detailFa, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }

            if (context != null) {
                val aligned = context.alignment == TrendAlignment.WITH
                Text("چطور به معامله اضافه می‌شود: ${context.alignment.label}" +
                        if (context.gate.allowed) "" else " → در این خوانش زمینه‌ای، خلاف روند",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !context.gate.allowed -> AurumColors.Gold
                        aligned -> AurumColors.Green
                        context.alignment == TrendAlignment.AGAINST -> AurumColors.Red
                        else -> AurumColors.Gold
                    })
                Text(context.noteFa, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("این خوانشِ روند، زمینهٔ بازار و رتبه‌بندی است؛ قفل ورود یا آستانهٔ " +
                        "مخفی اضافه نمی‌کند. مرجع ورود موتور چهارلایهٔ V1، وتوی معتبر AI و " +
                        "قواعد ریسک/تازگی است؛ عکس خوانش در ژورنال ثبت می‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            } else {
                Text("برای دیدن جایگاهِ ورود نسبت به روند، باید سیگنالِ همین نماد و یک پویشِ " +
                        "انجام‌شده در دسترس باشد.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

/**
 * «چرا الان معامله/هشدار نداریم؟» — the nine honest prerequisites plus the AI connection truth.
 * Green checks are prerequisites, never a forecast; a red row is the actual reason nothing opened.
 */
@Composable
private fun WhyNoTradeCard(checks: List<AlertCheck>, ai: AiConnectionState) {
    val failed = checks.count { !it.ready }
    SectionCard(
        title = "چرا الان معامله/هشدار نداریم؟",
        subtitle = "پیش‌نیازهای زندهٔ اپ — هر ردیف قرمز یعنی همان دلیل، بدون حدس",
        trailing = {
            Pill(if (checks.isEmpty()) "در حال بررسی" else if (failed == 0) "همهٔ پیش‌نیازها سبز" else "$failed مورد قرمز",
                if (checks.isEmpty()) AurumColors.TextMuted else if (failed == 0) AurumColors.Green else AurumColors.Red)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (checks.isEmpty()) {
                Text("هنوز دادهٔ کافی برای ارزیابی پیش‌نیازها نیست.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
            }
            checks.forEach { check ->
                Text("${if (check.ready) "✅" else "⛔"} ${check.kind.label}: ${check.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (check.ready) AurumColors.TextSecondary else AurumColors.Red)
            }
            Text(
                when {
                    !ai.configured -> "🤖 اتصال AI: کلید/مدل تنظیم نشده — معاملات کاغذی بدون AI و فقط با شرط‌های فنی انجام می‌شوند."
                    ai.reachable == true -> "🤖 اتصال AI: برقرار است — ارزنده‌بودن ورود و بازبینی پوزیشن باز از مدل پرسیده می‌شود."
                    ai.reachable == false -> "🤖 اتصال AI: قطع است (${ai.detail.ifBlank { "اتصال برقرار نشد" }}) — معاملات بدون AI ادامه دارند و این وضعیت اعلان می‌شود."
                    else -> "🤖 اتصال AI: هنوز در این اجرا بررسی نشده است."
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (ai.reachable) {
                    true -> AurumColors.Green
                    false -> if (ai.configured) AurumColors.Red else AurumColors.Gold
                    null -> AurumColors.TextMuted
                },
            )
            ai.checkedAt?.let { at ->
                Text("آخرین بررسی اتصال AI: " + relativeTime(at, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            if (failed == 0 && checks.isNotEmpty()) {
                Text("اگر همه‌چیز سبز است و معامله‌ای باز نشد، دلیل لحظه‌ای را در بخش «معاملهٔ خودکار کاغذی» و گزارش تصمیم ببینید.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

private fun fmt2(value: Double?): String =
    if (value == null) "—" else String.format(java.util.Locale.US, "%.2f", value)

/** Four buckets are ALWAYS shown. No eligible candle => show coverage and an actual reason,
 * not an invented "best". Observations outside the watchlist are never entry permissions.
 */
@Composable
private fun PerMarketRecommendations(scan: PairScanState, interval: com.aurum.edge.core.Interval,
                                     now: Long, onSelect: (String) -> Unit) {
    val picks = ScanRanking.byMarket(scan.statuses, interval, now)
    AssetClass.entries.forEach { category ->
        val rows = scan.statuses.filter { it.assetClass == category }
        val checked = rows.count { it.state != "pending" }
        val ranked = picks[category].orEmpty()
        SectionCard("برترین‌های ${category.label}",
            "همین دور اسکن · $checked/${rows.size} بررسی‌شده · ${rows.count { it.state == "error" }} فید ناموفق · " +
                "${rows.count { it.state == "partial" }} تاریخچهٔ ناقص",
            trailing = { Pill(if (scan.sweeping) "در حال اسکن" else "آخرین دور", AurumColors.Cyan) }) {
            Box(Modifier.fillMaxWidth().height(6.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))) {
                Box(Modifier.fillMaxWidth(if (rows.isEmpty()) 0f else checked.toFloat() / rows.size)
                    .height(6.dp).background(AurumColors.Cyan, RoundedCornerShape(8.dp)))
            }
            Text("رتبه فقط از کندل‌های بستهٔ تازه است؛ «مشاهده» نه مجوز ورود است نه قیمت لحظه‌ای. " +
                "اگر دادهٔ هفت بازه یا فید معتبر نباشد، رتبه ساخته نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(vertical = 6.dp))
            if (ranked.isEmpty()) {
                val reason = rows.firstOrNull { it.state == "error" || it.state == "partial" || it.state == "blocked" }
                Text(if (checked == 0) "هنوز بررسی نشده؛ اسکن را اجرا کنید."
                     else "فعلاً گزینهٔ معتبر نیست. ${reason?.symbol.orEmpty()}: ${reason?.detail ?: "شرایط کافی نیست"}",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            } else ranked.forEachIndexed { index, row ->
                val approved = row.state == "candidate"
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(14.dp))
                    .clickable { onSelect(row.symbol) }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#${index + 1}  ${row.symbol}", style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Pill(if (approved) "کاندیدای واچ‌لیست" else "فقط مشاهده",
                            if (approved) AurumColors.Green else AurumColors.Gold)
                    }
                    if (category == AssetClass.CRYPTO) Text(
                        com.aurum.edge.data.CryptoFamilies.label(row.symbol),
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                    Text("${row.action?.name ?: "—"} · امتیاز ${row.confidence?.toInt() ?: 0}/۱۰۰ · " +
                        "متد ${row.methodLabel ?: "نامشخص"} · ${row.trendLabel ?: "روند نامشخص"}",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                    Box(Modifier.fillMaxWidth().height(5.dp)
                        .background(AurumColors.Bg, RoundedCornerShape(5.dp))) {
                        Box(Modifier.fillMaxWidth(((row.confidence ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f))
                            .height(5.dp).background(if (approved) AurumColors.Green else AurumColors.Gold,
                                RoundedCornerShape(5.dp)))
                    }
                    Text("بررسی ${row.lastScanAt?.let { formatTime(it) } ?: "—"} · " +
                        "قیمت کندل ${formatPrice(row.price)} · لمس کنید برای بررسی نماد",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                    if (!approved) Text(row.detail, style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Gold)
                }
            }
            if (ranked.size in 1..3) Text("گزینهٔ بیشتری با شواهد تازه پیدا نشد؛ رتبهٔ فرضی نمایش داده نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
    }
}

/**
 * کارت بهترین فرصت معاملاتی با تشریح کامل شروط تکنیکال و تفکیک نماد/دسته دارایی.
 */
@Composable
private fun BestOpportunityDetailedCard(
    best: PairScanStatus?,
    sweeping: Boolean,
    onScan: () -> Unit,
    onTrade: (String) -> Unit,
) {
    val assetClass = best?.let { AssetClass.of(it.symbol) } ?: AssetClass.FOREX
    val isBuy = best?.action == SignalAction.BUY
    val actionColor = when (best?.action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.Gold
    }

    SectionCard(
        title = "✨ برترین کاندیدای مجازِ واچ‌لیست",
        subtitle = if (best != null) "رتبهٔ برتر میان کاندیداهای تازهٔ مجاز؛ ورود فقط با قیمت تازهٔ مستقل" else "اسکن نمادهای کاتالوگ؛ نبود داده به معنی فرصت نیست",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (best != null) {
                Pill(
                    text = if (isBuy) "خرید LONG" else "فروش SHORT",
                    color = actionColor,
                )
            }
        },
    ) {
        if (best != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header: Symbol + Category badge + Score + RR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = best.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                        )
                        Pill(assetClass.label, AurumColors.SurfaceAlt)
                        Pill("امتیاز ${best.confidence?.toInt() ?: 0}/۱۰۰", actionColor)
                    }
                    best.riskReward?.let { rr ->
                        Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                    }
                }

                // «متناسب با همان استراتژی»: which per-market method approved this symbol, so a
                // candidate is never shown without the strategy that makes it tradable.
                best.methodLabel?.let { method ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("متد این بازار (استراتژی مخصوص همان بازار)",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Pill(method, if (best.playbookAllowed == true) AurumColors.Cyan else AurumColors.Gold)
                    }
                }

                // «روند کلی بازار» برای همان کاندیدا: هم‌جهت بودن، یک شرطِ دیده‌شدنی است نه یک ادعا.
                best.trendLabel?.let { trend ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("روند اندازه‌گیری‌شدهٔ این نماد (تایم‌فریم مرجع)",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Pill(trend, if (best.trendAligned == true) AurumColors.Green else AurumColors.Gold)
                    }
                }
                best.trendNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }

                // Price levels: Entry, SL, TP
                if (best.entry != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("نقطه ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            Text(formatPrice(best.entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        }
                        Column {
                            Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red.copy(alpha = 0.8f))
                            Text(formatPrice(best.stopLoss), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                        }
                        Column {
                            Text("تارگت سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green.copy(alpha = 0.8f))
                            Text(formatPrice(best.takeProfit), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                        }
                    }
                }

                Text(
                    text = best.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )

                // Detailed Technical Conditions Checklist for #1 Best Opportunity
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AurumColors.Surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .border(1.dp, AurumColors.Gold.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "📋 شروط و شواهد تکنیکال این فرصت (${best.symbol}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AurumColors.Gold,
                    )

                    if (best.conditions.isNotEmpty()) {
                        best.conditions.forEach { item ->
                            ConfluenceRow(item)
                        }
                    } else {
                        Text("شواهد تفصیلی از فید ثبت نشده‌اند؛ تأیید تکنیکال فرض نمی‌شود.",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                    }
                }

                // Action button: Trade this opportunity
                Button(
                    onClick = { onTrade(best.symbol) },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        "نمایش چارت ${best.symbol} · بررسی پیش از ورود",
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "برای بررسی همهٔ نمادهای کاتالوگ با دادهٔ واقعی، اسکن را بزنید؛ نبود فید به‌صورت خطا نمایش داده می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onScan,
                    enabled = !sweeping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (sweeping) "در حال اسکن..." else "اسکن کاتالوگ")
                }
            }
        }
    }
}

/**
 * بخش نمایش ۳ کاندیدای برتر بازار با جزئیات کامل شروط و نماد
 */
@Composable
private fun TopCandidatesDetailedSection(
    candidates: List<PairScanStatus>,
    currentSymbol: String,
    onSelectSymbol: (String) -> Unit,
) {
    SectionCard(
        title = "🏆 ۳ کاندیدای برتر بازار (Top 3 Candidates)",
        subtitle = "فرصت‌های ممتاز استخراج‌شده از میان ۵۰+ نماد با تفکیک دقیق دسته دارایی و امتیاز شروط",
        trailing = {
            Pill("${candidates.size} کاندیدا", AurumColors.Gold)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            candidates.forEachIndexed { index, candidate ->
                CandidateDetailedItemCard(
                    rank = index + 1,
                    candidate = candidate,
                    isCurrent = currentSymbol == candidate.symbol,
                    onSelect = { onSelectSymbol(candidate.symbol) },
                )
            }
        }
    }
}

@Composable
private fun CandidateDetailedItemCard(
    rank: Int,
    candidate: PairScanStatus,
    isCurrent: Boolean,
    onSelect: () -> Unit,
) {
    val assetClass = AssetClass.of(candidate.symbol)
    val isBuy = candidate.action == SignalAction.BUY
    val actionColor = if (isBuy) AurumColors.Green else AurumColors.Red

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, (if (rank == 1) AurumColors.Gold else AurumColors.SurfaceAlt).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#$rank ${candidate.symbol}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Pill(assetClass.label, AurumColors.Surface)
                Pill(
                    text = if (isBuy) "خرید LONG" else "فروش SHORT",
                    color = actionColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("امتیاز ${candidate.confidence?.toInt() ?: 0}/۱۰۰", actionColor)
                candidate.riskReward?.let { rr ->
                    Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                }
            }
        }

        if (candidate.entry != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ورود: ${formatPrice(candidate.entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("SL: ${formatPrice(candidate.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                Text("TP: ${formatPrice(candidate.takeProfit)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
        }

        // Summary of passing conditions
        Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (candidate.conditions.isNotEmpty()) {
                candidate.conditions.take(3).forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                        Pill(when (item.status) {
                            com.aurum.edge.core.ConfluenceStatus.CONFIRMED -> "✓ کامل"
                            com.aurum.edge.core.ConfluenceStatus.PARTIAL -> "◐ ${item.scorePercent}/۲۵"
                            com.aurum.edge.core.ConfluenceStatus.UNKNOWN -> "؟ نامعلوم"
                            com.aurum.edge.core.ConfluenceStatus.CONFLICT -> "✗ رد"
                        }, if (item.ok) AurumColors.Green else AurumColors.Gold)
                    }
                }
            } else {
                Text("شواهد تکنیکال این کاندیدا در دسترس نیست؛ تأیید فرض نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "دسته دارایی: ${assetClass.label} · سهم در پورتفو: ۱/۱",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            if (isCurrent) {
                Pill("نماد انتخابی", AurumColors.Green)
            } else {
                Text(
                    text = "انتخاب نماد ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier
                        .clickable { onSelect() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** All-pairs background radar summary */
@Composable
internal fun PairRadarSummaryCard(scan: PairScanState, onScan: () -> Unit, now: Long) {
    LaunchedEffect(scan.lastSweepAt) { if (scan.lastSweepAt == null) onScan() }
    SectionCard(
        title = "پایش پیوستهٔ ۵۰+ نماد در پس‌زمینه",
        subtitle = "دیتاگیری و کشف خودکار فرصت‌ها از بازار کریپتو، طلا، نفت، فارکس و سهام",
        trailing = {
            Pill(
                when {
                    scan.sweeping -> "بررسی ${scan.checkedCount} از ${scan.totalCount} نماد…"
                    scan.lastSweepAt != null -> "آخرین اسکن " + relativeTime(scan.lastSweepAt, now)
                    else -> "اسکن نشده"
                },
                if (scan.sweeping) AurumColors.Cyan else AurumColors.TextMuted,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "برای همهٔ نمادهای کاتالوگ و واچ‌لیست به‌ترتیب تلاش می‌شود؛ فقط کندل واقعیِ هم‌نماد امتیاز می‌گیرد. " +
                    "نبود فید/بسته‌بودن بازار هم با دلیل ثبت می‌شود. فقط واچ‌لیست فعال مجاز به فرصت معاملاتی است. " +
                    "اسکن پیوسته در پس‌زمینه نیازمند سرویس فعال در تنظیمات است؛ هر دور پس از اتمام و فاصلهٔ حداقلی شروع می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onScan,
                enabled = !scan.sweeping,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(if (scan.sweeping) "اسکن..." else "اسکن مجدد")
            }
        }
        Text("نتیجهٔ دور جاری: ${scan.checkedCount}/${scan.totalCount} نماد · " +
            "آخرین دور کامل: ${scan.lastSweepAt?.let { formatTime(it) } ?: "هنوز کامل نشده"} · " +
            "تلاش بعدی پس از اتمام دور، حداقل ۳۰ ثانیه فاصله و در چرخهٔ حدوداً یک‌دقیقه‌ای برنامه. " +
            "زمان واقعی به شبکه و محدودیت منبع بستگی دارد؛ دلیل هر بررسی در انتهای صفحه است.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        scan.lastError?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
