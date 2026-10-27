package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.IchimokuChartSeries
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AppNetwork
import com.aurum.edge.data.ChartSymbolChoices
import com.aurum.edge.data.LiveQuoteFetcher
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(
    initialTicker: String,
    onSelectTicker: (String) -> Unit,
) {
    val route by AppNetwork.route.collectAsStateWithLifecycle()
    var useTradingViewWeb by rememberSaveable { mutableStateOf(false) }
    var selectedTfLabel by rememberSaveable { mutableStateOf("15m") }
    var showPicker by remember { mutableStateOf(false) }
    var reloadCount by remember { mutableIntStateOf(0) }

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
                com.aurum.edge.domain.SignalAction.BUY
            } else {
                com.aurum.edge.domain.SignalAction.SELL
            }
            IchimokuStrategyEngine.computeHosodaWave(candles, dir, latestSnap)
        } else null
    }

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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

        // ── ۲. نوار انتخاب سریع ۱۵ نماد ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            V1Universe.allSymbols.forEach { sym ->
                val active = sym == appSymbol
                Pill(
                    text = sym,
                    color = if (active) AurumColors.Gold else AurumColors.TextMuted,
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

        // ── ۳. نمایش چارت (پیش‌فرض: چارت بومی سریع ایچیموکو؛ قابلیت سوییچ به TradingView) ──
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
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            val html = remember(tvTicker) { TradingViewEmbed.html(tvTicker) }
            if (!route.ready) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("شبکه آماده نیست؛ از «چارت ایچیموکو ⚡» استفاده کنید", color = AurumColors.Gold)
                }
            } else if (html == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("نماد معتبر نیست", color = AurumColors.Gold)
                }
            } else {
                key(tvTicker, reloadCount, route.revision) {
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
                                    html,
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
    modifier: Modifier = Modifier,
) {
    val bars = series.bars
    val futureKumo = series.futureKumo

    var visibleBarsCount by rememberSaveable { mutableIntStateOf(56) }
    var panOffsetBars by rememberSaveable(symbol, timeframe) { mutableFloatStateOf(0f) }
    var inspectedIndex by remember(bars) { mutableStateOf<Int?>(bars.lastIndex.takeIf { it >= 0 }) }

    Column(modifier = modifier.background( Color(0xFF0B0E13))) {
        if (bars.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("در حال بارگذاری کندل‌های $symbol ($timeframe)...", color = AurumColors.Gold)
            }
            return@Column
        }

        val inspectIdx = (inspectedIndex ?: bars.lastIndex).coerceIn(0, bars.lastIndex)
        val sel = bars[inspectIdx]

        // ── نوار اطلاعات کندل و ۵ خط ایچیموکو در بالای بوم چارت ────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.Surface)
                .border(0.5.dp, AurumColors.TextMuted.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$symbol · $timeframe · قیمت: ${formatPrice(sel.candle.close)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (sel.candle.close >= sel.candle.open) AurumColors.Green else AurumColors.Red,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = "➕ زوم",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount - 12).coerceAtLeast(24) },
                    )
                    Pill(
                        text = "➖",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { visibleBarsCount = (visibleBarsCount + 12).coerceAtMost(100) },
                    )
                    Pill(
                        text = "⏭ انتها",
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

        // ── بوم گرافیکی کندل‌ها + ابر کومو + ابر آینده ۲۶ دوره + خطوط ایچیموکو ──
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
        val spanP = (maxP - minP).coerceAtLeast(1e-6)
        val padMin = minP - spanP * 0.08
        val padMax = maxP + spanP * 0.08
        val padRange = (padMax - padMin).coerceAtLeast(1e-6)

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

            // ۱. خطوط شبکه افقی (Grid)
            val dashGrid = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
            for (g in 1..4) {
                val gy = h * (g / 5f)
                drawLine(
                    color = Color(0xFF1E2430),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f,
                    pathEffect = dashGrid,
                )
            }

            // ۲. رسم ابر کوموی فعلی (Current Kumo Cloud)
            for (i in 0 until visibleSlice.size - 1) {
                val cur = visibleSlice[i]
                val nxt = visibleSlice[i + 1]
                val x1 = xOfSlot(i)
                val x2 = xOfSlot(i + 1)
                val isBullCloud = (cur.currentSpanA + nxt.currentSpanA) >= (cur.currentSpanB + nxt.currentSpanB)
                val cloudFill = if (isBullCloud) Color(0x382E7D32) else Color(0x38C62828)
                val path = Path().apply {
                    moveTo(x1, yOf(cur.currentSpanA))
                    lineTo(x2, yOf(nxt.currentSpanA))
                    lineTo(x2, yOf(nxt.currentSpanB))
                    lineTo(x1, yOf(cur.currentSpanB))
                    close()
                }
                drawPath(path, color = cloudFill)
                drawLine(Color(0xAA66BB6A), Offset(x1, yOf(cur.currentSpanA)), Offset(x2, yOf(nxt.currentSpanA)), 1.5f)
                drawLine(Color(0xAAEF5350), Offset(x1, yOf(cur.currentSpanB)), Offset(x2, yOf(nxt.currentSpanB)), 1.5f)
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
                    val fill = if (isBull) Color(0x2800E676) else Color(0x28FF5252)
                    val path = Path().apply {
                        moveTo(prevX, yOf(prevA))
                        lineTo(nextX, yOf(fk.spanA))
                        lineTo(nextX, yOf(fk.spanB))
                        lineTo(prevX, yOf(prevB))
                        close()
                    }
                    drawPath(path, color = fill)
                    drawLine(Color(0x9966BB6A), Offset(prevX, yOf(prevA)), Offset(nextX, yOf(fk.spanA)), 1.4f)
                    drawLine(Color(0x99EF5350), Offset(prevX, yOf(prevB)), Offset(nextX, yOf(fk.spanB)), 1.4f)
                    prevX = nextX
                    prevA = fk.spanA
                    prevB = fk.spanB
                }
            }

            // ۴. رسم کندل‌های ژاپنی (Candlesticks)
            val bodyWidth = (slotW * 0.66f).coerceAtLeast(2.5f)
            visibleSlice.forEachIndexed { slot, item ->
                val c = item.candle
                val cx = xOfSlot(slot)
                val isBull = c.close >= c.open
                val candleColor = if (isBull) Color(0xFF00C853) else Color(0xFFFF5252)
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

            // ۵. رسم خطوط Tenkan-sen (آبی)، Kijun-sen (طلایی) و Chikou Span (بنفش)
            for (i in 0 until visibleSlice.size - 1) {
                val cur = visibleSlice[i]
                val nxt = visibleSlice[i + 1]
                val x1 = xOfSlot(i)
                val x2 = xOfSlot(i + 1)
                drawLine(Color(0xFF29B6F6), Offset(x1, yOf(cur.tenkan)), Offset(x2, yOf(nxt.tenkan)), strokeWidth = 2.2f)
                drawLine(Color(0xFFFFB300), Offset(x1, yOf(cur.kijun)), Offset(x2, yOf(nxt.kijun)), strokeWidth = 2.4f)
                if (cur.chikouPrice != null && nxt.chikouPrice != null) {
                    drawLine(Color(0xFFCE93D8), Offset(x1, yOf(cur.chikouPrice)), Offset(x2, yOf(nxt.chikouPrice)), strokeWidth = 1.8f)
                }
            }

            // ۶. خطوط اهداف موجی هوسودا (TP1=V, TP2=N) و استاپ کیجون در صورت قرار گرفتن در محدوده دید
            val targetDash = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            targetN?.takeIf { it in padMin..padMax }?.let { tp ->
                val ty = yOf(tp)
                drawLine(Color(0xFF00E676), Offset(0f, ty), Offset(w, ty), strokeWidth = 1.6f, pathEffect = targetDash)
            }
            targetV?.takeIf { it in padMin..padMax }?.let { tp ->
                val ty = yOf(tp)
                drawLine(Color(0x9900E676), Offset(0f, ty), Offset(w, ty), strokeWidth = 1.2f, pathEffect = targetDash)
            }
            kijunStop?.takeIf { it in padMin..padMax }?.let { sl ->
                val sy = yOf(sl)
                drawLine(Color(0x99FF5252), Offset(0f, sy), Offset(w, sy), strokeWidth = 1.2f, pathEffect = targetDash)
            }

            // ۷. خط نشانگر کندل انتخاب‌شده (Crosshair)
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

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"
