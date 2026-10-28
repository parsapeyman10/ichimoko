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

    var useTradingViewWeb by rememberSaveable { mutableStateOf(true) }
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
        // ── ۱. نوار ابزار فشرده بالای چارت (نماد، تایم‌فریم، سوییچ ویجت رسمی TradingView / چارت ایچیموکو + ابر آینده) ──
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
                        color = if (selectedTfLabel == tf) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable {
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
                    text = "🌐 ویجت رسمی TV",
                    color = if (useTradingViewWeb) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { useTradingViewWeb = true },
                )
                Pill(
                    text = "☁️ ایچیموکو (+26 آینده)",
                    color = if (!useTradingViewWeb) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.clickable { useTradingViewWeb = false },
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

        // ── ۵. نمایش چارت (ویجت رسمی TradingView با اندیکاتور ایچیموکو یا چارت تخصصی ایچیموکو + ابر آینده ۲۶ کندلی) ──
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
            val tvIntervalCode = remember(selectedTfLabel) {
                when (selectedTfLabel) {
                    "5m" -> "5"
                    "15m" -> "15"
                    "1H" -> "60"
                    "4H" -> "240"
                    else -> "15"
                }
            }
            val officialHtml = remember(tvTicker, tvIntervalCode) {
                TradingViewEmbed.htmlForInterval(
                    symbol = tvTicker,
                    intervalCode = tvIntervalCode,
                    tenkan = 9,
                    kijun = 26,
                    spanB = 52,
                ) ?: TradingViewEmbed.html(tvTicker)
            }
            if (!route.ready) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "شبکهٔ گوشی آماده نیست؛ اینترنت یا VPN سیستم را بررسی کنید",
                        modifier = Modifier.padding(20.dp),
                        color = AurumColors.Gold,
                    )
                }
            } else if (officialHtml == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "نماد TradingView معتبر نیست؛ چارت نماد دیگری جایگزین نمی‌شود",
                        modifier = Modifier.padding(20.dp),
                        color = AurumColors.Gold,
                    )
                }
            } else {
                key(tvTicker, tvIntervalCode, reloadCount, route.revision) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setBackgroundColor(AndroidColor.rgb(11, 14, 19))
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
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    userAgentString = MOBILE_USER_AGENT
                                    textZoom = 100
                                }
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                loadDataWithBaseURL(
                                    "https://s3.tradingview.com",
                                    officialHtml,
                                    "text/html",
                                    "UTF-8",
                                    "https://s3.tradingview.com",
                                )
                            }
                        },
                        onReset = null,
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.destroy()
                        },
                    )
                }
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

    var focusFutureKumo by rememberSaveable { mutableStateOf(true) }
    var visibleBarsCount by rememberSaveable { mutableIntStateOf(34) }
    var panOffsetBars by rememberSaveable(symbol, timeframe) { mutableFloatStateOf(0f) }
    var fitTradeTpSl by rememberSaveable(symbol) { mutableStateOf(true) }
    var inspectedIndex by remember(bars) { mutableStateOf<Int?>(bars.lastIndex.takeIf { it >= 0 }) }
    var inspectedFutureStep by remember(bars) { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.background(AurumColors.ChartBg)) {
        if (bars.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("در حال بارگذاری کندل‌های $symbol ($timeframe)...", color = AurumColors.Gold)
            }
            return@Column
        }

        val inspectIdx = (inspectedIndex ?: bars.lastIndex).coerceIn(0, bars.lastIndex)
        val sel = bars[inspectIdx]

        // محاسبه ابر آینده ۲۶ کندلی (حتی اگر کاربر چارت را به عقب اسکرول کرده باشد)
        val endIdx = (bars.lastIndex - panOffsetBars.roundToInt()).coerceIn(10, bars.lastIndex)
        val startIdx = (endIdx - visibleBarsCount + 1).coerceAtLeast(0)
        val visibleSlice = bars.subList(startIdx, endIdx + 1)

        val activeFutureKumo = remember(bars, futureKumo, endIdx) {
            if (endIdx == bars.lastIndex && futureKumo.isNotEmpty()) {
                futureKumo
            } else {
                // اگر چارت روی کندل قبلی پان شده، ۲۶ دوره ابر آینده همان کندل را استخراج کن
                val disp = 25
                val list = mutableListOf<com.aurum.edge.core.FutureKumoPoint>()
                for (step in 1..26) {
                    val targetIdx = endIdx + step
                    if (targetIdx <= bars.lastIndex) {
                        val b = bars[targetIdx]
                        list += com.aurum.edge.core.FutureKumoPoint(stepAhead = step, spanA = b.currentSpanA, spanB = b.currentSpanB)
                    } else {
                        val fkIdx = targetIdx - bars.lastIndex - 1
                        val fk = futureKumo.getOrNull(fkIdx) ?: futureKumo.lastOrNull()
                        if (fk != null) {
                            list += com.aurum.edge.core.FutureKumoPoint(stepAhead = step, spanA = fk.spanA, spanB = fk.spanB)
                        }
                    }
                }
                list
            }
        }

        val futureTip = activeFutureKumo.lastOrNull()
        val futureBullish = futureTip != null && futureTip.spanA >= futureTip.spanB
        val futureTwistStep = remember(activeFutureKumo, visibleSlice) {
            var prevDiff = visibleSlice.lastOrNull()?.let { it.currentSpanA - it.currentSpanB } ?: 0.0
            var twistAt: Int? = null
            for (fk in activeFutureKumo) {
                val curDiff = fk.spanA - fk.spanB
                if ((prevDiff > 0.0 && curDiff < 0.0) || (prevDiff < 0.0 && curDiff > 0.0)) {
                    twistAt = fk.stepAhead
                    break
                }
                prevDiff = curDiff
            }
            twistAt
        }

        // ── نوار اطلاعات کندل، ۵ خط ایچیموکو، وضعیت ابر آینده (+26) و کنترل‌های زوم ──
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
                    Pill(
                        text = if (focusFutureKumo) "☁️ ابر آینده (+26) ✓" else "☁️ ابر آینده (+26)",
                        color = if (focusFutureKumo) AurumColors.Gold else AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            focusFutureKumo = !focusFutureKumo
                            visibleBarsCount = if (focusFutureKumo) 32 else 54
                            panOffsetBars = 0f
                        },
                    )
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
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount - 10).coerceAtLeast(18) },
                    )
                    Pill(
                        text = "➖",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount + 10).coerceAtMost(95) },
                    )
                    Pill(
                        text = "⏭",
                        color = AurumColors.Gold,
                        modifier = Modifier.clickable {
                            panOffsetBars = 0f
                            inspectedIndex = bars.lastIndex
                            inspectedFutureStep = null
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

            // نوار اختصاصی اطلاعات «ابر آینده ۲۶ کندل جلوتر (Future Kumo +26)»
            if (futureTip != null) {
                val inspectedFk = inspectedFutureStep?.let { step ->
                    activeFutureKumo.firstOrNull { it.stepAhead == step }
                } ?: futureTip
                val stepLabel = inspectedFutureStep?.let { "+$it کندل جلوتر" } ?: "+26 کندل آینده"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0E1724))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "☁️ ابر آینده ($stepLabel): ${if (inspectedFk.spanA >= inspectedFk.spanB) "🟢 صعودی" else "🔴 نزولی"}${futureTwistStep?.let { " · ⚡ پیچش در +$it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (inspectedFk.spanA >= inspectedFk.spanB) AurumColors.Green else AurumColors.Red,
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = "SSA: ${formatPrice(inspectedFk.spanA)} | SSB: ${formatPrice(inspectedFk.spanB)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AurumColors.Gold,
                        )
                    }
                }
            }
        }

        // ── بوم گرافیکی کندل‌ها + کل ۲۶ کندل ابر آینده (Future Kumo +26) + ابزار پوزیشن تریدینگ‌ویو ──
        val futureSpaceBars = if (focusFutureKumo) 30 else 27
        val totalSlots = visibleSlice.size + futureSpaceBars

        var minP = Double.POSITIVE_INFINITY
        var maxP = Double.NEGATIVE_INFINITY
        visibleSlice.forEach { b ->
            minP = minOf(minP, b.candle.low, b.tenkan, b.kijun, b.currentSpanA, b.currentSpanB)
            maxP = maxOf(maxP, b.candle.high, b.tenkan, b.kijun, b.currentSpanA, b.currentSpanB)
        }
        activeFutureKumo.forEach { fk ->
            minP = minOf(minP, fk.spanA, fk.spanB)
            maxP = maxOf(maxP, fk.spanA, fk.spanB)
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
                    .pointerInput(bars.size, visibleBarsCount, focusFutureKumo) {
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
                                inspectedFutureStep = null
                            } else {
                                val fStep = tappedSlot - visibleSlice.lastIndex
                                if (fStep in 1..26) {
                                    inspectedFutureStep = fStep
                                }
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
                    val cloudFill = if (isBullCloud) Color(0x38089981) else Color(0x38F23645)
                    val path = Path().apply {
                        moveTo(x1, yOf(cur.currentSpanA))
                        lineTo(x2, yOf(nxt.currentSpanA))
                        lineTo(x2, yOf(nxt.currentSpanB))
                        lineTo(x1, yOf(cur.currentSpanB))
                        close()
                    }
                    drawPath(path, color = cloudFill)
                    drawLine(Color(0xAA089981), Offset(x1, yOf(cur.currentSpanA)), Offset(x2, yOf(nxt.currentSpanA)), 1.6f)
                    drawLine(Color(0xAAF23645), Offset(x1, yOf(cur.currentSpanB)), Offset(x2, yOf(nxt.currentSpanB)), 1.6f)
                }

                // ۳. ═══ رسم کامل و پروضوح ابر آینده (Future Kumo Cloud — تمام ۲۶ دوره جلوتر در سمت راست) ═══
                if (activeFutureKumo.isNotEmpty() && visibleSlice.isNotEmpty()) {
                    val baseSlot = visibleSlice.lastIndex
                    val dividerX = xOfSlot(baseSlot) + slotW * 0.5f

                    // الف) هایلایت پس‌زمینه منطقه ۲۶ کندل آینده و خط جداکننده «اکنون | ابر آینده (+26)»
                    drawRect(
                        color = Color(0x161E3A5F),
                        topLeft = Offset(dividerX, 0f),
                        size = Size((w - dividerX).coerceAtLeast(0f), h),
                    )
                    drawLine(
                        color = Color(0x99F3C65F),
                        start = Offset(dividerX, 0f),
                        end = Offset(dividerX, h),
                        strokeWidth = 1.6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                    )

                    val futureHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.argb(225, 243, 198, 95)
                        textSize = 21f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "Future Kumo (+26) ☁️",
                        (dividerX + 8f).coerceAtMost(w - 180f),
                        26f,
                        futureHeaderPaint,
                    )

                    // ب) رسم چندضلعی‌ها، هاشور عمودی کلاسیک ایچیموکو و خطوط ضخیم SSA / SSB آینده
                    var prevX = xOfSlot(baseSlot)
                    var prevA = visibleSlice.last().currentSpanA
                    var prevB = visibleSlice.last().currentSpanB
                    activeFutureKumo.forEachIndexed { fIdx, fk ->
                        val nextX = xOfSlot(baseSlot + fIdx + 1)
                        val isBull = (prevA + fk.spanA) >= (prevB + fk.spanB)
                        val fill = if (isBull) Color(0x55089981) else Color(0x55F23645)
                        val hatchColor = if (isBull) Color(0x8800E676) else Color(0x88FF5252)
                        val path = Path().apply {
                            moveTo(prevX, yOf(prevA))
                            lineTo(nextX, yOf(fk.spanA))
                            lineTo(nextX, yOf(fk.spanB))
                            lineTo(prevX, yOf(prevB))
                            close()
                        }
                        drawPath(path, color = fill)
                        // خطوط هاشور عمودی سبک ایچیموکو ژاپنی برای تفکیک واضح ابر آینده
                        drawLine(
                            color = hatchColor,
                            start = Offset(nextX, yOf(fk.spanA)),
                            end = Offset(nextX, yOf(fk.spanB)),
                            strokeWidth = 1.2f,
                        )
                        // خطوط مرزی پررنگ Senkou Span A (سبز) و Senkou Span B (قرمز) در آینده
                        drawLine(Color(0xFF00E676), Offset(prevX, yOf(prevA)), Offset(nextX, yOf(fk.spanA)), 2.5f)
                        drawLine(Color(0xFFFF5252), Offset(prevX, yOf(prevB)), Offset(nextX, yOf(fk.spanB)), 2.5f)

                        // ج) علامت‌گذاری نقطه پیچش ابر آینده (Kumo Twist)
                        val wasBull = prevA >= prevB
                        val nowBull = fk.spanA >= fk.spanB
                        if (fIdx > 0 && wasBull != nowBull) {
                            val twistY = yOf((fk.spanA + fk.spanB) / 2.0)
                            drawCircle(Color(0xFFFFD54F), radius = 6.5f, center = Offset(nextX, twistY))
                            drawCircle(Color(0xFF090C10), radius = 3.0f, center = Offset(nextX, twistY))
                        }

                        prevX = nextX
                        prevA = fk.spanA
                        prevB = fk.spanB
                    }

                    // د) برچسب قیمت نوک ابر آینده در +26 کندل جلوتر (SSA+26 و SSB+26)
                    val lastFk = activeFutureKumo.last()
                    val tipX = xOfSlot(baseSlot + activeFutureKumo.size)
                    val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        textSize = 20f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                    val tipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

                    fun drawFutureTipTag(label: String, priceVal: Double, bgInt: Int) {
                        val y = yOf(priceVal).coerceIn(18f, h - 18f)
                        val txt = "$label: ${formatPriceFor(symbol, priceVal)}"
                        val tw = tipPaint.measureText(txt)
                        val left = (w - tw - 18f).coerceAtLeast(dividerX + 4f)
                        val rect = RectF(left, y - 13f, left + tw + 12f, y + 13f)
                        tipBgPaint.color = bgInt
                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 6f, 6f, tipBgPaint)
                        drawContext.canvas.nativeCanvas.drawText(txt, left + 6f, y + 6f, tipPaint)
                    }

                    drawCircle(Color(0xFF00E676), radius = 4.5f, center = Offset(tipX.coerceAtMost(w - 6f), yOf(lastFk.spanA)))
                    drawCircle(Color(0xFFFF5252), radius = 4.5f, center = Offset(tipX.coerceAtMost(w - 6f), yOf(lastFk.spanB)))
                    drawFutureTipTag("SSA+26", lastFk.spanA, AndroidColor.argb(220, 8, 153, 129))
                    drawFutureTipTag("SSB+26", lastFk.spanB, AndroidColor.argb(220, 242, 54, 69))

                    // اگر کاربر روی یکی از کندل‌های ابر آینده (+1..+26) کلیک کرده باشد، خط نشانگر آینده را رسم کن
                    inspectedFutureStep?.let { fStep ->
                        val fk = activeFutureKumo.firstOrNull { it.stepAhead == fStep }
                        if (fk != null) {
                            val fx = xOfSlot(baseSlot + fStep)
                            val fy = yOf((fk.spanA + fk.spanB) / 2.0)
                            drawLine(Color(0xAAFFD54F), Offset(fx, 0f), Offset(fx, h), strokeWidth = 1.5f, pathEffect = dashGrid)
                            drawCircle(Color(0xFFFFD54F), radius = 5.5f, center = Offset(fx, fy), style = Stroke(width = 2.2f))
                        }
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

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"


