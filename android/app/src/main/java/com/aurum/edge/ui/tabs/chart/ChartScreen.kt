package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.IchimokuChartSeries
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AppNetwork
import com.aurum.edge.data.ChartSymbolChoices
import com.aurum.edge.data.LiveQuoteFetcher
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.TradingViewPositionBox
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(
    initialTicker: String,
    onSelectTicker: (String) -> Unit,
    viewModel: AurumViewModel? = null,
) {
    val route by AppNetwork.route.collectAsStateWithLifecycle()
    val allTrades = viewModel?.trades?.collectAsStateWithLifecycle()?.value.orEmpty()
    val liveTicks = viewModel?.liveTicks?.collectAsStateWithLifecycle()?.value.orEmpty()
    val lastMarks = viewModel?.lastDisplayMarks?.collectAsStateWithLifecycle()?.value.orEmpty()

    var useTradingViewWeb by rememberSaveable { mutableStateOf(false) }
    var selectedTfLabel by rememberSaveable { mutableStateOf("15m") }
    var showPicker by remember { mutableStateOf(false) }
    var reloadCount by remember { mutableIntStateOf(0) }
    var selectedTradeId by rememberSaveable { mutableStateOf("") }
    var showBottomPositionCard by rememberSaveable { mutableStateOf(true) }

    val appSymbol = remember(initialTicker) {
        val fromChoice = ChartSymbolChoices.all.firstOrNull {
            it.ticker.equals(initialTicker, ignoreCase = true) || it.id.equals(initialTicker, ignoreCase = true)
        }?.id
        fromChoice ?: V1Universe.normalize(initialTicker).takeIf(V1Universe::valid) ?: "BTC/USDT"
    }
    val tvTicker = remember(appSymbol, initialTicker) {
        TradingViewSymbols.find(appSymbol)
            ?: ChartSymbolChoices.all.firstOrNull { it.ticker == initialTicker }?.ticker
            ?: "BINANCE:BTCUSDT"
    }

    val openTradesAll = remember(allTrades) { allTrades.filter { it.isOpen } }
    val openTradesForSymbol = remember(openTradesAll, appSymbol) {
        openTradesAll.filter { V1Universe.normalize(it.symbol) == appSymbol }
    }
    val activePositionTrade = remember(openTradesForSymbol, selectedTradeId) {
        openTradesForSymbol.firstOrNull { it.id == selectedTradeId } ?: openTradesForSymbol.firstOrNull()
    }

    // وقتی کاربر روی نمادی کلیک می‌کند که پوزیشن باز دارد، تایم‌فریم چارت با پوزیشن هماهنگ شود
    LaunchedEffect(appSymbol, openTradesForSymbol.size) {
        val firstOpen = openTradesForSymbol.firstOrNull()
        if (firstOpen != null && selectedTradeId.isBlank()) {
            selectedTradeId = firstOpen.id
        }
    }

    var candles by remember(appSymbol, selectedTfLabel) { mutableStateOf<List<Candle>>(emptyList()) }
    var providerName by remember(appSymbol, selectedTfLabel) { mutableStateOf("") }
    var loadingCandles by remember(appSymbol, selectedTfLabel) { mutableStateOf(true) }

    LaunchedEffect(appSymbol, selectedTfLabel, reloadCount) {
        loadingCandles = true
        val tf = Interval.fromLabel(selectedTfLabel)
        val fetched = LiveQuoteFetcher.fetchTimeframeCandles(
            symbolInput = appSymbol,
            interval = tf,
            limit = 110,
            forceNetwork = reloadCount > 0,
        )
        if (fetched != null) {
            candles = fetched.first
            providerName = fetched.second
        }
        loadingCandles = false
    }

    val chartSeries = remember(candles) {
        IchimokuStrategyEngine.computeChartSeries(candles, BaseStrategyConfig())
    }
    val latestSnap = remember(candles) {
        IchimokuStrategyEngine.computeSnapshot(candles, BaseStrategyConfig())
    }
    val waveProj = remember(candles, latestSnap) {
        if (latestSnap != null) {
            val dir = if (latestSnap.close >= latestSnap.kumoTop) {
                SignalAction.BUY
            } else {
                SignalAction.SELL
            }
            IchimokuStrategyEngine.computeHosodaWave(candles, dir, latestSnap)
        } else null
    }

    val livePriceForSymbol = liveTicks[appSymbol]?.price
        ?: lastMarks[appSymbol]?.price
        ?: candles.lastOrNull()?.close

    Column(Modifier.fillMaxSize().background(AurumColors.Bg)) {
        // ── ۱. نوار ابزار فشرده بالای چارت (نماد، تایم‌فریم، سوییچ چارت بومی / TradingView) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Pill(
                    text = "🔍 $appSymbol ▾",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { showPicker = true },
                )
                listOf("5m", "15m", "1H", "4H").forEach { tf ->
                    Pill(
                        text = tf,
                        color = if (selectedTfLabel == tf && !useTradingViewWeb) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable {
                            useTradingViewWeb = false
                            selectedTfLabel = tf
                        },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Pill(
                    text = if (useTradingViewWeb) "چارت ایچیموکو ⚡" else "TradingView 🌐",
                    color = if (useTradingViewWeb) AurumColors.Green else AurumColors.TextSecondary,
                    modifier = Modifier.clickable { useTradingViewWeb = !useTradingViewWeb },
                )
                Pill(
                    text = "↻",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { reloadCount++ },
                )
            }
        }

        // ── ۲. نوار اختصاصی نمادهای دارای پوزیشن باز (برای پرش سریع به ابزار TP/SL تریدینگ‌ویو) ──
        if (openTradesAll.isNotEmpty()) {
            val openBySymbol = remember(openTradesAll) {
                openTradesAll.groupBy { V1Universe.normalize(it.symbol) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A141E))
                    .border(0.5.dp, AurumColors.Green.copy(alpha = 0.35f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = "📌 نمادهای دارای معامله (${openBySymbol.size}):",
                    color = AurumColors.Gold,
                )
                openBySymbol.forEach { (sym, symTrades) ->
                    val first = symTrades.first()
                    val isCurrentSym = sym == appSymbol
                    val isBuy = first.action == SignalAction.BUY
                    val dirBadge = if (isBuy) "LONG ↗" else "SHORT ↘"
                    Pill(
                        text = "$sym · $dirBadge (${symTrades.size})",
                        color = if (isCurrentSym) AurumColors.Gold else if (isBuy) AurumColors.Green else AurumColors.Red,
                        modifier = Modifier.clickable {
                            selectedTradeId = first.id
                            selectedTfLabel = first.interval.label
                            useTradingViewWeb = false
                            val nextTv = TradingViewSymbols.find(sym) ?: sym
                            onSelectTicker(nextTv)
                        },
                    )
                }
            }
        }

        // ── ۳. اگر نماد فعلی چند معامله باز دارد (مثلاً با چند استراتژی یا در 15m و 5m)، انتخابگر پوزیشن ──
        if (openTradesForSymbol.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "انتخاب پوزیشن $appSymbol:",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                openTradesForSymbol.forEachIndexed { idx, t ->
                    val active = activePositionTrade?.id == t.id
                    Pill(
                        text = "#${idx + 1} [${t.interval.label}] ${t.strategyTitleFa.substringBefore('(').trim()}",
                        color = if (active) AurumColors.Gold else AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            selectedTradeId = t.id
                            selectedTfLabel = t.interval.label
                        },
                    )
                }
            }
        }

        // ── ۴. نوار انتخاب سریع ۱۵ نماد ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            V1Universe.allSymbols.forEach { sym ->
                val active = sym == appSymbol
                val hasOpen = openTradesAll.any { V1Universe.normalize(it.symbol) == sym }
                Pill(
                    text = if (hasOpen) "🟢 $sym" else sym,
                    color = when {
                        active -> AurumColors.Gold
                        hasOpen -> AurumColors.Green
                        else -> AurumColors.TextMuted
                    },
                    modifier = Modifier.clickable {
                        val nextTv = TradingViewSymbols.find(sym) ?: sym
                        onSelectTicker(nextTv)
                    },
                )
            }
        }

        if (loadingCandles && !useTradingViewWeb) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AurumColors.Gold)
        }

        // ── ۵. نمایش چارت با ابزار پوزیشن تریدینگ‌ویو (حد سود سبز + خط ورود + حد ضرر قرمز) ──
        if (!useTradingViewWeb) {
            NativeIchimokuCanvasChart(
                symbol = appSymbol,
                timeframe = selectedTfLabel,
                provider = providerName,
                series = chartSeries,
                targetV = waveProj?.targetV,
                targetN = waveProj?.targetN,
                targetE = waveProj?.targetE,
                kijunStop = latestSnap?.kijun,
                activeTrade = activePositionTrade,
                allOpenTradesForSymbol = openTradesForSymbol,
                livePrice = livePriceForSymbol,
                showBottomCard = showBottomPositionCard,
                onToggleBottomCard = { showBottomPositionCard = !showBottomPositionCard },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            val webHtml = remember(appSymbol, tvTicker, selectedTfLabel, candles, activePositionTrade, livePriceForSymbol) {
                buildReliableTradingViewWebHtml(
                    appSymbol = appSymbol,
                    tvTicker = tvTicker,
                    timeframeLabel = selectedTfLabel,
                    series = chartSeries,
                    activeTrade = activePositionTrade,
                    livePrice = livePriceForSymbol,
                )
            }
            key(appSymbol, selectedTfLabel, reloadCount) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(AndroidColor.rgb(19, 23, 34))
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                javaScriptCanOpenWindowsAutomatically = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(false)
                                builtInZoomControls = false
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                allowFileAccess = false
                                allowContentAccess = false
                                userAgentString = MOBILE_USER_AGENT
                                textZoom = 100
                            }
                            setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?,
                                ) {
                                    // Never show a 403 error page; keep the embedded TradingView Pro engine active
                                    if (request?.isForMainFrame == true) {
                                        view?.evaluateJavascript("if(window.switchMode) switchMode('pro');", null)
                                    }
                                }
                            }
                            loadDataWithBaseURL(
                                "https://app.aurum-edge.local/",
                                webHtml,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            "https://app.aurum-edge.local/",
                            webHtml,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    },
                    onReset = null,
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    },
                )
            }
        }

        // ── ۶. پنل پایین چارت: باکس کامل حد سود / حد ضرر تریدینگ‌ویو برای نماد فعلی ──
        if (activePositionTrade != null && showBottomPositionCard) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.Surface)
                    .border(0.5.dp, AurumColors.Line)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                TradingViewPositionBox(
                    trade = activePositionTrade,
                    currentPrice = livePriceForSymbol,
                )
            }
        }
    }

    if (showPicker) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("انتخاب نماد چارت (۱۵ نماد)") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("جستجوی نماد") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 370.dp)) {
                        items(ChartSymbolChoices.search(query), key = { it.id }) { choice ->
                            TextButton(
                                onClick = {
                                    if (choice.ticker == tvTicker) reloadCount++
                                    else onSelectTicker(choice.ticker)
                                    showPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("${choice.id} · ${choice.label}", style = MaterialTheme.typography.bodyMedium)
                                    Text(choice.ticker, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("بستن") } },
        )
    }
}

@Composable
private fun NativeIchimokuCanvasChart(
    symbol: String,
    timeframe: String,
    provider: String,
    series: IchimokuChartSeries,
    targetV: Double?,
    targetN: Double?,
    targetE: Double?,
    kijunStop: Double?,
    activeTrade: PaperTrade?,
    allOpenTradesForSymbol: List<PaperTrade>,
    livePrice: Double?,
    showBottomCard: Boolean,
    onToggleBottomCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bars = series.bars
    val futureKumo = series.futureKumo

    var visibleBarsCount by rememberSaveable { mutableIntStateOf(52) }
    var panOffsetBars by rememberSaveable(symbol, timeframe) { mutableFloatStateOf(0f) }
    var fitTradeTpSl by rememberSaveable(symbol) { mutableStateOf(true) }
    var inspectedIndex by remember(bars) { mutableStateOf<Int?>(bars.lastIndex.takeIf { it >= 0 }) }

    Column(modifier = modifier.background(AurumColors.ChartBg)) {
        if (bars.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("در حال بارگذاری کندل‌های $symbol ($timeframe)...", color = AurumColors.Gold)
            }
            return@Column
        }

        val inspectIdx = (inspectedIndex ?: bars.lastIndex).coerceIn(0, bars.lastIndex)
        val sel = bars[inspectIdx]

        // ── نوار اطلاعات کندل، ۵ خط ایچیموکو و کنترل‌های زوم / فیت پوزیشن تریدینگ‌ویو ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.Surface)
                .border(0.5.dp, AurumColors.Line)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$symbol · $timeframe · ${formatPriceFor(symbol, livePrice ?: sel.candle.close)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (sel.candle.close >= sel.candle.open) AurumColors.Green else AurumColors.Red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (activeTrade != null) {
                        Pill(
                            text = if (fitTradeTpSl) "📐 فیت TP/SL ✓" else "📐 فیت TP/SL",
                            color = if (fitTradeTpSl) AurumColors.Green else AurumColors.TextMuted,
                            modifier = Modifier.clickable { fitTradeTpSl = !fitTradeTpSl },
                        )
                        Pill(
                            text = if (showBottomCard) "پنل پوزیشن ▾" else "پنل پوزیشن ▴",
                            color = AurumColors.Gold,
                            modifier = Modifier.clickable { onToggleBottomCard() },
                        )
                    }
                    Pill(
                        text = "➕",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount - 12).coerceAtLeast(24) },
                    )
                    Pill(
                        text = "➖",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount + 12).coerceAtMost(100) },
                    )
                    Pill(
                        text = "⏭",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable {
                            panOffsetBars = 0f
                            inspectedIndex = bars.lastIndex
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("O: ${formatPrice(sel.candle.open)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                Text("H: ${formatPrice(sel.candle.high)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                Text("L: ${formatPrice(sel.candle.low)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                Text("C: ${formatPrice(sel.candle.close)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Tenkan: ${formatPrice(sel.tenkan)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF29B6F6))
                Text("Kijun: ${formatPrice(sel.kijun)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB300))
                Text("SSA: ${formatPrice(sel.currentSpanA)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF66BB6A))
                Text("SSB: ${formatPrice(sel.currentSpanB)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
            }
        }

        // ── بوم گرافیکی کندل‌ها (در جهت LTR استاندارد چارت‌های مالی) + ابزار پوزیشن تریدینگ‌ویو ──
        val futureSpaceBars = 18
        val totalSlots = visibleBarsCount + futureSpaceBars
        val endIdx = (bars.lastIndex - panOffsetBars.roundToInt()).coerceIn(10, bars.lastIndex)
        val startIdx = (endIdx - visibleBarsCount + 1).coerceAtLeast(0)
        val visibleSlice = bars.subList(startIdx, endIdx + 1)

        var minP = Double.POSITIVE_INFINITY
        var maxP = Double.NEGATIVE_INFINITY
        visibleSlice.forEach { b ->
            minP = minOf(minP, b.candle.low, b.tenkan, b.kijun, b.currentSpanA, b.currentSpanB)
            maxP = maxOf(maxP, b.candle.high, b.tenkan, b.kijun, b.currentSpanA, b.currentSpanB)
        }
        if (endIdx == bars.lastIndex) {
            futureKumo.take(futureSpaceBars).forEach { fk ->
                minP = minOf(minP, fk.spanA, fk.spanB)
                maxP = maxOf(maxP, fk.spanA, fk.spanB)
            }
        }
        // اگر نماد پوزیشن باز دارد و گزینه «فیت TP/SL» روشن است، کل مستطیل حد سود و حد ضرر در کادر چارت جا می‌شود
        if (fitTradeTpSl && activeTrade != null) {
            minP = minOf(minP, activeTrade.entry, activeTrade.stopLoss, activeTrade.takeProfit)
            maxP = maxOf(maxP, activeTrade.entry, activeTrade.stopLoss, activeTrade.takeProfit)
        }
        val spanP = (maxP - minP).coerceAtLeast(1e-6)
        val padMin = minP - spanP * 0.10
        val padMax = maxP + spanP * 0.10
        val padRange = (padMax - padMin).coerceAtLeast(1e-6)

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(bars.size, visibleBarsCount) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val slotW = size.width.toFloat() / totalSlots.toFloat().coerceAtLeast(1f)
                            val deltaBars = dragAmount.x / slotW.coerceAtLeast(4f)
                            panOffsetBars = (panOffsetBars + deltaBars).coerceIn(0f, (bars.size - 15).coerceAtLeast(0).toFloat())
                        }
                    }
                    .pointerInput(startIdx, visibleSlice.size, totalSlots) {
                        detectTapGestures { offset ->
                            val slotW = size.width / totalSlots.toFloat().coerceAtLeast(1f)
                            val tappedSlot = (offset.x / slotW).toInt()
                            if (tappedSlot in visibleSlice.indices) {
                                inspectedIndex = startIdx + tappedSlot
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val slotW = w / totalSlots.toFloat()

                fun yOf(price: Double): Float {
                    val norm = ((price - padMin) / padRange).toFloat().coerceIn(0f, 1f)
                    return h - norm * h
                }

                fun xOfSlot(slot: Int): Float = (slot + 0.5f) * slotW

                // ۱. خطوط شبکه افقی و برچسب‌های محور قیمت سمت راست
                val dashGrid = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
                val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = AndroidColor.argb(150, 140, 155, 175)
                    textSize = 24f
                    textAlign = Paint.Align.RIGHT
                }
                for (g in 1..4) {
                    val gy = h * (g / 5f)
                    val gPrice = padMax - (g / 5.0) * padRange
                    drawLine(
                        color = Color(0xFF19202C),
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1f,
                        pathEffect = dashGrid,
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        formatPriceFor(symbol, gPrice),
                        w - 10f,
                        gy - 6f,
                        gridLabelPaint,
                    )
                }

                // ۲. رسم ابر کوموی فعلی (Current Kumo Cloud)
                for (i in 0 until visibleSlice.size - 1) {
                    val cur = visibleSlice[i]
                    val nxt = visibleSlice[i + 1]
                    val x1 = xOfSlot(i)
                    val x2 = xOfSlot(i + 1)
                    val isBullCloud = (cur.currentSpanA + nxt.currentSpanA) >= (cur.currentSpanB + nxt.currentSpanB)
                    val cloudFill = if (isBullCloud) Color(0x33089981) else Color(0x33F23645)
                    val path = Path().apply {
                        moveTo(x1, yOf(cur.currentSpanA))
                        lineTo(x2, yOf(nxt.currentSpanA))
                        lineTo(x2, yOf(nxt.currentSpanB))
                        lineTo(x1, yOf(cur.currentSpanB))
                        close()
                    }
                    drawPath(path, color = cloudFill)
                    drawLine(Color(0x99089981), Offset(x1, yOf(cur.currentSpanA)), Offset(x2, yOf(nxt.currentSpanA)), 1.4f)
                    drawLine(Color(0x99F23645), Offset(x1, yOf(cur.currentSpanB)), Offset(x2, yOf(nxt.currentSpanB)), 1.4f)
                }

                // ۳. رسم ابر آینده (Future Kumo Cloud — ۲۶ دوره جلوتر در سمت راست آخرین کندل)
                if (endIdx == bars.lastIndex && futureKumo.isNotEmpty() && visibleSlice.isNotEmpty()) {
                    val baseSlot = visibleSlice.lastIndex
                    val fkSlice = futureKumo.take(futureSpaceBars)
                    var prevX = xOfSlot(baseSlot)
                    var prevA = visibleSlice.last().currentSpanA
                    var prevB = visibleSlice.last().currentSpanB
                    fkSlice.forEachIndexed { fIdx, fk ->
                        val nextX = xOfSlot(baseSlot + fIdx + 1)
                        val isBull = (prevA + fk.spanA) >= (prevB + fk.spanB)
                        val fill = if (isBull) Color(0x24089981) else Color(0x24F23645)
                        val path = Path().apply {
                            moveTo(prevX, yOf(prevA))
                            lineTo(nextX, yOf(fk.spanA))
                            lineTo(nextX, yOf(fk.spanB))
                            lineTo(prevX, yOf(prevB))
                            close()
                        }
                        drawPath(path, color = fill)
                        drawLine(Color(0x88089981), Offset(prevX, yOf(prevA)), Offset(nextX, yOf(fk.spanA)), 1.3f)
                        drawLine(Color(0x88F23645), Offset(prevX, yOf(prevB)), Offset(nextX, yOf(fk.spanB)), 1.3f)
                        prevX = nextX
                        prevA = fk.spanA
                        prevB = fk.spanB
                    }
                }

                // ۴. ═══ رسم ابزار پوزیشن تریدینگ‌ویو (TradingView Long / Short Position Tool) روی چارت ═══
                if (activeTrade != null) {
                    val entryY = yOf(activeTrade.entry)
                    val tpY = yOf(activeTrade.takeProfit)
                    val slY = yOf(activeTrade.stopLoss)

                    // پیدا کردن کندل زمان باز شدن معامله؛ اگر قبل از بازه دید باشد از شاخه چپ ابزار پوزیشن شروع می‌شود
                    val entrySlotIndex = visibleSlice.indexOfFirst { it.candle.time >= activeTrade.openedAt }
                    val boxStartX = when {
                        entrySlotIndex >= 0 -> xOfSlot(entrySlotIndex).coerceAtMost(w * 0.68f)
                        else -> w * 0.22f
                    }
                    val boxEndX = (w - 8f).coerceAtLeast(boxStartX + 80f)
                    val boxWidth = boxEndX - boxStartX

                    // الف) مستطیل سبز منطقه حد سود (TradingView Profit Zone — #089981)
                    val tpTop = min(entryY, tpY)
                    val tpBottom = max(entryY, tpY)
                    drawRect(
                        color = Color(0x3D089981),
                        topLeft = Offset(boxStartX, tpTop),
                        size = Size(boxWidth, (tpBottom - tpTop).coerceAtLeast(3f)),
                    )
                    // کادر دور منطقه سود
                    drawRect(
                        color = Color(0xCC089981),
                        topLeft = Offset(boxStartX, tpTop),
                        size = Size(boxWidth, (tpBottom - tpTop).coerceAtLeast(3f)),
                        style = Stroke(width = 2.0f),
                    )

                    // ب) مستطیل قرمز منطقه حد ضرر (TradingView Stop-Loss Zone — #F23645)
                    val slTop = min(entryY, slY)
                    val slBottom = max(entryY, slY)
                    drawRect(
                        color = Color(0x3DF23645),
                        topLeft = Offset(boxStartX, slTop),
                        size = Size(boxWidth, (slBottom - slTop).coerceAtLeast(3f)),
                    )
                    // کادر دور منطقه ضرر
                    drawRect(
                        color = Color(0xCCF23645),
                        topLeft = Offset(boxStartX, slTop),
                        size = Size(boxWidth, (slBottom - slTop).coerceAtLeast(3f)),
                        style = Stroke(width = 2.0f),
                    )

                    // ج) خط ورود طلایی/سفید (Entry Line) و دایره نقطه ورود
                    drawLine(
                        color = Color(0xFFF3C65F),
                        start = Offset(boxStartX, entryY),
                        end = Offset(boxEndX, entryY),
                        strokeWidth = 3.2f,
                    )
                    drawCircle(
                        color = Color(0xFFF3C65F),
                        radius = 7f,
                        center = Offset(boxStartX, entryY),
                    )
                    drawCircle(
                        color = Color(0xFF090C10),
                        radius = 3.5f,
                        center = Offset(boxStartX, entryY),
                    )

                    // د) خط قیمت لحظه‌ای (Live Price Line) داخل ابزار پوزیشن
                    val curP = livePrice ?: visibleSlice.lastOrNull()?.candle?.close
                    if (curP != null) {
                        val liveY = yOf(curP)
                        val isProfitNow = if (activeTrade.action == SignalAction.BUY) {
                            curP >= activeTrade.entry
                        } else {
                            curP <= activeTrade.entry
                        }
                        val liveColor = if (isProfitNow) Color(0xFF00E676) else Color(0xFFFF5252)
                        drawLine(
                            color = liveColor,
                            start = Offset(0f, liveY),
                            end = Offset(w, liveY),
                            strokeWidth = 2.0f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        )
                    }

                    // هـ) برچسب‌های حرفه‌ای تریدینگ‌ویو روی خطوط TP، Entry و SL
                    val slDist = abs(activeTrade.entry - activeTrade.stopLoss).coerceAtLeast(1e-8)
                    val tpDist = abs(activeTrade.takeProfit - activeTrade.entry).coerceAtLeast(1e-8)
                    val rr = (tpDist / slDist).coerceIn(0.1, 50.0)
                    val riskUsd = if (activeTrade.fixedRiskUsd > 0.0) activeTrade.fixedRiskUsd else 5.0
                    val rewardUsd = riskUsd * rr
                    val valuation = LiveTradeValuation.from(activeTrade, curP)
                    val pnlUsd = valuation?.netPnlUsd ?: 0.0
                    val pnlSign = if (pnlUsd >= 0.0) "+" else "-"

                    val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        textSize = 23f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                    }

                    fun drawTvBadge(
                        text: String,
                        anchorY: Float,
                        bgColorInt: Int,
                        alignRight: Boolean = true,
                    ) {
                        val padH = 14f
                        val padV = 7f
                        val textW = tagTextPaint.measureText(text)
                        val boxH = 34f
                        val left = if (alignRight) {
                            (w - textW - padH * 2f - 8f).coerceAtLeast(8f)
                        } else {
                            (boxStartX + 8f).coerceAtMost(w - textW - padH * 2f - 8f)
                        }
                        val top = (anchorY - boxH / 2f).coerceIn(4f, h - boxH - 4f)
                        val rect = RectF(left, top, left + textW + padH * 2f, top + boxH)
                        bgPaint.color = bgColorInt
                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 8f, 8f, bgPaint)
                        drawContext.canvas.nativeCanvas.drawText(
                            text,
                            left + padH,
                            top + boxH - padV - 2f,
                            tagTextPaint,
                        )
                    }

                    // تگ حد سود (TP)
                    drawTvBadge(
                        text = "TP: ${formatPriceFor(symbol, activeTrade.takeProfit)}  (+$${String.format(Locale.US, "%.2f", rewardUsd)} | ${String.format(Locale.US, "%.2f", rr)}R)",
                        anchorY = tpY,
                        bgColorInt = AndroidColor.argb(235, 8, 153, 129),
                        alignRight = true,
                    )
                    // تگ حد ضرر (SL)
                    drawTvBadge(
                        text = "SL: ${formatPriceFor(symbol, activeTrade.stopLoss)}  (-$${String.format(Locale.US, "%.2f", riskUsd)} | -1R)",
                        anchorY = slY,
                        bgColorInt = AndroidColor.argb(235, 242, 54, 69),
                        alignRight = true,
                    )
                    // تگ نقطه ورود و سود/زیان لحظه‌ای (Entry + Live PnL)
                    val dirTitle = if (activeTrade.action == SignalAction.BUY) "LONG" else "SHORT"
                    drawTvBadge(
                        text = "$dirTitle [${activeTrade.interval.label}] · Entry: ${formatPriceFor(symbol, activeTrade.entry)} · P/L: ${pnlSign}$${String.format(Locale.US, "%.2f", abs(pnlUsd))}",
                        anchorY = entryY,
                        bgColorInt = AndroidColor.argb(235, 30, 41, 59),
                        alignRight = false,
                    )
                }

                // ۵. رسم کندل‌های ژاپنی (Candlesticks)
                val bodyWidth = (slotW * 0.66f).coerceAtLeast(2.5f)
                visibleSlice.forEachIndexed { slot, item ->
                    val c = item.candle
                    val cx = xOfSlot(slot)
                    val isBull = c.close >= c.open
                    val candleColor = if (isBull) Color(0xFF089981) else Color(0xFFF23645)
                    val highY = yOf(c.high)
                    val lowY = yOf(c.low)
                    val openY = yOf(c.open)
                    val closeY = yOf(c.close)
                    drawLine(candleColor, Offset(cx, highY), Offset(cx, lowY), strokeWidth = 1.4f)
                    val topY = min(openY, closeY)
                    val botY = max(openY, closeY)
                    val rectH = (botY - topY).coerceAtLeast(2f)
                    drawRect(
                        color = candleColor,
                        topLeft = Offset(cx - bodyWidth / 2f, topY),
                        size = Size(bodyWidth, rectH),
                    )
                }

                // ۶. رسم خطوط Tenkan-sen (آبی)، Kijun-sen (طلایی) و Chikou Span (بنفش)
                for (i in 0 until visibleSlice.size - 1) {
                    val cur = visibleSlice[i]
                    val nxt = visibleSlice[i + 1]
                    val x1 = xOfSlot(i)
                    val x2 = xOfSlot(i + 1)
                    drawLine(Color(0xFF29B6F6), Offset(x1, yOf(cur.tenkan)), Offset(x2, yOf(nxt.tenkan)), strokeWidth = 2.0f)
                    drawLine(Color(0xFFFFB300), Offset(x1, yOf(cur.kijun)), Offset(x2, yOf(nxt.kijun)), strokeWidth = 2.2f)
                    if (cur.chikouPrice != null && nxt.chikouPrice != null) {
                        drawLine(Color(0xFFCE93D8), Offset(x1, yOf(cur.chikouPrice)), Offset(x2, yOf(nxt.chikouPrice)), strokeWidth = 1.6f)
                    }
                }

                // ۷. خطوط اهداف موجی هوسودا در صورتی که پوزیشن فعالی روی چارت نباشد
                if (activeTrade == null) {
                    val targetDash = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    targetN?.takeIf { it in padMin..padMax }?.let { tp ->
                        val ty = yOf(tp)
                        drawLine(Color(0xFF089981), Offset(0f, ty), Offset(w, ty), strokeWidth = 1.5f, pathEffect = targetDash)
                    }
                    targetV?.takeIf { it in padMin..padMax }?.let { tp ->
                        val ty = yOf(tp)
                        drawLine(Color(0x99089981), Offset(0f, ty), Offset(w, ty), strokeWidth = 1.2f, pathEffect = targetDash)
                    }
                    kijunStop?.takeIf { it in padMin..padMax }?.let { sl ->
                        val sy = yOf(sl)
                        drawLine(Color(0x99F23645), Offset(0f, sy), Offset(w, sy), strokeWidth = 1.2f, pathEffect = targetDash)
                    }
                }

                // ۸. خط نشانگر کندل انتخاب‌شده (Crosshair)
                val selSlot = inspectIdx - startIdx
                if (selSlot in visibleSlice.indices) {
                    val sx = xOfSlot(selSlot)
                    val sy = yOf(visibleSlice[selSlot].candle.close)
                    drawLine(Color(0x66FFFFFF), Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f, pathEffect = dashGrid)
                    drawLine(Color(0x66FFFFFF), Offset(0f, sy), Offset(w, sy), strokeWidth = 1f, pathEffect = dashGrid)
                    drawCircle(Color(0xFFFFD54F), radius = 4.5f, center = Offset(sx, sy), style = Stroke(width = 2f))
                }
            }
        }
    }
}

private fun resolveEmbedUnrestrictedTvTicker(appSymbol: String, fallbackTicker: String): String =
    when (V1Universe.normalize(appSymbol)) {
        "XAU/USD" -> "FX_IDC:XAUUSD"
        "XAG/USD" -> "FX_IDC:XAGUSD"
        "DOW" -> "FOREXCOM:DJI"
        "EUR/USD" -> "FX:EURUSD"
        "GBP/USD" -> "FX:GBPUSD"
        "USD/JPY" -> "FX:USDJPY"
        "EUR/JPY" -> "FX:EURJPY"
        "GBP/JPY" -> "FX:GBPJPY"
        "AUD/USD" -> "FX:AUDUSD"
        "USD/CHF" -> "FX:USDCHF"
        "BTC/USDT" -> "BINANCE:BTCUSDT"
        "ETH/USDT" -> "BINANCE:ETHUSDT"
        "SOL/USDT" -> "BINANCE:SOLUSDT"
        "BNB/USDT" -> "BINANCE:BNBUSDT"
        "XRP/USDT" -> "BINANCE:XRPUSDT"
        else -> fallbackTicker
    }

private fun buildReliableTradingViewWebHtml(
    appSymbol: String,
    tvTicker: String,
    timeframeLabel: String,
    series: IchimokuChartSeries,
    activeTrade: PaperTrade?,
    livePrice: Double?,
): String {
    val embedSymbol = resolveEmbedUnrestrictedTvTicker(appSymbol, tvTicker)
    val tvInterval = when (timeframeLabel) {
        "5m" -> "5"
        "15m" -> "15"
        "1H" -> "60"
        "4H" -> "240"
        else -> "15"
    }
    val barsJson = series.bars.takeLast(90).joinToString(",") { b ->
        val c = b.candle
        """{"t":${c.time},"o":${c.open},"h":${c.high},"l":${c.low},"c":${c.close},"v":${c.volume},"tk":${b.tenkan},"kj":${b.kijun},"sa":${b.currentSpanA},"sb":${b.currentSpanB}}"""
    }
    val tradeJson = if (activeTrade != null) {
        val isBuy = activeTrade.action == SignalAction.BUY
        val risk = if (activeTrade.fixedRiskUsd > 0.0) activeTrade.fixedRiskUsd else 5.0
        """{"side":"${if (isBuy) "LONG" else "SHORT"}","entry":${activeTrade.entry},"sl":${activeTrade.stopLoss},"tp":${activeTrade.takeProfit},"rr":${activeTrade.riskReward},"risk":$risk,"tf":"${activeTrade.interval.label}"}"""
    } else {
        "null"
    }
    val liveVal = livePrice ?: series.bars.lastOrNull()?.candle?.close ?: 0.0
    val binanceStreamPair = appSymbol.replace("/", "").lowercase(Locale.US)
    val isCryptoSymbol = appSymbol.endsWith("/USDT")

    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="referrer" content="no-referrer">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; -webkit-user-select: none; }
  html, body { width: 100%; height: 100%; background: #131722; color: #d1d4dc; font-family: -apple-system, BlinkMacSystemFont, "Trebuchet MS", Roboto, Ubuntu, sans-serif; overflow: hidden; }
  #tv_header { height: 36px; background: #1e222d; border-bottom: 1px solid #2a2e39; display: flex; align-items: center; justify-content: space-between; padding: 0 8px; font-size: 11px; }
  #tv_subbar { height: 26px; background: #171b26; border-bottom: 1px solid #2a2e39; display: flex; align-items: center; justify-content: space-between; padding: 0 8px; font-size: 10.5px; color: #9ca3af; }
  .tv-pill { background: #2a2e39; color: #d1d4dc; padding: 3px 7px; border-radius: 4px; font-weight: 600; cursor: pointer; border: 1px solid #363a45; font-size: 10.5px; }
  .tv-pill.active { background: #2962ff; color: #fff; border-color: #2962ff; }
  #wrap { position: relative; width: 100%; height: calc(100% - 62px); }
  canvas { width: 100%; height: 100%; display: block; }
</style>
</head>
<body>
<div id="tv_header">
  <div style="display:flex;align-items:center;gap:6px;">
    <span style="color:#f3c65f;font-weight:700;">TradingView Pro</span>
    <span>$appSymbol</span>
    <span style="color:#38bdf8;font-weight:700;">$timeframeLabel</span>
    <span id="ws_status" style="color:#089981;font-size:10px;">● Live</span>
  </div>
  <div style="display:flex;align-items:center;gap:4px;">
    <button id="btn_canvas" class="tv-pill active" onclick="switchMode('canvas')">ایچیموکو + TP/SL</button>
    <button id="btn_pro" class="tv-pill" onclick="switchMode('pro')">وب‌ویجت Pro (RSI+EMA+Vol)</button>
    <button id="btn_ha" class="tv-pill" onclick="toggleHeikin()">HA</button>
  </div>
</div>
<div id="tv_subbar">
  <div id="ohlc_bar">O: — · H: — · L: — · C: —</div>
  <div id="ind_bar" style="color:#f3c65f;">TK / KJ / Kumo / RSI(14)</div>
</div>
<div id="wrap">
  <canvas id="tvCanvas"></canvas>
</div>
<script>
const BARS = [$barsJson];
const TRADE = $tradeJson;
let LIVE_PRICE = $liveVal;
const EMBED_SYMBOL = "$embedSymbol";
const TV_INTERVAL = "$tvInterval";
const IS_CRYPTO = $isCryptoSymbol;
const STREAM_PAIR = "$binanceStreamPair";

let currentMode = 'canvas';
let useHeikinAshi = false;
let viewCount = 54;
let panBars = 0;
let dragStartX = null;
let dragStartPan = 0;
let crossX = null;
let crossY = null;

function switchMode(mode) {
  currentMode = mode;
  document.getElementById('btn_canvas').classList.toggle('active', mode === 'canvas');
  document.getElementById('btn_pro').classList.toggle('active', mode === 'pro');
  drawChart();
}

function toggleHeikin() {
  useHeikinAshi = !useHeikinAshi;
  document.getElementById('btn_ha').classList.toggle('active', useHeikinAshi);
  drawChart();
}

// Connect live Binance WebSocket stream for real-time sub-second candle updates (Zero 403!)
if (IS_CRYPTO && window.WebSocket) {
  try {
    const ws = new WebSocket("wss://stream.binance.com:9443/ws/" + STREAM_PAIR + "@MiniTicker");
    ws.onmessage = (ev) => {
      try {
        const d = JSON.parse(ev.data);
        const p = parseFloat(d.c);
        if (p > 0 && BARS.length > 0) {
          LIVE_PRICE = p;
          const last = BARS[BARS.length - 1];
          last.c = p;
          if (p > last.h) last.h = p;
          if (p < last.l) last.l = p;
          document.getElementById('ws_status').textContent = '● Live ' + fmt(p);
          drawChart();
        }
      } catch (_) {}
    };
  } catch (_) {}
}

const canvas = document.getElementById('tvCanvas');
const ctx = canvas.getContext('2d');

function fmt(p) {
  if (!p && p !== 0) return '';
  const a = Math.abs(p);
  const d = a >= 100 ? 2 : (a >= 10 ? 3 : (a >= 1 ? 4 : 5));
  return Number(p).toFixed(d);
}

function computeRsi14(arr) {
  const res = new Array(arr.length).fill(50);
  if (arr.length < 2) return res;
  let avgGain = 0, avgLoss = 0;
  const period = Math.min(14, arr.length - 1);
  for (let i = 1; i <= period; i++) {
    const chg = arr[i].c - arr[i - 1].c;
    if (chg >= 0) avgGain += chg; else avgLoss -= chg;
  }
  avgGain /= period;
  avgLoss /= period;
  for (let i = period; i < arr.length; i++) {
    if (i > period) {
      const chg = arr[i].c - arr[i - 1].c;
      avgGain = (avgGain * (period - 1) + (chg > 0 ? chg : 0)) / period;
      avgLoss = (avgLoss * (period - 1) + (chg < 0 ? -chg : 0)) / period;
    }
    const rs = avgLoss <= 1e-9 ? 100 : avgGain / avgLoss;
    res[i] = 100 - (100 / (1 + rs));
  }
  return res;
}

function computeEma(arr, period) {
  const k = 2 / (period + 1);
  const out = [];
  let ema = arr[0] ? arr[0].c : 0;
  for (let i = 0; i < arr.length; i++) {
    ema = arr[i].c * k + ema * (1 - k);
    out.push(ema);
  }
  return out;
}

function drawChart() {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (w <= 0 || h <= 0) return;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  ctx.fillStyle = '#131722';
  ctx.fillRect(0, 0, w, h);

  if (!BARS.length) {
    ctx.fillStyle = '#d1d4dc';
    ctx.font = '14px sans-serif';
    ctx.fillText('در حال دریافت کندل‌های ' + EMBED_SYMBOL + '...', 24, h / 2);
    return;
  }

  const rsiAll = computeRsi14(BARS);
  const ema20All = computeEma(BARS, 20);
  const ema50All = computeEma(BARS, 50);

  const rightAxisW = 68;
  const chartW = w - rightAxisW;
  const mainH = currentMode === 'pro' ? Math.floor(h * 0.72) : h;
  const oscTop = mainH;
  const oscH = h - mainH;

  const endIdx = Math.max(10, Math.min(BARS.length - 1, BARS.length - 1 - Math.round(panBars)));
  const startIdx = Math.max(0, endIdx - viewCount + 1);
  const slice = BARS.slice(startIdx, endIdx + 1);
  const rsiSlice = rsiAll.slice(startIdx, endIdx + 1);
  const ema20Slice = ema20All.slice(startIdx, endIdx + 1);
  const ema50Slice = ema50All.slice(startIdx, endIdx + 1);

  const totalSlots = viewCount + 8;
  const slotW = chartW / totalSlots;

  let minP = Infinity, maxP = -Infinity, maxVol = 1;
  slice.forEach((b, idx) => {
    minP = Math.min(minP, b.l, b.tk, b.kj, b.sa, b.sb, ema20Slice[idx], ema50Slice[idx]);
    maxP = Math.max(maxP, b.h, b.tk, b.kj, b.sa, b.sb, ema20Slice[idx], ema50Slice[idx]);
    if ((b.v || 0) > maxVol) maxVol = b.v;
  });
  if (TRADE) {
    minP = Math.min(minP, TRADE.entry, TRADE.sl, TRADE.tp);
    maxP = Math.max(maxP, TRADE.entry, TRADE.sl, TRADE.tp);
  }
  const span = Math.max(1e-6, maxP - minP);
  const padMin = minP - span * 0.10;
  const padMax = maxP + span * 0.10;
  const padRange = Math.max(1e-6, padMax - padMin);

  const yOf = p => mainH - ((p - padMin) / padRange) * mainH;
  const xOf = i => (i + 0.5) * slotW;

  // Update header OHLC bar
  const inspectIdx = crossX !== null
    ? Math.max(0, Math.min(slice.length - 1, Math.floor(crossX / slotW)))
    : slice.length - 1;
  const sb = slice[inspectIdx] || slice[slice.length - 1];
  const sRsi = rsiSlice[inspectIdx] || 50;
  document.getElementById('ohlc_bar').textContent =
    'O: ' + fmt(sb.o) + ' · H: ' + fmt(sb.h) + ' · L: ' + fmt(sb.l) + ' · C: ' + fmt(sb.c);
  document.getElementById('ind_bar').textContent =
    'TK: ' + fmt(sb.tk) + ' · KJ: ' + fmt(sb.kj) + ' · RSI(14): ' + sRsi.toFixed(1);

  // Grid & Right Price Axis
  ctx.strokeStyle = '#1f2937';
  ctx.lineWidth = 1;
  ctx.font = '10.5px sans-serif';
  ctx.fillStyle = '#9ca3af';
  for (let g = 1; g <= 5; g++) {
    const gy = (mainH / 6) * g;
    const gp = padMax - (g / 6) * padRange;
    ctx.beginPath();
    ctx.moveTo(0, gy);
    ctx.lineTo(chartW, gy);
    ctx.stroke();
    ctx.fillText(fmt(gp), chartW + 5, gy + 4);
  }
  ctx.strokeStyle = '#2a2e39';
  ctx.beginPath();
  ctx.moveTo(chartW, 0);
  ctx.lineTo(chartW, h);
  ctx.stroke();

  // Volume Histogram at bottom of main pane
  const volMaxH = mainH * 0.18;
  slice.forEach((b, i) => {
    const vh = ((b.v || 1) / maxVol) * volMaxH;
    ctx.fillStyle = b.c >= b.o ? 'rgba(8,153,129,0.22)' : 'rgba(242,54,69,0.22)';
    ctx.fillRect(xOf(i) - slotW * 0.3, mainH - vh, slotW * 0.6, vh);
  });

  // Kumo Cloud
  for (let i = 0; i < slice.length - 1; i++) {
    const c1 = slice[i], c2 = slice[i + 1];
    const x1 = xOf(i), x2 = xOf(i + 1);
    ctx.fillStyle = (c1.sa + c2.sa >= c1.sb + c2.sb) ? 'rgba(8,153,129,0.18)' : 'rgba(242,54,69,0.18)';
    ctx.beginPath();
    ctx.moveTo(x1, yOf(c1.sa));
    ctx.lineTo(x2, yOf(c2.sa));
    ctx.lineTo(x2, yOf(c2.sb));
    ctx.lineTo(x1, yOf(c1.sb));
    ctx.closePath();
    ctx.fill();
  }

  // TradingView Long/Short Position Tool (TP Green Box + SL Red Box)
  if (TRADE) {
    const ey = yOf(TRADE.entry);
    const tpy = yOf(TRADE.tp);
    const sly = yOf(TRADE.sl);
    const bx = chartW * 0.25;
    const bw = chartW - bx - 4;

    const tpTop = Math.min(ey, tpy), tpH = Math.max(3, Math.abs(tpy - ey));
    ctx.fillStyle = 'rgba(8,153,129,0.26)';
    ctx.fillRect(bx, tpTop, bw, tpH);
    ctx.strokeStyle = '#089981';
    ctx.lineWidth = 1.6;
    ctx.strokeRect(bx, tpTop, bw, tpH);

    const slTop = Math.min(ey, sly), slH = Math.max(3, Math.abs(sly - ey));
    ctx.fillStyle = 'rgba(242,54,69,0.26)';
    ctx.fillRect(bx, slTop, bw, slH);
    ctx.strokeStyle = '#f23645';
    ctx.lineWidth = 1.6;
    ctx.strokeRect(bx, slTop, bw, slH);

    ctx.strokeStyle = '#f3c65f';
    ctx.lineWidth = 2.4;
    ctx.beginPath();
    ctx.moveTo(bx, ey);
    ctx.lineTo(chartW, ey);
    ctx.stroke();

    const drawTag = (y, txt, bg) => {
      ctx.fillStyle = bg;
      ctx.fillRect(chartW, Math.max(0, Math.min(mainH - 18, y - 9)), rightAxisW, 18);
      ctx.fillStyle = '#fff';
      ctx.font = 'bold 10px sans-serif';
      ctx.fillText(txt, chartW + 4, Math.max(12, Math.min(mainH - 5, y + 4)));
    };
    drawTag(tpy, fmt(TRADE.tp), '#089981');
    drawTag(ey, fmt(TRADE.entry), '#d97706');
    drawTag(sly, fmt(TRADE.sl), '#f23645');
  }

  // Candlesticks or Heikin-Ashi
  const bw = Math.max(2.5, slotW * 0.66);
  let prevHaO = slice[0].o, prevHaC = slice[0].c;
  slice.forEach((b, i) => {
    const cx = xOf(i);
    let o = b.o, h = b.h, l = b.l, c = b.c;
    if (useHeikinAshi) {
      c = (b.o + b.h + b.l + b.c) / 4;
      o = i === 0 ? (b.o + b.c) / 2 : (prevHaO + prevHaC) / 2;
      h = Math.max(b.h, o, c);
      l = Math.min(b.l, o, c);
      prevHaO = o;
      prevHaC = c;
    }
    const bull = c >= o;
    ctx.strokeStyle = bull ? '#089981' : '#f23645';
    ctx.fillStyle = bull ? '#089981' : '#f23645';
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    ctx.moveTo(cx, yOf(h));
    ctx.lineTo(cx, yOf(l));
    ctx.stroke();
    const top = Math.min(yOf(o), yOf(c));
    const bh = Math.max(2, Math.abs(yOf(c) - yOf(o)));
    ctx.fillRect(cx - bw / 2, top, bw, bh);
  });

  // Tenkan & Kijun + EMA 20/50 in Pro Mode
  const drawLineSeries = (vals, color, width) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = width || 1.6;
    ctx.beginPath();
    vals.forEach((v, i) => {
      if (i === 0) ctx.moveTo(xOf(i), yOf(v));
      else ctx.lineTo(xOf(i), yOf(v));
    });
    ctx.stroke();
  };
  drawLineSeries(slice.map(b => b.tk), '#2962ff', 1.8);
  drawLineSeries(slice.map(b => b.kj), '#ff9800', 1.8);
  if (currentMode === 'pro') {
    drawLineSeries(ema20Slice, '#a855f7', 1.4);
    drawLineSeries(ema50Slice, '#ec4899', 1.4);
  }

  // Sub-pane RSI(14) in Pro Mode
  if (currentMode === 'pro' && oscH > 24) {
    ctx.strokeStyle = '#2a2e39';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(0, oscTop);
    ctx.lineTo(w, oscTop);
    ctx.stroke();

    const yRsi = r => oscTop + oscH - ((r / 100) * (oscH - 8)) - 4;
    // 70 / 30 bands
    ctx.fillStyle = 'rgba(56,189,248,0.07)';
    ctx.fillRect(0, yRsi(70), chartW, yRsi(30) - yRsi(70));
    ctx.strokeStyle = 'rgba(156,163,175,0.35)';
    ctx.setLineDash([4, 4]);
    [70, 50, 30].forEach(lvl => {
      ctx.beginPath();
      ctx.moveTo(0, yRsi(lvl));
      ctx.lineTo(chartW, yRsi(lvl));
      ctx.stroke();
      ctx.fillStyle = '#9ca3af';
      ctx.fillText(String(lvl), chartW + 6, yRsi(lvl) + 3);
    });
    ctx.setLineDash([]);

    ctx.strokeStyle = '#38bdf8';
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    rsiSlice.forEach((r, i) => {
      if (i === 0) ctx.moveTo(xOf(i), yRsi(r));
      else ctx.lineTo(xOf(i), yRsi(r));
    });
    ctx.stroke();
    ctx.fillStyle = '#38bdf8';
    ctx.font = 'bold 10px sans-serif';
    ctx.fillText('RSI(14): ' + (rsiSlice[rsiSlice.length - 1] || 50).toFixed(1), 8, oscTop + 14);
  }

  // Interactive Crosshair
  if (crossX !== null && crossY !== null && crossX < chartW) {
    ctx.strokeStyle = 'rgba(209,212,220,0.45)';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(crossX, 0);
    ctx.lineTo(crossX, h);
    ctx.moveTo(0, crossY);
    ctx.lineTo(chartW, crossY);
    ctx.stroke();
    ctx.setLineDash([]);
  }
}

canvas.addEventListener('pointerdown', e => {
  dragStartX = e.clientX;
  dragStartPan = panBars;
  const r = canvas.getBoundingClientRect();
  crossX = e.clientX - r.left;
  crossY = e.clientY - r.top;
  drawChart();
});
canvas.addEventListener('pointermove', e => {
  const r = canvas.getBoundingClientRect();
  crossX = e.clientX - r.left;
  crossY = e.clientY - r.top;
  if (dragStartX !== null) {
    const dx = e.clientX - dragStartX;
    const slotW = (canvas.clientWidth - 68) / (viewCount + 8);
    panBars = Math.max(0, Math.min(BARS.length - 15, dragStartPan + dx / Math.max(4, slotW)));
  }
  drawChart();
});
window.addEventListener('pointerup', () => { dragStartX = null; });
window.addEventListener('resize', drawChart);
setTimeout(drawChart, 30);
</script>
</body>
</html>"""
}

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"

