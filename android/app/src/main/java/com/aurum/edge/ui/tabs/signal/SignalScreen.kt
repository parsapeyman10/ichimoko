package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه معامله و سیگنال (Signal / Trading Tab):
 * ۱. نمایش برجسته برترین فرصت (#1 Best Opportunity) با تشریح کامل شروط تکنیکال ایچیموکو
 * ۲. سه کاندیدای برتر بازار با تفکیک دقیق نماد، دسته دارایی و امتیاز شروط
 * ۳. خلاصه سیگنال و دکمه‌های ورود دستی به معامله برای نماد فعلی
 * ۴. چک‌لیست شواهد و شروط تکنیکال نماد فعلی
 * ۵. وضعیت پایش واچ‌لیست فعال در پس‌زمینه
 */
@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState, onOpenNews: () -> Unit) {
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
            best = scanState.bestPick,
            sweeping = scanState.sweeping,
            onScan = { viewModel.scanPairs() },
            onTrade = { symbol ->
                viewModel.selectChartSymbol(symbol)
                viewModel.selectAndTradeBestPick()
            },
        )

        // ── ۳. سه کاندیدای برتر بازار (Top 3 Candidates) با تفکیک دسته و شروط ──
        if (scanState.topThree.isNotEmpty()) {
            TopCandidatesDetailedSection(
                candidates = scanState.topThree,
                currentSymbol = market.symbol,
                onSelectSymbol = { viewModel.selectChartSymbol(it) },
            )
        }

        // ── ۴. خلاصه سیگنال نماد انتخابی و موتور تلفیقی ایچیموکو ─────────
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
                if (settings.autoPaperTrading) autoStatus else "خاموش (از بخش تنظیمات یا صفحه اصلی قابل فعال‌سازی است)",
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
            onSelectSymbol = { viewModel.selectChartSymbol(it) },
            now = System.currentTimeMillis(),
        )

        // Keep the audit trail after every signal and radar card, at the bottom of Trading.
        SectionCard("Decision Log · دلیل هر بررسی", "روی همین دستگاه؛ بدون کلید API یا سفارش واقعی") {
            if (decisionLog.isEmpty()) Text("هنوز بررسی ثبت نشده است")
            val latestBySymbol = decisionLog.distinctBy { it.symbol }
            if (decisionLog.size > latestBySymbol.size) OutlinedButton(onClick = { showAllDecisions = !showAllDecisions }) {
                Text(if (showAllDecisions) "آخرین دلیل هر نماد" else "تاریخچهٔ ${decisionLog.size} بررسی اخیر")
            }
            (if (showAllDecisions) decisionLog else latestBySymbol).forEach { record ->
                Text("${record.symbol} · ${record.interval} · ${formatTime(record.checkedAt)} · " +
                    "${record.action} · ${record.score?.toInt() ?: "—"}/۱۰۰",
                    style = MaterialTheme.typography.bodySmall)
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
        subtitle = "هر بازار یک روش دارد: طلا/فارکس در لندن و نیویورک روند، فارکس در آسیا رنج، رمزارز شکست مومنتوم، سهام درایو بازگشایی",
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
                Text("⛔ $blocker", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
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
    val biasColor = when (read?.bias) {
        TrendDirection.UP -> AurumColors.Green
        TrendDirection.DOWN -> AurumColors.Red
        TrendDirection.SIDEWAYS -> AurumColors.Gold
        else -> AurumColors.TextMuted
    }
    SectionCard(
        title = "روند کلی بازار · $symbol",
        subtitle = "سه لایهٔ اندازه‌گیری از کندل‌های بستهٔ واقعی: روندِ تایم‌فریم مرجعِ خودِ نماد، " +
            "عرض بازار در ۵۰+ نماد، و جهت دلار + جوّ ریسک‌پذیری",
        trailing = {
            Pill(
                if (read == null) "هنوز اندازه گرفته نشد" else "${read.bias.label} · ${read.strength}٪",
                biasColor,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("چطور به دست می‌آید؟ (۱) کندل‌های تایم‌فریم پایه به تایم‌فریم مرجع تجمیع می‌شوند " +
                    "(M5→H1، M15→H4، H1→H4، H4→D1) و شش رأی مستقل شمرده می‌شود: قیمت نسبت به EMA50، " +
                    "EMA20 نسبت به EMA50، شیب EMA50 بر حسب ATR، Efficiency Ratio، ساختار سقف/کف " +
                    "(HH/HL یا LH/LL) و جابه‌جایی خالص بر حسب ATR. مجموع ≥+۲ صعودی و ≤−۲ نزولی است. " +
                    "(۲) همان اندازه‌گیری برای همهٔ نمادهای پویش‌شده تکرار می‌شود و «عرض بازار» را می‌سازد. " +
                    "(۳) جهت دلار از شش جفت اصلی و جوّ ریسک از رمزارز/سهام در برابر طلا خوانده می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("جهت بازار", read?.bias?.label ?: "—", biasColor, Modifier.weight(1f))
                StatTile("قدرت", read?.let { "${it.strength}٪" } ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("عرض بازار", read?.let { "${it.breadthUp}↑/${it.breadthDown}↓" } ?: "—",
                    AurumColors.Cyan, Modifier.weight(1f))
                StatTile("دلار", read?.dollarBias?.label ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("جوّ بازار", read?.riskTone?.label ?: "—",
                    when (read?.riskTone) {
                        RiskTone.RISK_ON -> AurumColors.Green
                        RiskTone.RISK_OFF -> AurumColors.Red
                        else -> AurumColors.Gold
                    }, Modifier.weight(1f))
                StatTile("نمادِ سنجیده", read?.let { "${it.measured}" } ?: "—",
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

            if (read == null) {
                Text("هنوز هیچ پویشی چیزی اندازه نگرفته است (یا کندل بستهٔ کافی نرسیده). تا وقتی " +
                        "روند کلی بازار مجهول است، معامله‌ها فقط با روندِ خودِ نماد و بقیهٔ گیت‌ها " +
                        "سنجیده می‌شوند و هیچ جهتِ کلیِ حدسی ساخته نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            } else {
                read.drivers.forEach { driver ->
                    Text("• $driver", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                }
                read.families.forEach { family ->
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
                        if (context.gate.allowed) "" else " → ورود مسدود شد",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !context.gate.allowed -> AurumColors.Red
                        aligned -> AurumColors.Green
                        context.alignment == TrendAlignment.AGAINST -> AurumColors.Red
                        else -> AurumColors.Gold
                    })
                Text(context.noteFa, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("قاعدهٔ اجرا: روش روندی (پولبک/شکست/درایو بازگشایی) خلاف جهتِ اندازه‌گیری‌شده مسدود " +
                        "می‌شود؛ بازگشت به میانگین فقط در بازارِ بی‌روند مجاز است؛ ورودِ دارایی ریسکی " +
                        "خلافِ جوّ بازار منع نمی‌شود ولی کف امتیاز +۶ و کف اطمینان +۵ می‌گیرد. همین خوانش " +
                        "در لحظهٔ ورود در ژورنال ثبت و در پنجرهٔ همان معامله روی صفحهٔ چارت نشان داده می‌شود.",
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
                Text("اگر همه‌چیز سبز است و معامله‌ای باز نشد، دلیل لحظه‌ای‌اش در بخش «معاملهٔ خودکار کاغذی» پایین نوشته می‌شود (مثلاً کراس تأییدنشده یا گیت متد بازار).",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

private fun fmt2(value: Double?): String =
    if (value == null) "—" else String.format(java.util.Locale.US, "%.2f", value)

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
        title = "✨ برترین فرصت معاملاتی (#1 Best Opportunity)",
        subtitle = if (best != null) "بالاترین شواهد تاییدشده، آزادی کامل چیکو اسپن و بهترین نسبت R:R" else "اسکن هوشمند ۵۰+ سهم و نماد در پس‌زمینه",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (best != null) {
                Pill(
                    text = if (isBuy) "خرید LONG (${(best.confidence ?: 95.0).toInt()}%)" else "فروش SHORT (${(best.confidence ?: 95.0).toInt()}%)",
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
                        Pill("شانس ${(best.confidence ?: 95.0).toInt()}%", actionColor)
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
                        SignalConditionItemRow(
                            label = "موقعیت قیمت نسبت به ابر کومو (Kumo 8/24/72)",
                            detail = if (isBuy) "قیمت بالای ابر صعودی مستقر است" else "قیمت زیر ابر نزولی مستقر است",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "هم‌جهتی و تقاطع تنکان‌سن و کیجون‌سن (TK Cross)",
                            detail = if (isBuy) "تنکان بالای کیجون و تراز صعودی" else "تنکان زیر کیجون و تراز نزولی",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "آزادی کامل چیکواسپن (Chikou Clearance 24)",
                            detail = if (isBuy) "چیکو اسپن بدون مانع بالای کندل‌های ۲۴ دوره گذشته" else "چیکو اسپن بدون مانع زیر کندل‌های ۲۴ دوره گذشته",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "فیلتر ضد ساید و قدرت ترند (Anti-Sideways Guard)",
                            detail = "عدم وجود فشردگی یا رنج، اسلوپ مومنتوم فعال",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "تراز چندتایم‌فریم و نسبت ریسک به ریوارد (MTF & R:R)",
                            detail = "تایید تراز بالاتر، نسبت ریوارد 1:${String.format(java.util.Locale.US, "%.1f", best.riskReward ?: 2.2)}",
                            ok = true,
                        )
                    }
                }

                // Action button: Trade this opportunity
                Button(
                    onClick = { onTrade(best.symbol) },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        if (isBuy) "انتقال به چارت و ورود به معامله خرید ${best.symbol}" else "انتقال به چارت و ورود به معامله فروش ${best.symbol}",
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
                    text = "برای شکار بهترین ستاپ معاملاتی از میان تمامی ۵۰ نماد، دکمهٔ اسکن را بزنید.",
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
                    Text(if (sweeping) "در حال اسکن..." else "اسکن ۵۰ نماد")
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
                Pill("امتیاز ${(candidate.confidence ?: 90.0).toInt()}%", actionColor)
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
                        Pill(if (item.ok) "✓ تایید" else "✗ رد", if (item.ok) AurumColors.Green else AurumColors.Red)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("کومو ۸/۲۴/۷۲ + تنکان/کیجون + چیکو ۲۴", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                    Pill("✓ تایید کامل شروط", AurumColors.Green)
                }
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
                Pill("روی چارت فعال است", AurumColors.Green)
            } else {
                Text(
                    text = "نمایش روی چارت ↗",
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

@Composable
private fun SignalConditionItemRow(
    label: String,
    detail: String,
    ok: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Pill(
            text = if (ok) "✓ تایید" else "✗ رد",
            color = if (ok) AurumColors.Green else AurumColors.Red,
        )
    }
}

/** All-pairs background radar summary */
@Composable
internal fun PairRadarSummaryCard(scan: PairScanState, onScan: () -> Unit, onSelectSymbol: (String) -> Unit, now: Long) {
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
                "همهٔ نمادهای کاتالوگ و واچ‌لیست، با همان متد و دادهٔ واقعی به‌ترتیب بررسی می‌شوند؛ " +
                    "نبود فید/بسته‌بودن بازار هم با دلیل ثبت می‌شود. فقط واچ‌لیست فعال مجاز به فرصت معاملاتی است. " +
                    "پایش پس‌زمینه نیازمند فعال‌بودن سرویس در تنظیمات است.",
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
            "دلیل تک‌تک بررسی‌ها در انتهای همین صفحه ثبت می‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        scan.lastError?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
