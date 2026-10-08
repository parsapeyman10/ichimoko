package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketPlaybook
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.VenueSpecs
import kotlinx.coroutines.delay
import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.SignalEngine
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
    val openTrade = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }
    val context = LocalContext.current
    // Reset the probe whenever the instrument or timeframe changes.
    var chartSource by remember(market.symbol, market.interval) { mutableStateOf(ChartSource.TRADINGVIEW) }
    var widgetLoaded by remember(market.symbol, market.interval) { mutableStateOf(false) }
    var widgetProblem by remember(market.symbol, market.interval) { mutableStateOf<String?>(null) }
    var widgetAttempt by remember(market.symbol, market.interval) { mutableStateOf(0) }
    /** null = این نماد در نگاشت رسمی TradingView اپ نیست؛ هرگز نماد دیگری جایش نشان داده نمی‌شود. */
    val tvSymbol = remember(market.symbol) { TradingViewSymbols.find(market.symbol) }
    val tvChartable = tvSymbol != null
    val effectiveSource = if (tvChartable) chartSource else ChartSource.NATIVE
    /** Which trade window is open; a NEW entry opens its own window automatically. */
    var expandedTradeId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAutoExpanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trades) {
        val newest = trades.filter { it.isOpen }.maxByOrNull { it.openedAt }
        if (newest != null && newest.id != lastAutoExpanded) {
            // «هر وقت معامله‌ای شروع شد و ورود کرد، عیناً در صفحهٔ چارت یک پنجره باز می‌شود»
            lastAutoExpanded = newest.id
            expandedTradeId = newest.id
        } else if (expandedTradeId != null && trades.none { it.id == expandedTradeId && it.isOpen }) {
            expandedTradeId = null // that position was settled; its window closes with it
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Interval.entries.forEach { interval ->
                FilterChip(
                    selected = market.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                    label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        // A silent dark box is the worst possible answer, so the widget gets an honest watchdog:
        // if TradingView has not finished loading in time, the app says so and offers a retry,
        // the real TradingView site, or the app's own candles — chosen by the user, never silently.
        LaunchedEffect(chartSource, widgetAttempt, market.symbol, market.interval) {
            if (chartSource != ChartSource.TRADINGVIEW || !tvChartable) return@LaunchedEffect
            widgetLoaded = false
            widgetProblem = null
            delay(WIDGET_TIMEOUT_MS)
            if (!widgetLoaded && widgetProblem == null) {
                widgetProblem = "ویدجت TradingView در ${WIDGET_TIMEOUT_MS / 1000} ثانیه بارگذاری نشد؛ " +
                    "شبکه/فیلتر یا VPN را بررسی کنید"
            }
        }

        SectionCard(
            title = when (effectiveSource) {
                ChartSource.TRADINGVIEW -> "چارت TradingView · ${market.symbol}"
                ChartSource.NATIVE -> "چارت داخلی اپ · ${market.symbol}"
            },
            subtitle = when {
                !tvChartable -> "نماد ${market.symbol} در نگاشت رسمی TradingView اپ نیست؛ برای اینکه نماد " +
                    "دیگری (مثلاً طلا) جایش نمایش داده نشود، چارت داخلی اپ روی همان کندل‌های واقعی آمده است"
                effectiveSource == ChartSource.NATIVE ->
                    "چارت داخلی را خودت انتخاب کردی؛ کندل‌ها همان دادهٔ واقعی دریافتی اپ است"
                widgetProblem != null -> widgetProblem
                else -> "ویدجت رسمی TradingView با همان مشخصات خودش (کندل‌ها، تایم‌فریم، ایچیموکو) — " +
                    "هیچ خط یا لایهٔ دست‌سازی روی چارت کشیده نمی‌شود"
            },
            trailing = {
                Pill(
                    when {
                        !tvChartable -> "نگاشت نشده"
                        effectiveSource == ChartSource.NATIVE -> "چارت داخلی"
                        widgetLoaded -> "TradingView بارگذاری شد"
                        else -> "در حال بارگذاری"
                    },
                    when {
                        !tvChartable -> AurumColors.Red
                        effectiveSource == ChartSource.NATIVE -> AurumColors.TextMuted
                        widgetLoaded -> AurumColors.Green
                        else -> AurumColors.Gold
                    },
                )
            },
        ) {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                when (effectiveSource) {
                    ChartSource.NATIVE ->
                        if (market.candles.isNotEmpty()) {
                            CandleChart(
                                candles = market.candles.takeLast(800),
                                interval = market.interval,
                                signal = market.signal,
                                modifier = Modifier.fillMaxSize(),
                                showVolume = false,
                            )
                        } else {
                            Text("کندل واقعی در دسترس نیست؛ چارت داخلی بدون داده چیزی نمی‌کشد.",
                                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp))
                        }
                    ChartSource.TRADINGVIEW -> key(widgetAttempt) {
                        TradingViewWidget(
                            symbol = market.symbol,
                            interval = market.interval,
                            modifier = Modifier.fillMaxSize(),
                            onLoaded = { widgetLoaded = true; widgetProblem = null },
                            onFailed = { blocked ->
                                if (blocked) {
                                    widgetProblem = "TradingView از این شبکه پاسخ نداد (خطای اصلی قاب/۴۵۱)؛ " +
                                        "چارت را در مرورگر باز کن یا چارت داخلی اپ را ببین"
                                }
                            },
                        )
                    }
                }
                if (effectiveSource == ChartSource.TRADINGVIEW && !widgetLoaded) {
                    WidgetStatusPanel(
                        problem = widgetProblem,
                        onRetry = { widgetAttempt++ },
                        onOpenBrowser = { context.openTradingView(tvSymbol) },
                        onFallback = { chartSource = ChartSource.NATIVE },
                        fallbackLabel = "چارت داخلی اپ",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // اطلاعات موتور زیر چارت می‌نشیند، نه روی کندل‌ها: هیچ چیزی روی ویدجت TradingView
            // قرار نمی‌گیرد تا چارت دقیقاً با مشخصات خودش دیده شود.
            EngineOverlay(
                market = market,
                openTrade = openTrade,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        // ── پنجرهٔ هر معاملهٔ باز: همان لحظه که ورود ثبت می‌شود اینجا باز می‌شود ──────
        OpenTradeWindows(
            openTrades = trades.filter { it.isOpen }.sortedByDescending { it.openedAt },
            livePrices = livePrices,
            expandedId = expandedTradeId,
            onExpand = { expandedTradeId = it },
            onShowOnChart = { symbol -> viewModel.selectChartSymbol(symbol) },
            chartSymbol = market.symbol,
            chartInterval = market.interval,
            chartCandles = market.candles,
        )

        StrategyBar(viewModel, market)

        EntryScoreCard(market)
    }
}

@Composable
private fun EngineOverlay(market: MarketState, openTrade: PaperTrade?, modifier: Modifier = Modifier) {
    val setting = SignalEngine.ichimokuSetting(market.interval)
    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.Gold
    }
    Column(
        modifier = modifier
            .background(AurumColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("AURUM ICHIMOKU", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        Text("${setting.tenkan}/${setting.kijun}/${setting.spanB} · امتیاز ${(signal?.confidence ?: 0.0).toInt()}/100",
            style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        Text(when (action) {
            SignalAction.BUY -> "سیگنال موتور: BUY"
            SignalAction.SELL -> "سیگنال موتور: SELL"
            SignalAction.NO_TRADE -> "سیگنال موتور: NO TRADE"
        }, style = MaterialTheme.typography.labelSmall, color = color)
        market.lastPrice?.let { price ->
            Text(
                "آخرین قیمت: ${formatPriceFor(market.symbol, price)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        if (signal?.isActionable == true) {
            Text("E ${formatPrice(signal.entry)} · SL ${formatPrice(signal.stopLoss)} · TP ${formatPrice(signal.takeProfit)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
        }
        openTrade?.let { trade ->
            Text("Paper باز: ${trade.action} · ${formatDateTime(trade.openedAt)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            Text("E ${formatPrice(trade.entry)} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
        }
    }
}

@Composable
private fun EntryScoreCard(market: MarketState) {
    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.TextSecondary
    }
    val score = signal?.confidence ?: 0.0
    SectionCard(
        title = "امتیاز ورود به معامله",
        trailing = { Pill("${score.toInt()}/100", color) },
    ) {
        Text(
            when (action) {
                SignalAction.BUY -> "امتیاز خرید"
                SignalAction.SELL -> "امتیاز فروش"
                SignalAction.NO_TRADE -> "فعلاً ورود مجاز نیست"
            },
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        LinearProgressIndicator(
            progress = { (score / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            trackColor = AurumColors.SurfaceAlt,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (signal?.isActionable == true) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ورود", formatPrice(signal.entry), AurumColors.Gold, Modifier.weight(1f))
                StatTile("SL", formatPrice(signal.stopLoss), AurumColors.Red, Modifier.weight(1f))
                StatTile("TP", formatPrice(signal.takeProfit), AurumColors.Green, Modifier.weight(1f))
                StatTile("کندل", formatTime(signal.barTime), AurumColors.TextSecondary, Modifier.weight(1f))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    widgetId: String = "chart_primary",
    modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {},
    onFailed: (Boolean) -> Unit = {},
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.find(symbol) }
    if (tvSymbol == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("نماد $symbol در نگاشت رسمی TradingView اپ نیست؛ هیچ نماد جایگزینی (مثلاً طلا) " +
                    "نشان داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
                modifier = Modifier.padding(16.dp))
        }
        return
    }
    val tvInterval = remember(interval) {
        when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }
    }
    val html = remember(tvSymbol, tvInterval, widgetId) { tradingViewHtml(tvSymbol, tvInterval, widgetId) }

    // Reload only when the instrument/timeframe really changed: an unconditional reload on every
    // recomposition is what kept TradingView stuck on "loading".
    val loadKey = "$tvSymbol|$tvInterval|$widgetId"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // The widget renders its full desktop layout (side toolbar, studies, scales)
                    // for a desktop agent; a mobile agent gets a stripped chart. Same TradingView,
                    // same data — only the layout it serves changes.
                    userAgentString = DESKTOP_USER_AGENT
                    textZoom = 100
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                // Where TradingView is geo-blocked it answers 451 and a blank error page would sit
                // on top of an honest "not loaded" state. Report real failures, never guess.
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onFailed(false)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoaded()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onFailed(true)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onFailed(true)
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?,
                    ) {
                        // A certificate problem is never ignored: cancel and report it.
                        handler?.cancel()
                        onFailed(true)
                    }
                }
                tag = loadKey
                loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // update runs on every recomposition; only a real instrument/timeframe change reloads.
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
    )
}

/**
 * ONLY the official TradingView widget: its own candles, its own scales, its own Ichimoku study.
 *
 * قبلاً روی همین ویدجت یک HUD و سه خط چین با درصدهای ثابت (۲۰٪/۴۸٪/۷۵٪) کشیده می‌شد؛ آن خط‌ها
 * جای واقعی ورود/حد ضرر/حد سود روی چارت نبودند و فقط چارت را شلوغ و گمراه‌کننده می‌کردند.
 * عددهای واقعی معامله حالا در نوار بالای همان پنجره (از ژورنال) نوشته می‌شوند، نه روی کندل‌ها.
 */
private fun tradingViewHtml(tvSymbol: String, tvInterval: String, widgetId: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tv_$widgetId" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background-color: #0b0e13;
            }
            iframe { width: 100%; height: 100%; border: none; display: block; }
          </style>
        </head>
        <body>
          <iframe id="tv_frame" src="$iframeUrl" allowtransparency="true" frameborder="0"
                  allow="fullscreen"></iframe>
        </body>
        </html>
    """.trimIndent()
}

/** Which chart the user is looking at; TradingView is always the default. */
private enum class ChartSource { TRADINGVIEW, NATIVE }

private const val WIDGET_TIMEOUT_MS = 20_000L

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

/**
 * Honest loading/failure state for the TradingView widget. It never pretends the chart loaded and
 * never silently swaps in another renderer: retry, the real TradingView site, or the app's own
 * candles are the user's choice.
 */
@Composable
private fun WidgetStatusPanel(
    problem: String?,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit,
    onFallback: () -> Unit,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(AurumColors.Surface.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (problem == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
                Text("در حال بارگذاری ویدجت رسمی TradingView…",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                Text("کندل‌ها، تایم‌فریم و ایچیموکو مستقیم از خود TradingView می‌آید؛ اگر بارگذاری " +
                        "تمام نشود دلیلش را همین‌جا می‌نویسیم.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            } else {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onRetry) { Text("تلاش دوباره", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(onClick = onOpenBrowser) { Text("در مرورگر", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(onClick = onFallback) { Text(fallbackLabel, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

private fun android.content.Context.openTradingView(tvSymbol: String?, query: String? = null) {
    val url = when {
        !tvSymbol.isNullOrBlank() -> "https://www.tradingview.com/chart/?symbol=${Uri.encode(tvSymbol)}"
        !query.isNullOrBlank() -> "https://www.tradingview.com/search/?q=${Uri.encode(query)}"
        else -> "https://www.tradingview.com/chart/"
    }
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * «برای هر معامله یک پنجره»: one window per OPEN paper trade, on the chart page, each with its own
 * official TradingView widget for that trade's symbol and timeframe. Windows appear the moment an
 * entry is recorded and close when the position is settled.
 */
@Composable
private fun OpenTradeWindows(
    openTrades: List<PaperTrade>,
    livePrices: Map<String, Double>,
    expandedId: String?,
    onExpand: (String?) -> Unit,
    onShowOnChart: (String) -> Unit,
    chartSymbol: String,
    chartInterval: Interval,
    chartCandles: List<com.aurum.edge.core.Candle>,
) {
    if (openTrades.isEmpty()) {
        SectionCard(
            title = "پنجرهٔ معاملات باز",
            subtitle = "به‌محض ثبت ورود، پنجرهٔ همان معامله با چارت TradingView همین‌جا باز می‌شود",
        ) {
            Text("الان معاملهٔ بازی در ژورنال نیست. وقتی پویشگر یا ورود دستی معامله‌ای باز کند، " +
                    "پنجرهٔ مخصوص همان معامله (چارت TradingView + عددهای واقعی ورود/حد ضرر/حد سود) " +
                    "به‌صورت خودکار در همین صفحه باز می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
        return
    }
    openTrades.forEach { trade ->
        TradeWindowCard(
            trade = trade,
            livePrice = livePrices[trade.symbol],
            expanded = expandedId == trade.id,
            onToggle = { onExpand(if (expandedId == trade.id) null else trade.id) },
            onShowOnChart = { onShowOnChart(trade.symbol) },
            chartSymbol = chartSymbol,
            chartInterval = chartInterval,
            chartCandles = chartCandles,
        )
    }
}

@Composable
private fun TradeWindowCard(
    trade: PaperTrade,
    livePrice: Double?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShowOnChart: () -> Unit,
    chartSymbol: String,
    chartInterval: Interval,
    chartCandles: List<com.aurum.edge.core.Candle>,
) {
    val context = LocalContext.current
    val tvSymbol = remember(trade.symbol) { TradingViewSymbols.find(trade.symbol) }
    val tvChartable = tvSymbol != null
    val spec = remember(trade.symbol) { VenueSpecs.of(trade.symbol) }
    var widgetLoaded by remember(trade.id, trade.symbol, trade.interval) { mutableStateOf(false) }
    var widgetProblem by remember(trade.id, trade.symbol, trade.interval) { mutableStateOf<String?>(null) }
    var widgetAttempt by remember(trade.id, trade.symbol, trade.interval) { mutableStateOf(0) }

    LaunchedEffect(expanded, widgetAttempt, trade.id) {
        if (!expanded || !tvChartable) return@LaunchedEffect
        widgetLoaded = false
        widgetProblem = null
        delay(WIDGET_TIMEOUT_MS)
        if (!widgetLoaded && widgetProblem == null) {
            widgetProblem = "ویدجت TradingView در ${WIDGET_TIMEOUT_MS / 1000} ثانیه بارگذاری نشد؛ " +
                "شبکه/فیلتر یا VPN را بررسی کنید"
        }
    }

    // The method of THIS market is measured only from real candles of the same symbol; otherwise we
    // say so instead of guessing from another instrument's chart.
    val playbook = remember(trade.symbol, chartSymbol, chartInterval, chartCandles.size) {
        if (trade.symbol != chartSymbol || chartCandles.size < 40) null
        else runCatching {
            MarketPlaybook.assess(trade.symbol, chartCandles.takeLast(120), chartInterval)
        }.getOrNull()
    }

    val isBuy = trade.action == SignalAction.BUY
    val pnlUsd = livePrice?.let { price ->
        val perUnit = if (isBuy) price - trade.entry else trade.entry - price
        kotlin.math.round(PaperOrderRules.quotePnlToUsd(trade.symbol, perUnit * trade.positionOz, price) * 100.0) / 100.0
    }
    val roundTripCost = livePrice?.let { price ->
        kotlin.math.round((spec.spreadCostUsd(price, trade.positionOz) +
                spec.commissionUsd(price, trade.positionOz) * 2.0) * 100.0) / 100.0
    }
    val spreadPrice = livePrice?.let { price ->
        if (spec.spreadBps > 0.0) price * spec.spreadBps / 10_000.0 else spec.spreadPrice
    }
    val ageMinutes = ((System.currentTimeMillis() - trade.openedAt) / 60_000L).coerceAtLeast(0L)

    SectionCard(
        title = "پنجرهٔ معاملهٔ ${trade.symbol} · ${if (isBuy) "خرید LONG" else "فروش SHORT"}",
        subtitle = "چارت و کندل‌ها فقط از ویدجت رسمی TradingView با همان مشخصات خودش؛ عددهای معامله از ژورنال واقعی دستگاه",
        trailing = {
            Pill(
                when {
                    !tvChartable -> "نگاشت نشده"
                    !expanded -> "چارت بسته است"
                    widgetLoaded -> "TradingView بارگذاری شد"
                    else -> "در حال بارگذاری"
                },
                when {
                    !tvChartable -> AurumColors.Red
                    !expanded -> AurumColors.TextMuted
                    widgetLoaded -> AurumColors.Green
                    else -> AurumColors.Gold
                },
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("ورود", formatPriceFor(trade.symbol, trade.entry), AurumColors.Gold, Modifier.weight(1f))
            StatTile("حد ضرر", formatPriceFor(trade.symbol, trade.stopLoss), AurumColors.Red, Modifier.weight(1f))
            StatTile("حد سود", formatPriceFor(trade.symbol, trade.takeProfit), AurumColors.Green, Modifier.weight(1f))
            StatTile("R:R", String.format(java.util.Locale.US, "%.2f", trade.riskReward),
                AurumColors.Cyan, Modifier.weight(1f))
        }
        Text(
            "حجم ${String.format(java.util.Locale.US, "%.6f", trade.positionOz)} ${trade.unit} · اهرم 1:${trade.leverage} · " +
                "مارجین ${String.format(java.util.Locale.US, "%.2f", trade.marginUsd)}$ · " +
                "باز شده ${formatDateTime(trade.openedAt)} (${if (ageMinutes < 60) "$ageMinutes دقیقه" else "${ageMinutes / 60} ساعت و ${ageMinutes % 60} دقیقه"} پیش) · " +
                if (trade.autoOpened) "ورود خودکار کاغذی" else "ورود دستی کاغذی",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (livePrice != null && livePrice > 0.0) {
            val stopDistance = kotlin.math.abs(livePrice - trade.stopLoss)
            val targetDistance = kotlin.math.abs(trade.takeProfit - livePrice)
            val spreadText = if (spreadPrice != null && spreadPrice > 0.0)
                " (≈${String.format(java.util.Locale.US, "%.1f", stopDistance / spreadPrice)} برابر اسپرد تا SL)" else ""
            Text(
                "آخرین قیمت ${formatPriceFor(trade.symbol, livePrice)} · سود/زیان باز " +
                    "${String.format(java.util.Locale.US, "%.2f", pnlUsd ?: 0.0)}$" +
                    (roundTripCost?.let { " · هزینهٔ رفت‌وبرگشت ${String.format(java.util.Locale.US, "%.2f", it)}$" } ?: "") +
                    " · فاصله تا حد ضرر ${formatPriceFor(trade.symbol, stopDistance)}$spreadText" +
                    " · فاصله تا حد سود ${formatPriceFor(trade.symbol, targetDistance)}",
                style = MaterialTheme.typography.labelSmall,
                color = if ((pnlUsd ?: 0.0) >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text("قیمت زندهٔ این نماد الان در دسترس نیست؛ سود/زیان باز فقط با قیمت واقعی نشان داده می‌شود، نه با حدس.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (playbook != null) {
            Text(
                "متد این بازار: ${playbook.family.label} · ${playbook.session.label} · ${playbook.regime.label} → " +
                    "${playbook.method.label}" + if (playbook.allowed) "" else " (الان ورود مجاز نیست: " +
                    (playbook.blockers.firstOrNull() ?: "—") + ")",
                style = MaterialTheme.typography.labelSmall,
                color = if (playbook.allowed) AurumColors.Cyan else AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text("برای دیدن متدِ این بازار، همین نماد را در چارت بالا انتخاب کن تا از کندل‌های بستهٔ واقعیِ " +
                    "خودش اندازه گرفته شود (نه از کندلِ نمادی دیگر).",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }
        Text("خروج فقط با قیمت واقعی و در ژورنال ثبت می‌شود؛ این پنجره هیچ سفارشی نمی‌فرستد.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp))

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onToggle) {
                Text(if (expanded) "بستن چارت این معامله" else "باز کردن چارت این معامله",
                    style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onShowOnChart) {
                Text("انتخاب این نماد در چارت بالا", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (expanded && !tvChartable) {
            Text("چارت TradingView برای ${trade.symbol} در دسترس نیست چون این نماد در نگاشت رسمی اپ " +
                    "نیست؛ به‌جایش نماد دیگری نشان داده نمی‌شود. می‌توانی همین نماد را در TradingView " +
                    "جست‌وجو کنی یا چارت داخلی اپ را در بالا ببینی.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(
                onClick = { context.openTradingView(null, trade.symbol) },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("جست‌وجوی ${trade.symbol} در TradingView", style = MaterialTheme.typography.labelSmall) }
        } else if (expanded) {
            Box(Modifier.fillMaxWidth().height(400.dp).padding(top = 8.dp)) {
                key(widgetAttempt) {
                    TradingViewWidget(
                        symbol = trade.symbol,
                        interval = trade.interval,
                        widgetId = "trade_" + trade.id.takeLast(8),
                        modifier = Modifier.fillMaxSize(),
                        onLoaded = { widgetLoaded = true; widgetProblem = null },
                        onFailed = { blocked ->
                            if (blocked) {
                                widgetProblem = "TradingView برای ${trade.symbol} از این شبکه پاسخ نداد " +
                                    "(خطای اصلی قاب/۴۵۱)"
                            }
                        },
                    )
                }
                if (!widgetLoaded) {
                    WidgetStatusPanel(
                        problem = widgetProblem,
                        onRetry = { widgetAttempt++ },
                        onOpenBrowser = { context.openTradingView(tvSymbol) },
                        onFallback = onShowOnChart,
                        fallbackLabel = "چارت اصلی اپ",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SymbolSearchRow(selected: String, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("طلا و فارکس") }
    var open by rememberSaveable { mutableStateOf(false) }

    val forexIds = remember { WatchCatalog.chartSymbols.filter { !CryptoCatalog.isCrypto(it) } }
    val cryptoList = remember { CryptoCatalog.symbols }

    val forexShown = remember(query) {
        val needle = SymbolSearch.normalize(query).replace(" ", "").uppercase()
        if (needle.isEmpty()) forexIds
        else forexIds.filter { it.replace("/", "").contains(needle, ignoreCase = true) }
    }
    val cryptoShown = remember(query) { SymbolSearch.rank(query, cryptoList, limit = 300) }
    val suggestions = remember(query, forexShown.size, cryptoShown.size) {
        if (query.isBlank() || forexShown.isNotEmpty() || cryptoShown.isNotEmpty()) emptyList()
        else SymbolSearch.suggest(query, cryptoList)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "نماد: $selected",
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${forexIds.size + cryptoList.size} نماد",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Text(
                if (open) "  بستن" else "  تغییر نماد",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                modifier = Modifier.clickable { open = !open }.padding(6.dp),
            )
        }

        if (!open) return@Column

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("جستجو — طلا، EUR، بیت‌کوین، PEPE…", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("طلا و فارکس", "ارز دیجیتال").forEach { option ->
                FilterChip(
                    selected = group == option,
                    onClick = { group = option },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp)) {
            if (group == "طلا و فارکس") {
                items(forexShown, key = { "f-$it" }) { id ->
                    SymbolRow(id, id == selected) { onSelect(id); open = false; query = "" }
                }
            } else {
                items(cryptoShown, key = { "c-" + it.id }) { coin ->
                    SymbolRow(coin.id, coin.id == selected) { onSelect(coin.id); open = false; query = "" }
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    Text(
                        "«$query» پیدا نشد. منظورتان این بود؟",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(suggestions, key = { "s-" + it.id }) { coin ->
                    SymbolRow(coin.id, false, AurumColors.Cyan) {
                        onSelect(coin.id); open = false; query = ""
                    }
                }
            }
        }
    }
    SymbolPickerRow(selected, onSelect)
}

@Composable
private fun SymbolRow(
    id: String,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Text(
        id,
        style = MaterialTheme.typography.bodyMedium,
        color = tint ?: if (selected) AurumColors.Gold else AurumColors.TextPrimary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
    )
}

@Composable
internal fun SymbolPickerRow(selected: String, onSelect: (String) -> Unit) {
    // Gold first, then the FX pairs, then crypto — the order asked for.
    val quick = remember(selected) {
        val forex = WatchCatalog.chartSymbols.filter { !CryptoCatalog.isCrypto(it) }
        val crypto = WatchCatalog.chartSymbols.filter { CryptoCatalog.isCrypto(it) }
        (listOf("XAU/USD") + forex + crypto + selected).distinct()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quick.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { if (selected != id) onSelect(id) },
                label = { Text(id, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                    selectedLabelColor = AurumColors.Gold,
                    labelColor = AurumColors.TextSecondary,
                ),
            )
        }
    }
}

/**
 * Live strategy readout, directly under the chart.
 *
 * The engine was already evaluating every closed bar, but the chart only showed a score
 * with no explanation, so a screen that sat at "no entry" for hours was indistinguishable
 * from a broken one. This states three things at all times: where the score stands against
 * the threshold, the single condition currently blocking an entry, and — when one is live
 * — the exact levels the trade would use.
 *
 * Read-only. It reports what the automatic engine decided; it cannot open anything.
 */
@Composable
private fun StrategyBar(viewModel: AurumViewModel, market: MarketState) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()

    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val score = signal?.confidence ?: 0.0
    val threshold = settings.minConfidence
    val digits = CryptoCatalog.digitsFor(market.symbol)
    val open = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }

    val tone = when {
        open != null -> AurumColors.Cyan
        action == SignalAction.BUY -> AurumColors.Green
        action == SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.TextSecondary
    }

    // The first unmet condition is far more useful than a list of twelve.
    val firstBlocker = signal?.blockers?.firstOrNull()
        ?: signal?.confluence?.firstOrNull { !it.ok }?.let { "${it.name} — ${it.detail}" }

    SectionCard(
        title = "استراتژی روی ${market.symbol}",
        subtitle = "ایچیموکو · ${market.interval.label} · خودکار و کاغذی",
        trailing = {
            Pill(
                when {
                    open != null -> "پوزیشن باز"
                    action == SignalAction.BUY -> "خرید"
                    action == SignalAction.SELL -> "فروش"
                    else -> "منتظر"
                },
                tone,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("امتیاز", "${score.toInt()} / ${threshold.toInt()}", tone, Modifier.weight(1f))
            StatTile("تایم‌فریم", market.interval.label, modifier = Modifier.weight(1f))
            StatTile(
                "کندل بسته",
                market.candles.count { it.closed }.toString(),
                modifier = Modifier.weight(1f),
            )
        }

        LinearProgressIndicator(
            progress = { (score / 100.0).toFloat().coerceIn(0f, 1f) },
            color = tone,
            trackColor = AurumColors.Line,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        // ── the levels, whether live or hypothetical ──
        val entry = open?.entry ?: signal?.entry
        val stop = open?.stopLoss ?: signal?.stopLoss
        val target = open?.takeProfit ?: signal?.takeProfit
        if (entry != null && stop != null && target != null) {
            Text(
                if (open != null) "حدود پوزیشن باز" else "اگر وارد شود، با این حدود",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ورود", formatPriceFor(market.symbol, entry), modifier = Modifier.weight(1f))
                StatTile("حد ضرر", formatPriceFor(market.symbol, stop), AurumColors.Red, Modifier.weight(1f))
                StatTile("حد سود", formatPriceFor(market.symbol, target), AurumColors.Green, Modifier.weight(1f))
            }
            val risk = kotlin.math.abs(entry - stop)
            val reward = kotlin.math.abs(target - entry)
            if (risk > 0) {
                Text(
                    "نسبت سود به ضرر ${String.format(java.util.Locale.US, "%.2f", reward / risk)} " +
                        "· فاصلهٔ حد ضرر ${String.format(java.util.Locale.US, "%,.${digits}f", risk)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── why it is not entering ──
        if (open == null) {
            Text(
                "چرا وارد نشده",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                firstBlocker ?: when {
                    signal == null -> "هنوز سیگنالی برای این کندل محاسبه نشده است."
                    score < threshold -> "امتیاز ${score.toInt()} هنوز به آستانهٔ ${threshold.toInt()} نرسیده است."
                    else -> "همهٔ شرط‌ها برقرار است؛ منتظر تأیید کندل بسته."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
            // The auto-trader has its own gate, separate from the signal score.
            Text(
                "وضعیت موتور خودکار: $autoStatus",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── remaining conditions, compact ──
        val pending = signal?.confluence?.filter { !it.ok }.orEmpty()
        if (pending.isNotEmpty()) {
            Text(
                "${pending.size} شرط باقی‌مانده: " + pending.take(4).joinToString("، ") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
