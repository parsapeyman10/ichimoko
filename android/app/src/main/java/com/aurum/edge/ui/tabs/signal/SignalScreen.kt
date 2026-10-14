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
import com.aurum.edge.core.ConfluenceStatus
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
import com.aurum.edge.data.AiEntryEffect
import com.aurum.edge.data.DecisionRecord
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.TraderOpinionState
import com.aurum.edge.engine.NewsConfluence
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
import com.aurum.edge.ui.components.formatDateTime
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
fun SignalScreen(viewModel: AurumViewModel, onChartSymbol: (String) -> Unit) {
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
    val decisionLog by viewModel.decisionLog.collectAsStateWithLifecycle()
    var showAllDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var visibleDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(12) }
    var scanClock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            scanClock = System.currentTimeMillis() // expire historical recommendations without a new sweep
        }
    }
    val freshCandidates = ScanRanking.rank(scanState.statuses, com.aurum.edge.core.Interval.M5, scanClock)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
        // The background scanner publishes each finished symbol immediately. Never require
        // the user to press Scan or wait for the entire sweep to display a fresh best pick.
        BestOpportunityDetailedCard(
            best = freshCandidates.firstOrNull(),
            sweeping = scanState.sweeping,
            onTrade = onChartSymbol,
        )

        SectionCard("دلیل بررسی") {
            if (decisionLog.isEmpty()) Text("هنوز بررسی ثبت نشده است")
            // An AI entry review is a separate event, not the technical scan's latest score.
            val latestBySymbol = decisionLog.filter { AiEntryEffect.fromAction(it.action) == null }
                .distinctBy { it.symbol }
            if (decisionLog.size > latestBySymbol.size) OutlinedButton(onClick = { showAllDecisions = !showAllDecisions }) {
                Text(if (showAllDecisions) "آخرین دلیل هر نماد" else "تاریخچهٔ ${decisionLog.size} بررسی اخیر")
            }
            val records = if (showAllDecisions) decisionLog else latestBySymbol.ifEmpty { decisionLog.distinctBy { it.symbol } }
            records.take(visibleDecisions).forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val aiEffect = AiEntryEffect.fromAction(record.action)
                Text(if (aiEffect != null)
                    "🤖 ${record.symbol} · ${record.interval} · ${formatTime(record.checkedAt)} · ${aiEffect.label}" +
                        (record.aiModel?.let { " · مدل $it" } ?: " · بدون پاسخ معتبر مدل")
                    else "${record.symbol} · ${record.interval} · ${formatTime(record.checkedAt)} · " +
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
                    "(۳) جهت دلار از شش جفت اصلی و جوّ ریسک از رمزارز در برابر طلا خوانده می‌شود.",
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

/** Evidence for the selected SIGNAL symbol, never for the chart-only TradingView selection.
 * The decision log is durable; old responses must not be passed off as this bar's analysis.
 */
@Composable
private fun AiProofCard(
    market: MarketState,
    configured: Boolean,
    connection: AiConnectionState,
    opinion: TraderOpinionState,
    news: PersianNewsState,
    decisions: List<DecisionRecord>,
    now: Long,
    onRefreshOpinion: () -> Unit,
) {
    val symbol = market.symbol
    val signal = market.signal
    val lastEntry = decisions.firstOrNull { it.symbol == symbol && AiEntryEffect.fromAction(it.action) != null }
    val effect = lastEntry?.let { AiEntryEffect.fromAction(it.action) }
    val currentBarReview = lastEntry != null && signal != null && lastEntry.barTime > 0L &&
        lastEntry.barTime == signal.barTime && now - lastEntry.checkedAt in 0L..300_000L
    val ownOpinion = opinion.opinion?.takeIf { it.symbol == symbol }
    val aiVeto = signal?.blockers?.firstOrNull { it.startsWith("وتوی AI معتبر:") }
    val alignment = signal?.let { NewsConfluence.alignment(symbol, it.action, news, now) }
    val newsVerdict = news.aiBySymbol[symbol] ?: news.ai.takeIf { it.symbol == symbol }

    SectionCard("نقش واقعی AI · $symbol", "نماد فعال تحلیل (مستقل از انتخاب چارت) · اتصال ≠ پاسخ ≠ اثر") {
        Text(when {
            !configured -> "اتصال: کلید/مدل تنظیم نشده؛ هیچ پاسخ مدل را از روی اسکن فنی فرض نکنید."
            connection.reachable == true -> "اتصال: تست موفق در ${connection.checkedAt?.let(::formatTime) ?: "—"}؛ صرفاً دسترسی به سرویس تأیید شده است."
            connection.reachable == false -> "اتصال: ناموفق در ${connection.checkedAt?.let(::formatTime) ?: "—"}؛ ${connection.detail}"
            else -> "اتصال: تنظیم شده، اما در این اجرا هنوز تست نشده است."
        }, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)

        Text("ورود خودکار کاغذی: ابتدا سیگنال چهارلایه، فید و ریسک بررسی می‌شوند؛ " +
            "تنها برای کاندیدای عبورکرده مدل می‌تواند صریحاً وتو کند. بازبینی دستی/نظر تریدر مجوز ورود نیست.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp))
        if (lastEntry == null || effect == null) {
            Text("برای $symbol هنوز هیچ درخواستِ بررسی ارزندگی ورود خودکار در گزارش ثبت نشده است؛ " +
                "اسکن فنی و تست اتصال، پاسخ مدل برای این نماد نیستند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("آخرین بررسی ورود · ${formatDateTime(lastEntry.checkedAt)} · ${lastEntry.interval}" +
                (lastEntry.aiModel?.let { " · مدل $it" } ?: " · بدون پاسخ معتبر مدل") +
                if (currentBarReview) " · همین کندل" else " · تاریخی؛ مربوط به تحلیل فعلی نیست",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            Text("قبل از بررسی AI: کاندیدای عبورکرده از شروط اولیهٔ فنی/ورود خودکار. " +
                "بعد از بررسی AI: ${lastEntry.reason}. " +
                (if (effect == AiEntryEffect.VETO) "ورود متوقف شد." else "بازبینی نهایی فید، خبر و ریسک ادامه یافت؛ این رکورد اثبات ثبت معامله نیست."),
                style = MaterialTheme.typography.bodySmall,
                color = if (effect == AiEntryEffect.VETO) AurumColors.Red else AurumColors.Gold)
        }

        Text("خبر/مدل: " + when {
            aiVeto != null -> "$aiVeto (سیگنال این کندل مسدود شد)"
            alignment?.status == ConfluenceStatus.CONFIRMED && newsVerdict != null ->
                "پاسخ معتبر ${newsVerdict.model} برای $symbol در ${newsVerdict.checkedAt?.let(::formatDateTime) ?: "—"}؛ " +
                    "هم‌جهت سیگنال فنی، فقط شاهد خبری/ژورنال؛ مجوز معامله نیست."
            else -> "برای این سیگنال وتوی معتبر AI ثبت نشده؛ " +
                "${alignment?.detail ?: "هنوز سیگنال/شاهد خبری معتبر برای تطبیق وجود ندارد"}. " +
                "نبود شاهد را تأیید AI تلقی نکنید."
        }, style = MaterialTheme.typography.labelSmall, color = if (aiVeto != null) AurumColors.Red else AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp))

        if (ownOpinion != null) {
            val fresh = now - ownOpinion.generatedAt in 0L..600_000L
            Text("نظر مشورتی مستقل · ${ownOpinion.model} · ${formatDateTime(ownOpinion.generatedAt)}" +
                (if (fresh) "" else " · قدیمی") + ": ${ownOpinion.bias} — ${ownOpinion.summary}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("نظر مشورتی برای $symbol: هنوز پاسخ معتبر همین نماد دریافت نشده است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp))
        }
        opinion.error?.let { Text("خطای آخرین درخواست نظر مشورتی: $it",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Red) }
        if (configured) OutlinedButton(onClick = onRefreshOpinion, enabled = !opinion.loading) {
            Text(if (opinion.loading) "در حال دریافت نظر..." else "درخواست نظر مشورتی (مصرف API)")
        }
        Text("این کارت نقش اجرایی مدل را نشان می‌دهد، نه بهبود سودآوری. " +
            "برای اثبات تأثیر بر بازده، مقایسهٔ معاملات واقعیِ با/بی AI لازم است.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
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
                    ai.reachable == true -> "🤖 تست اتصال AI موفق بود؛ این به‌تنهایی پاسخ مدل برای این نماد یا اثر آن بر ورود نیست (جزئیات در کارت پایین)."
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
    AssetClass.entries.filter { it != AssetClass.STOCK }.forEach { category ->
        val rows = scan.statuses.filter { it.assetClass == category }
        val openRows = rows.filterNot { com.aurum.edge.core.MarketHours.closedFor(it.symbol, now) }
        val checked = openRows.count { it.state != "pending" && it.state != "closed" }
        val closedCount = rows.size - openRows.size
        val pending = openRows.any { it.state == "pending" }
        val ranked = picks[category].orEmpty()
        SectionCard("برترین‌های ${category.label}",
            "بازار باز: $checked/${openRows.size} بررسی‌شده · " +
                "$closedCount بسته (بدون درخواست/اسکن) · " +
                "${rows.count { it.state == "error" }} فید ناموفق · ${rows.count { it.state == "partial" }} دادهٔ ناقص",
            trailing = { Pill(when {
                openRows.isEmpty() -> "بسته؛ بدون اسکن"
                scan.sweeping && pending -> "در انتظار پاسخ منبع"
                scan.sweeping -> "این دسته بررسی شد"
                else -> "آخرین دور"
            }, if (openRows.isEmpty()) AurumColors.Gold else AurumColors.Cyan) }) {
            Box(Modifier.fillMaxWidth().height(6.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))) {
                Box(Modifier.fillMaxWidth(if (openRows.isEmpty()) 0f else checked.toFloat() / openRows.size)
                    .height(6.dp).background(AurumColors.Cyan, RoundedCornerShape(8.dp)))
            }
            Text("رتبه فقط از کندل‌های بستهٔ تازه است؛ «مشاهده» نه مجوز ورود است نه قیمت لحظه‌ای. " +
                "اگر دادهٔ هفت بازه یا فید معتبر نباشد، رتبه ساخته نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(vertical = 6.dp))
            if (openRows.isEmpty() && rows.isNotEmpty()) Text(
                "این دسته طبق ساعت سشن بسته است؛ هیچ قیمت جدید یا سیگنالی برای آن اسکن نمی‌شود. " +
                    "تاریخچهٔ قبلی، قیمت آنلاین و مجوز ورود نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            else if (ranked.isEmpty()) {
                val reason = openRows.firstOrNull { it.state == "error" || it.state == "partial" || it.state == "blocked" }
                Text(if (checked == 0) "بازار باز است اما هنوز بررسی نشده؛ منتظر نوبت اسکن بمانید."
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
                        Pill(if (approved) "کاندیدای ورود" else if (row.state == "watch") "فشردگی · آماده‌باش" else "فقط مشاهده",
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
                    Text("منبع کندل پایه: ${row.baseSource ?: "نامعلوم"} · آخرین کندل بسته: " +
                        (row.lastBarAt?.let { formatTime(it) } ?: "نامعلوم") +
                        " · بازه‌های M5/M15/H1 مستقل‌اند؛ این قیمت تیک ورود نیست.",
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
        title = "برترین کاندید مجاز",
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
            Text(if (sweeping) "در حال بررسی بازارها" else "کاندیدای مجازِ تازه‌ای موجود نیست",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
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
        subtitle = "فرصت‌های ممتاز استخراج‌شده از میان ۲۰۰ کریپتو و نمادهای فارکس/کالا با تفکیک دقیق دسته دارایی و امتیاز شروط",
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
        title = "پایش پیوستهٔ ۲۰۰ کریپتو و نمادهای فارکس/کالا در پس‌زمینه",
        subtitle = "دیتاگیری و کشف خودکار فرصت‌ها از بازار کریپتو، طلا، نفت و فارکس",
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
                "هر یک از ۲۳۰ نماد موتور مستقل دارد؛ نماد کند، بقیه را پشت یک دور سراسری نگه نمی‌دارد. " +
                    "فقط دادهٔ واقعی هم‌نماد معتبر است؛ منبع پرظرفیت صف نمی‌سازد و موتور همان نماد دوباره تلاش می‌کند. " +
                    "محدودیت ناشر حذف نشده و دریافت آنی همهٔ نمادها تضمین نیست. پایش پس‌زمینه نیازمند سرویس فعال است.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onScan,
                enabled = true,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("بازبینی مستقل")
            }
        }
        val skipped = scan.statuses.count { com.aurum.edge.core.MarketHours.closedFor(it.symbol, now) }
        val requested = scan.statuses.count { it.state != "pending" && it.state != "closed" }
        Text("بازارهای بازِ بررسی‌شده: $requested/${scan.totalCount - skipped} · " +
            "$skipped نماد بسته بدون درخواست فید · " +
            "آخرین موتور تکمیل‌شده: ${scan.lastSweepAt?.let { formatTime(it) } ?: "هنوز تکمیل نشده"}" +
            (scan.lastSweepDurationMs?.let { " · مدت همان نماد: ${it / 1000.0} ثانیه" } ?: "") + " · " +
            "هر موتور پس از بررسی خودش حداقل ۳۰ ثانیه فاصله دارد؛ انتظار ظرفیت با تلاش مجدد کوتاه همان موتور است. " +
            "زمان واقعی به شبکه و محدودیت منبع بستگی دارد؛ دلیل هر بررسی در انتهای صفحه است.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        scan.lastError?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
