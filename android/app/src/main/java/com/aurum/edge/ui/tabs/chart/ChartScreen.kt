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
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه چارت‌ها (Multi-Chart Studio):
 * ۱. چارت اصلی و اختصاصی دستی (پیش‌فرض طلای جهانی XAU/USD با قابلیت تغییر به ۵۰+ نماد دلخواه)
 * ۲. استودیو زنده ۴ دسته دارایی (کالا، فارکس، رمزارز، سهام) با سویچر تب بهینه‌شده و فوق‌سریع
 * همراه با درج داینامیک و ظریف سطوح حد ضرر (SL)، حد سود (TP)، قیمت ورود و اهرم با دقت اعشاری بالا.
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
    var selectedCategoryTab by remember { mutableStateOf(AssetClass.COMMODITY) }

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

            // ۱.۳ سطوح ورود، حد ضرر و حد سود در بالای چارت با اعشار دقیق
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
                    Text("ورود: ${formatPriceFor(market.symbol, primarySignal.entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("SL: ${formatPriceFor(market.symbol, primarySignal.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("TP: ${formatPriceFor(market.symbol, primarySignal.takeProfit)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
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
        // استودیو چارت ۴ دسته دارایی (کالا، فارکس، رمزارزها، سهام)
        // ═════════════════════════════════════════════════════════════════════
        val categories = listOf(
            AssetClass.COMMODITY to "XAU/USD",
            AssetClass.FOREX to "EUR/USD",
            AssetClass.CRYPTO to "BTCUSDT",
            AssetClass.STOCK to "AAPL",
        )

        val activeAssetClass = selectedCategoryTab
        val defaultBenchmark = categories.firstOrNull { it.first == activeAssetClass }?.second ?: "XAU/USD"

        val tradeForClass = openTrades.firstOrNull { it.assetClass == activeAssetClass }
        val evaluatingCandidate = scanState.topThree.firstOrNull { it.assetClass == activeAssetClass }
            ?: scanState.statuses.firstOrNull { it.assetClass == activeAssetClass && it.price != null && (it.technicalScore ?: 0) >= 3 }
            ?: scanState.statuses.firstOrNull { it.assetClass == activeAssetClass && it.state != "closed" }

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
            title = "📊 استودیو چارت اختصاصی دسته‌ها · ${activeAssetClass.label}",
            subtitle = if (isTradeOpen) "معاملهٔ فعال: $activeSymbol (با سطوح زنده ورود، حد سود و ضرر)"
                       else "پایش و ارزیابی زنده در پس‌زمینه · نماد کاندیدا: $activeSymbol",
            trailing = {
                Pill(
                    text = if (isTradeOpen) "● معامله باز (${tradeForClass?.action?.name})"
                           else "🔍 رصد زنده (${evaluatingCandidate?.technicalScore ?: 5}/7)",
                    color = if (isTradeOpen) AurumColors.Green else AurumColors.Cyan,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            // تب‌بار سوئیچ سریع بین ۴ دسته دارایی
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                categories.forEach { (cls, _) ->
                    val isOpenInThisCls = openTrades.any { it.assetClass == cls }
                    FilterChip(
                        selected = selectedCategoryTab == cls,
                        onClick = { selectedCategoryTab = cls },
                        label = {
                            Text(
                                text = "${cls.label}${if (isOpenInThisCls) " (فعال)" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedCategoryTab == cls) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = (if (isOpenInThisCls) AurumColors.Green else AurumColors.Cyan).copy(alpha = 0.2f),
                            selectedLabelColor = if (isOpenInThisCls) AurumColors.Green else AurumColors.Cyan,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }

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
                        Pill("مارجین $${formatPrice(tradeForClass.effectiveMarginUsd)}", AurumColors.Gold)
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
                        text = "نماد در حال رصد آنلاین: $activeSymbol",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Pill("اهرم ${activeLeverage}x", AurumColors.Cyan)
                }
            }

            // نوار سطوح ورود، حد ضرر و حد سود اختصاصی روی چارت با اعشار دقیق
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
                    Text("ورود: ${formatPriceFor(activeSymbol, activeEntry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("SL: ${formatPriceFor(activeSymbol, activeSL)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("TP: ${formatPriceFor(activeSymbol, activeTP)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                    activeRR?.let { Text("R:R 1:${String.format(java.util.Locale.US, "%.1f", it)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan) }
                }
            }

            // پنجره اختصاصی تریدینگ‌ویو
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
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
                    widgetId = "chart_cat_${activeAssetClass.name}",
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

    val html = remember(tvSymbol, tvInterval, widgetId, entry, stopLoss, takeProfit, action) {
        tradingViewHtml(
            symbol = symbol,
            tvSymbol = tvSymbol,
            tvInterval = tvInterval,
            widgetId = widgetId,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            action = action,
            leverage = leverage,
            riskReward = riskReward,
        )
    }

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
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {}
                loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
        },
    )
}

private fun tradingViewHtml(
    symbol: String,
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
        val entryFormatted = formatPriceFor(symbol, entry)
        val slFormatted = formatPriceFor(symbol, stopLoss)
        val tpFormatted = formatPriceFor(symbol, takeProfit)
        val isBuy = action == SignalAction.BUY

        val tpY = if (isBuy) "20%" else "75%"
        val entryY = "48%"
        val slY = if (isBuy) "75%" else "20%"

        """
        <!-- Sleek Floating HUD & Thin Dynamic Dashed Lines -->
        <div style="position:absolute; top:8px; right:8px; z-index:900; pointer-events:none; display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end;">
          <span style="background:rgba(255,255,255,0.92); color:#0b0e13; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">ورود: $$entryFormatted</span>
          <span style="background:rgba(0,230,118,0.92); color:#0b0e13; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">TP: $$tpFormatted</span>
          <span style="background:rgba(255,82,82,0.92); color:#ffffff; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">SL: $$slFormatted</span>
        </div>
        <svg style="position:absolute; top:0; left:0; width:100%; height:100%; pointer-events:none; z-index:850; opacity:0.85;">
          <!-- Take Profit: Green Thin Dashed Line -->
          <line x1="0" y1="$tpY" x2="100%" y2="$tpY" stroke="#00e676" stroke-width="1.2" stroke-dasharray="4,4" />
          <!-- Entry Price: White Thin Dashed Line -->
          <line x1="0" y1="$entryY" x2="100%" y2="$entryY" stroke="#ffffff" stroke-width="1.2" stroke-dasharray="4,4" />
          <!-- Stop Loss: Red Thin Dashed Line -->
          <line x1="0" y1="$slY" x2="100%" y2="$slY" stroke="#ff5252" stroke-width="1.2" stroke-dasharray="4,4" />
        </svg>
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
