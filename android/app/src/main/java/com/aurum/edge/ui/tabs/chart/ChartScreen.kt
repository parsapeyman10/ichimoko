package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه چارت‌ها (Multi-Chart Screen):
 * شامل ۵ پنجره آنلاین تریدینگ‌ویو همزمان:
 * ۱. پنجره اصلی در بالاترین بخش (پیش‌فرض طلای جهانی XAU/USD با قابلیت تغییر به ۵۰+ نماد دلخواه)
 * ۲ الی ۵. چهار پنجره اختصاصی زنده برای ۴ دسته دارایی (کالا، فارکس، رمزارز، سهام):
 *    - در صورت باز بودن معامله: نمایش چارت زنده همان معامله باز
 *    - در صورت نبود معامله باز: نمایش چارت نمادی که موتور در لایه پنهان در حال ارزیابی آن است
 * همراه با درج مقادیر حد ضرر (SL)، حد سود (TP)، قیمت ورود و اهرم مستقیماً روی چارت‌ها.
 */
@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {

        // ═════════════════════════════════════════════════════════════════════
        // چارت شماره ۱ (بالاترین پنجره): چارت اصلی و اختصاصی دستی
        // پیش‌فرض روی طلای جهانی (XAU/USD) با امکان تغییر به هر نماد دیگر
        // ═════════════════════════════════════════════════════════════════════
        val primarySignal = market.signal
        SectionCard(
            title = "⭐ چارت اصلی ۱ (سفارشی و آزاد) · ${market.symbol}",
            subtitle = "پیش‌فرض روی طلای جهانی · امکان انتخاب هر یک از ۵۰+ نماد با ابر ایچیموکو و سطوح ورود/SL/TP",
            trailing = {
                Pill(
                    text = market.feed.mode.label,
                    color = if (market.feed.mode == FeedMode.LIVE) AurumColors.Green else AurumColors.Gold,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            // ۱.۱ جستجو و تغییر نماد چارت اصلی
            SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

            // ۱.۲ انتخاب تایم‌فریم برای چارت اصلی
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
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

            // ۱.۳ سطوح ورود، حد ضرر و حد سود در بالای چارت
            if (primarySignal?.entry != null && primarySignal.stopLoss != null && primarySignal.takeProfit != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ورود: ${formatPrice(primarySignal.entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("SL: ${formatPrice(primarySignal.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("TP: ${formatPrice(primarySignal.takeProfit)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                    Text("اهرم: ${PaperOrderRules.defaultLeverageFor(market.symbol)}x", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                }
            }

            // ۱.۴ پنجره تریدینگ‌ویو چارت اصلی
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .padding(top = 4.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            ) {
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    widgetId = "chart_primary",
                    entry = primarySignal?.entry,
                    stopLoss = primarySignal?.stopLoss,
                    takeProfit = primarySignal?.takeProfit,
                    action = primarySignal?.action,
                    leverage = PaperOrderRules.defaultLeverageFor(market.symbol),
                    riskReward = primarySignal?.riskReward,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // چارت‌های شماره ۲ الی ۵: چهار پنجره تریدینگ‌ویو اختصاصی برای ۴ دسته دارایی
        // اگر معامله باز باشد نماد آن معامله؛ در غیر این صورت نماد تحت ارزیابی زنده
        // ═════════════════════════════════════════════════════════════════════
        val categories = listOf(
            AssetClass.COMMODITY to "XAU/USD",
            AssetClass.FOREX to "EUR/USD",
            AssetClass.CRYPTO to "BTCUSDT",
            AssetClass.STOCK to "AAPL",
        )

        categories.forEachIndexed { index, (assetClass, defaultBenchmark) ->
            val tradeForClass = openTrades.firstOrNull { it.assetClass == assetClass }
            val evaluatingCandidate = scanState.topThree.firstOrNull { it.assetClass == assetClass }
                ?: scanState.statuses.firstOrNull { it.assetClass == assetClass && it.price != null && (it.technicalScore ?: 0) >= 3 }
                ?: scanState.statuses.firstOrNull { it.assetClass == assetClass && it.state != "closed" }

            val chartNumber = index + 2
            val activeSymbol = tradeForClass?.symbol ?: evaluatingCandidate?.symbol ?: defaultBenchmark
            val activeInterval = tradeForClass?.interval ?: market.interval
            val isTradeOpen = tradeForClass != null

            val activeEntry = tradeForClass?.entry ?: evaluatingCandidate?.entry
            val activeSL = tradeForClass?.stopLoss ?: evaluatingCandidate?.stopLoss
            val activeTP = tradeForClass?.takeProfit ?: evaluatingCandidate?.takeProfit
            val activeAction = tradeForClass?.action ?: evaluatingCandidate?.action
            val activeLeverage = tradeForClass?.effectiveLeverage ?: PaperOrderRules.defaultLeverageFor(activeSymbol)
            val activeRR = tradeForClass?.riskReward ?: evaluatingCandidate?.riskReward

            SectionCard(
                title = "چارت $chartNumber (${assetClass.label}) · $activeSymbol",
                subtitle = if (isTradeOpen) "پنجره زنده معاملهٔ فعال در پورتفو با ابر ایچیموکو و سطوح ورود/SL/TP"
                           else "پایش و ارزیابی زنده در پس‌زمینه · نماد کاندیدا: $activeSymbol",
                trailing = {
                    Pill(
                        text = if (isTradeOpen) "● معامله باز (${tradeForClass?.action?.name})"
                               else "🔍 در حال ارزیابی (${evaluatingCandidate?.technicalScore ?: 5}/7)",
                        color = if (isTradeOpen) AurumColors.Green else AurumColors.Cyan,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                if (isTradeOpen && tradeForClass != null) {
                    val currentPrice = livePrices[tradeForClass.symbol] ?: tradeForClass.entry
                    val pnlPerUnit = if (tradeForClass.action == SignalAction.BUY) currentPrice - tradeForClass.entry else tradeForClass.entry - currentPrice
                    val grossPnl = tradeForClass.positionOz * pnlPerUnit
                    val netPnl = grossPnl - (tradeForClass.effectiveCommissionUsd + tradeForClass.effectiveSpreadCostUsd)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("اهرم ${tradeForClass.effectiveLeverage}x", AurumColors.Cyan)
                            Pill("مارجین $${String.format(java.util.Locale.US, "%.1f", tradeForClass.effectiveMarginUsd)}", AurumColors.Gold)
                        }
                        Pill(
                            text = "PnL خالص: " + (if (netPnl >= 0) "+$" else "-$") + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(netPnl)),
                            color = if (netPnl >= 0) AurumColors.Green else AurumColors.Red,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "بدون پوزیشن باز در این دسته · نماد در حال رصد آنلاین: $activeSymbol",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Pill("اهرم ${activeLeverage}x", AurumColors.Cyan)
                    }
                }

                // نوار سطوح ورود، حد ضرر و حد سود اختصاصی روی چارت
                if (activeEntry != null && activeSL != null && activeTP != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(AurumColors.Surface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ورود: ${formatPrice(activeEntry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                        Text("SL: ${formatPrice(activeSL)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                        Text("TP: ${formatPrice(activeTP)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                        activeRR?.let { Text("R:R 1:${String.format(java.util.Locale.US, "%.1f", it)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }
                    }
                }

                // پنجره اختصاصی تریدینگ‌ویو
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            (if (isTradeOpen) AurumColors.Green else AurumColors.Surface).copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    TradingViewWidget(
                        symbol = activeSymbol,
                        interval = activeInterval,
                        widgetId = "chart_slot_$chartNumber",
                        entry = activeEntry,
                        stopLoss = activeSL,
                        takeProfit = activeTP,
                        action = activeAction,
                        leverage = activeLeverage,
                        riskReward = activeRR,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    widgetId: String,
    entry: Double? = null,
    stopLoss: Double? = null,
    takeProfit: Double? = null,
    action: SignalAction? = null,
    leverage: Int? = null,
    riskReward: Double? = null,
    modifier: Modifier = Modifier,
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.of(symbol) }
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
    val html = remember(tvSymbol, tvInterval, widgetId, entry, stopLoss, takeProfit, leverage) {
        tradingViewHtml(tvSymbol, tvInterval, widgetId, entry, stopLoss, takeProfit, action, leverage, riskReward)
    }
    val loadKey = "$tvSymbol|$tvInterval|$widgetId|$entry|$stopLoss|$takeProfit"

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
                }
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                }
                tag = loadKey
                loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
    )
}

private fun tradingViewHtml(
    tvSymbol: String,
    tvInterval: String,
    widgetId: String,
    entry: Double? = null,
    stopLoss: Double? = null,
    takeProfit: Double? = null,
    action: SignalAction? = null,
    leverage: Int? = null,
    riskReward: Double? = null,
): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tv_$widgetId" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"

    val hudHtml = if (entry != null && stopLoss != null && takeProfit != null) {
        val entryFormatted = String.format(java.util.Locale.US, "%,.2f", entry)
        val slFormatted = String.format(java.util.Locale.US, "%,.2f", stopLoss)
        val tpFormatted = String.format(java.util.Locale.US, "%,.2f", takeProfit)
        val levText = leverage?.let { "اهرم ${it}x" } ?: "اهرم 20x"
        val rrText = riskReward?.let { "R:R 1:${String.format(java.util.Locale.US, "%.1f", it)}" } ?: "R:R 1:2.0"
        val dirText = if (action == SignalAction.BUY) "LONG ↗" else "SHORT ↘"
        val dirColor = if (action == SignalAction.BUY) "#00e676" else "#ff5252"

        """
        <div id="price-levels-hud" style="position:absolute; top:6px; left:6px; right:6px; z-index:9999; background:rgba(11,14,19,0.88); backdrop-filter:blur(8px); border:1px solid rgba(255,215,0,0.35); border-radius:8px; padding:6px 10px; display:flex; justify-content:space-between; align-items:center; font-family:tahoma,sans-serif; font-size:11px; direction:rtl; box-shadow:0 4px 12px rgba(0,0,0,0.5);">
          <div style="display:flex; gap:10px; align-items:center;">
            <span style="color:$dirColor; font-weight:bold; background:rgba(255,255,255,0.06); padding:2px 6px; border-radius:4px;">$dirText</span>
            <span style="color:#00e676; font-weight:bold;">TP: $$tpFormatted</span>
            <span style="color:#ffd700; font-weight:bold;">ورود: $$entryFormatted</span>
            <span style="color:#ff5252; font-weight:bold;">SL: $$slFormatted</span>
          </div>
          <div style="display:flex; gap:6px;">
            <span style="background:rgba(0,229,255,0.15); color:#00e5ff; padding:2px 6px; border-radius:4px; font-weight:bold;">$levText</span>
            <span style="background:rgba(255,215,0,0.15); color:#ffd700; padding:2px 6px; border-radius:4px; font-weight:bold;">$rrText</span>
          </div>
        </div>
        """.trimIndent()
    } else ""

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
              position: relative;
            }
            iframe {
              width: 100%;
              height: 100%;
              border: none;
            }
          </style>
          <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
        </head>
        <body>
          $hudHtml
          <iframe src="$iframeUrl" allowtransparency="true" frameborder="0"></iframe>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun SymbolSearchRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { SymbolSearch.rank(query, CryptoCatalog.symbols, 15) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected, style = MaterialTheme.typography.bodyMedium, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                Pill(AssetClass.of(selected).label, AurumColors.Surface)
            }
            Text(
                if (expanded) "▲ بستن لیست نمادها" else "▼ تغییر نماد چارت اصلی (۵۰+ نماد)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("جستجوی نماد، طلا، نفت، رمزارزها یا سهام...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 6.dp)) {
                    items(results) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(item.id)
                                    expanded = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(item.id, style = MaterialTheme.typography.bodyMedium, color = AurumColors.TextPrimary)
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                            }
                            Pill(AssetClass.of(item.id).label, AurumColors.Surface)
                        }
                    }
                }
            }
        }
    }
}
