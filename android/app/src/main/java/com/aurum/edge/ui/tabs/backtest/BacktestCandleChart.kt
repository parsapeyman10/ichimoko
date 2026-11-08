package com.aurum.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * چارت کندلی بک‌تست — بدون WebView و بدون شبکه.
 *
 * چرا Canvas و نه TradingView؟ چون این چارت باید بداند «کدام معامله کجا باز و کجا بسته
 * شد» و باید با پیمایش همان دادهٔ CSV هم‌گام باشد؛ ویجت آماده چنین چیزی نمی‌دهد.
 *
 * سه درسی که در نسخهٔ ۳.۹.۵۷ از بازخورد کاربرد آمد و این‌جا اصلاح شد:
 *  ۱. **ایچیموکو روی کل سری محاسبه می‌شود، نه روی برش دید.** پیش‌تر خطوط روی همان ۴۰ تا
 *     ۶۰۰ کندلِ دیده‌شده حساب می‌شد؛ نتیجه این بود که ابتدای هر برش ۲۶ تا ۵۲ کندل بدون
 *     تنکان/کیجون/سنکو B می‌ماند و ابر با هر زوم/پیمایش از نو «متولد» می‌شد. الان سری کامل
 *     یک‌بار محاسبه و فقط برش داده می‌شود — همان چیزی که در GoCharting/TradingView می‌بینید.
 *  ۲. **ابر کومو دو رنگ است، نه یک لکه.** جایی که سنکو A بالای B است سبز و جایی که B بالای A
 *     است سرخ؛ و در محل `null` بریده می‌شود تا مسیر، شکاف را با خط مستقیم پر نکند.
 *  ۳. **نشانگر معامله با «زمان» تطبیق داده می‌شود، نه با برابری کندل.** پیش‌تر فقط وقتی نشانگر
 *     می‌افتاد که `entryTime` دقیقاً برابر `bar.time` باشد و اگر کندل خروج بیرون از برش بود هیچ
 *     خط SL/TP کشیده نمی‌شد. الان نقشهٔ زمان→ایندکس ساخته می‌شود و بازهٔ هر معامله سایه می‌خورد.
 *
 * تعامل: کشیدن افقی = پیمایش زمان، دو-انگشتی = بزرگ‌نمایی، تک‌ضربه = انتخاب کندل،
 * دو-ضربه = پرش به نزدیک‌ترین معامله.
 */
@Composable
internal fun BacktestCandleChart(
    candles: List<Candle>,
    trades: List<BacktestReplayEngine.Trade>,
    visibleCount: Int,
    endIndex: Int,
    onVisibleCountChange: (Int) -> Unit,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showIchimoku: Boolean = true,
    highlightIndex: Int = -1,
) {
    val textMeasurer = rememberTextMeasurer()
    var selected by remember { mutableStateOf(-1) }

    if (candles.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(260.dp).background(AurumColors.ChartBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("داده‌ای برای نمایش نیست", color = AurumColors.TextMuted, fontSize = 12.sp)
        }
        return
    }

    val safeEnd = endIndex.coerceIn(1, candles.size)
    val safeCount = visibleCount.coerceIn(MIN_VISIBLE, MAX_VISIBLE)
    val startIndex = (safeEnd - safeCount).coerceAtLeast(0)

    // ── محاسبهٔ ایچیموکو روی «کل» سری ─────────────────────────────────────
    // کلید کش فقط خودِ لیست است؛ زوم و پیمایش دیگر محاسبه را تکرار نمی‌کنند.
    val ichimokuAll: IchimokuLines? = remember(candles, showIchimoku) {
        if (!showIchimoku || candles.size < 60) null else buildIchimokuLines(candles)
    }
    // برش نمایش برای شمع‌ها و برای خطوط (اندیس‌ها هم‌تراز می‌مانند).
    val slice = candles.subList(startIndex, safeEnd)
    val ichimoku: IchimokuLines? = ichimokuAll?.sliceTo(startIndex, safeEnd)

    // نقشهٔ زمان→اندیس برای معامله‌ها؛ O(1) به‌جای indexOfFirst در هر کندل.
    val timeIndex: Map<Long, Int> = remember(candles) {
        val m = HashMap<Long, Int>(candles.size * 2)
        candles.forEachIndexed { i, c -> m.putIfAbsent(c.time, i) }
        m
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AurumColors.ChartBg)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom > 1.02f) onVisibleCountChange((safeCount * 0.85f).toInt())
                    else if (zoom < 0.98f) onVisibleCountChange((safeCount * 1.18f).toInt())
                    if (pan.x != 0f) {
                        val barPx = size.width / safeCount.toFloat()
                        val shift = (-pan.x / barPx).toInt()
                        if (shift != 0) onScrub(safeEnd - shift)
                    }
                }
            }
            .pointerInput(slice, trades) {
                detectTapGestures(
                    onTap = { offset ->
                        val barWidth = size.width / safeCount.toFloat()
                        val idx = (offset.x / barWidth).toInt().coerceIn(0, slice.lastIndex)
                        selected = startIndex + idx
                        onScrub(startIndex + idx + 1)
                    },
                    onDoubleTap = {
                        // پرش سریع به معاملهٔ بعدی/قبلی نسبت به کندل انتخاب‌شده.
                        val anchor = if (selected >= 0) selected else startIndex
                        val entryIdx = trades
                            .mapNotNull { timeIndex[it.entryTime] }
                            .sorted()
                        if (entryIdx.isNotEmpty()) {
                            val next = entryIdx.firstOrNull { it > anchor } ?: entryIdx.first()
                            onScrub((next + 1).coerceIn(1, candles.size))
                        }
                    },
                )
            }
            .pointerInput(slice) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val barPx = size.width / safeCount.toFloat()
                    val shift = (-drag.x / barPx).toInt()
                    if (shift != 0) onScrub(safeEnd - shift)
                }
            },
    ) {
        drawChart(
            slice = slice,
            startIndex = startIndex,
            fullSize = candles.size,
            ichimoku = ichimoku,
            trades = trades,
            timeIndex = timeIndex,
            textMeasurer = textMeasurer,
            highlightIndex = if (highlightIndex >= 0) highlightIndex else selected,
        )
    }
}

private const val MIN_VISIBLE = 40
private const val MAX_VISIBLE = 600

private data class IchimokuLines(
    val tenkan: List<Double?>,
    val kijun: List<Double?>,
    val spanA: List<Double?>,
    val spanB: List<Double?>,
) {
    /** برش هم‌تراز با کندل‌ها؛ هر چهار سری با هم بریده می‌شوند تا اندیس‌ها جابه‌جا نشوند. */
    fun sliceTo(from: Int, to: Int): IchimokuLines {
        val f = from.coerceIn(0, tenkan.size)
        val t = to.coerceIn(f, tenkan.size)
        return IchimokuLines(
            tenkan = tenkan.subList(f, t),
            kijun = kijun.subList(f, t),
            spanA = spanA.subList(f, t),
            spanB = spanB.subList(f, t),
        )
    }
}

/**
 * خطوط کامل ایچیموکو برای **کل** سری.
 *
 * دوره‌ها از تنظیمات پیش‌فرض موتور می‌آیند (۹/۲۶/۵۲ و جابه‌جایی ۲۶) تا چارت و استراتژی
 * دقیقاً یک چیز را نشان دهند. ابر با جابه‌جایی رو به آینده رسم می‌شود، پس `spanA/spanB` در
 * اندیس `i` مقدار محاسبه‌شده در `i - disp` است؛ درست مثل `currentSpanAAt` موتور.
 */
private fun buildIchimokuLines(bars: List<Candle>): IchimokuLines {
    val tenkanP = BasePeriods.TENKAN
    val kijunP = BasePeriods.KIJUN
    val spanBP = BasePeriods.SPAN_B
    val disp = BasePeriods.DISPLACEMENT
    val n = bars.size

    /** میانهٔ سقف/کف با صف یکنواخت — O(n) به‌جای O(n·period). */
    fun rollingMid(period: Int): DoubleArray {
        val hi = java.util.ArrayDeque<Int>()
        val lo = java.util.ArrayDeque<Int>()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            while (hi.isNotEmpty() && bars[hi.last()].high <= bars[i].high) hi.removeLast()
            hi.addLast(i)
            if (hi.first() < i - period + 1) hi.removeFirst()
            while (lo.isNotEmpty() && bars[lo.last()].low >= bars[i].low) lo.removeLast()
            lo.addLast(i)
            if (lo.first() < i - period + 1) lo.removeFirst()
            out[i] = (bars[hi.first()].high + bars[lo.first()].low) / 2.0
        }
        return out
    }

    val tenkanRaw = rollingMid(tenkanP)
    val kijunRaw = rollingMid(kijunP)
    val spanBRaw = rollingMid(spanBP)

    val tenkan = MutableList<Double?>(n) { null }
    val kijun = MutableList<Double?>(n) { null }
    val spanA = MutableList<Double?>(n) { null }
    val spanB = MutableList<Double?>(n) { null }
    for (i in 0 until n) {
        if (i >= tenkanP - 1) tenkan[i] = tenkanRaw[i]
        if (i >= kijunP - 1) kijun[i] = kijunRaw[i]
        // ابر در اندیس i از دادهٔ i - disp ساخته می‌شود (جابه‌جایی رو به آینده).
        val src = i - disp
        if (src >= kijunP - 1) spanA[i] = (tenkanRaw[src] + kijunRaw[src]) / 2.0
        if (src >= spanBP - 1) spanB[i] = spanBRaw[src]
    }
    return IchimokuLines(tenkan, kijun, spanA, spanB)
}

private object BasePeriods {
    const val TENKAN = 9
    const val KIJUN = 26
    const val SPAN_B = 52
    const val DISPLACEMENT = 26
}

private fun DrawScope.drawChart(
    slice: List<Candle>,
    startIndex: Int,
    fullSize: Int,
    ichimoku: IchimokuLines?,
    trades: List<BacktestReplayEngine.Trade>,
    timeIndex: Map<Long, Int>,
    textMeasurer: TextMeasurer,
    highlightIndex: Int,
) {
    val leftPad = 4.dp.toPx()
    val rightPad = 62.dp.toPx()
    val topPad = 8.dp.toPx()
    val bottomPad = 18.dp.toPx()
    val plotW = size.width - leftPad - rightPad
    val plotH = size.height - topPad - bottomPad
    if (plotW <= 0f || plotH <= 0f || slice.isEmpty()) return

    // معامله‌هایی که «بازه‌شان» با این برش تلاقی دارد (نه فقط آن‌هایی که ورودشان داخل برش است).
    val viewStart = startIndex
    val viewEnd = startIndex + slice.size - 1
    val visibleTrades = trades.mapNotNull { trade ->
        val entryIdx = timeIndex[trade.entryTime] ?: return@mapNotNull null
        val exitIdx = trade.exitTime?.let { timeIndex[it] } ?: (fullSize - 1)
        if (exitIdx < viewStart || entryIdx > viewEnd) return@mapNotNull null
        VisibleTrade(trade, entryIdx, exitIdx)
    }

    // محدودهٔ قیمت: کندل‌ها + ابر (فقط همان برش) + SL/TP معامله‌های دیده‌شده.
    var minPrice = slice.minOf { it.low }
    var maxPrice = slice.maxOf { it.high }
    ichimoku?.let { lines ->
        lines.spanA.forEach { v -> if (v != null) { if (v < minPrice) minPrice = v; if (v > maxPrice) maxPrice = v } }
        lines.spanB.forEach { v -> if (v != null) { if (v < minPrice) minPrice = v; if (v > maxPrice) maxPrice = v } }
    }
    visibleTrades.forEach { vt ->
        listOf(vt.trade.stopLoss, vt.trade.takeProfit, vt.trade.entryPrice).forEach { p ->
            if (p.isFinite() && p > 0.0) {
                if (p < minPrice) minPrice = p
                if (p > maxPrice) maxPrice = p
            }
        }
    }
    val pad = (maxPrice - minPrice) * 0.06
    minPrice -= pad
    maxPrice += pad
    val priceSpan = (maxPrice - minPrice).coerceAtLeast(1e-9)
    val barW = plotW / slice.size
    val bodyW = (barW * 0.62f).coerceAtLeast(1.2f)

    fun xOf(index: Int): Float = leftPad + index * barW + barW / 2f
    fun yOf(price: Double): Float = topPad + ((maxPrice - price) / priceSpan * plotH).toFloat()

    // ── سایهٔ بازهٔ معامله‌ها: «کجا معامله شد» با یک نگاه ──────────────────
    visibleTrades.forEach { vt ->
        val from = (vt.entryIdx - viewStart).coerceIn(0, slice.lastIndex)
        val to = (vt.exitIdx - viewStart).coerceIn(0, slice.lastIndex)
        val won = vt.trade.netPnlUsd >= 0.0
        val tint = if (won) AurumColors.Green else AurumColors.Red
        val x0 = xOf(from) - barW / 2f
        val x1 = xOf(to) + barW / 2f
        drawRect(
            color = tint.copy(alpha = 0.07f),
            topLeft = Offset(x0, topPad),
            size = Size((x1 - x0).coerceAtLeast(1f), plotH),
        )
        // خط عمودی ورود برای این‌که در زوم‌های دور هم دیده شود.
        drawLine(
            color = tint.copy(alpha = 0.35f),
            start = Offset(xOf(from), topPad),
            end = Offset(xOf(from), topPad + plotH),
            strokeWidth = 1f,
        )
    }

    // شبکهٔ افقی + برچسب قیمت
    val gridColor = AurumColors.Grid
    val labelStyle = TextStyle(color = AurumColors.TextMuted, fontSize = 9.sp)
    val rows = 4
    for (r in 0..rows) {
        val y = topPad + plotH * r / rows
        drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
        val price = maxPrice - priceSpan * r / rows
        drawText(
            textMeasurer = textMeasurer,
            text = formatPrice(price) ?: price.toString(),
            topLeft = Offset(leftPad + plotW + 4.dp.toPx(), y - 6.dp.toPx()),
            style = labelStyle,
        )
    }

    // ── ابر کومو: دو رنگ، و بریده در شکاف‌های null ────────────────────────
    ichimoku?.let { lines ->
        drawKumo(lines.spanA, lines.spanB, ::xOf, ::yOf)
        drawSeries(lines.spanA, ::xOf, ::yOf, AurumColors.Green.copy(alpha = 0.55f))
        drawSeries(lines.spanB, ::xOf, ::yOf, AurumColors.Red.copy(alpha = 0.55f))
        drawSeries(lines.tenkan, ::xOf, ::yOf, AurumColors.Gold.copy(alpha = 0.9f))
        drawSeries(lines.kijun, ::xOf, ::yOf, AurumColors.Purple.copy(alpha = 0.9f))
    }

    // شمع‌ها
    slice.forEachIndexed { i, bar ->
        val x = xOf(i)
        val up = bar.close >= bar.open
        val color = if (up) AurumColors.Green else AurumColors.Red
        drawLine(color, Offset(x, yOf(bar.high)), Offset(x, yOf(bar.low)), strokeWidth = 1f)
        val top = yOf(maxOf(bar.open, bar.close))
        val bottom = yOf(minOf(bar.open, bar.close))
        val h = (bottom - top).coerceAtLeast(1f)
        drawRect(color, topLeft = Offset(x - bodyW / 2f, top), size = Size(bodyW, h))
    }

    // ── نشانگر ورود/خروج ─────────────────────────────────────────────────
    visibleTrades.forEach { vt ->
        val trade = vt.trade
        val from = (vt.entryIdx - viewStart).coerceIn(0, slice.lastIndex)
        val to = (vt.exitIdx - viewStart).coerceIn(0, slice.lastIndex)
        val isBuy = trade.action == SignalAction.BUY
        val markerColor = if (isBuy) AurumColors.Green else AurumColors.Red

        // خطوط SL/TP از ورود تا خروج — حتی اگر کندل خروج بیرون از برش باشد، تا لبهٔ راست کشیده می‌شود.
        drawLine(
            color = AurumColors.Red.copy(alpha = 0.30f),
            start = Offset(xOf(from), yOf(trade.stopLoss)),
            end = Offset(xOf(to), yOf(trade.stopLoss)),
            strokeWidth = 1f,
        )
        drawLine(
            color = AurumColors.Green.copy(alpha = 0.30f),
            start = Offset(xOf(from), yOf(trade.takeProfit)),
            end = Offset(xOf(to), yOf(trade.takeProfit)),
            strokeWidth = 1f,
        )
        // خط ورود (قیمت ورود) نقطه‌چین‌گونه: خط نازک کم‌رنگ.
        drawLine(
            color = markerColor.copy(alpha = 0.45f),
            start = Offset(xOf(from), yOf(trade.entryPrice)),
            end = Offset(xOf(to), yOf(trade.entryPrice)),
            strokeWidth = 1f,
        )

        // پیکان ورود (همیشه در برش دیده می‌شود چون ورود داخل بازه است).
        val entryY = yOf(trade.entryPrice)
        val dir = if (isBuy) 1f else -1f
        val tri = Path().apply {
            moveTo(xOf(from), entryY + dir * 8.dp.toPx())
            lineTo(xOf(from) - 4.dp.toPx(), entryY + dir * 2.dp.toPx())
            lineTo(xOf(from) + 4.dp.toPx(), entryY + dir * 2.dp.toPx())
            close()
        }
        drawPath(tri, markerColor)

        // نشانگر خروج (اگر داخل برش باشد).
        if (!trade.isOpen) {
            val exitPrice = trade.exitPrice ?: slice.getOrNull(to)?.close ?: trade.entryPrice
            val won = trade.netPnlUsd >= 0.0
            drawCircle(
                color = if (won) AurumColors.Green else AurumColors.Red,
                radius = 3.2.dp.toPx(),
                center = Offset(xOf(to), yOf(exitPrice)),
            )
        }
    }

    // خط عمودی کندل انتخاب‌شده
    val hl = highlightIndex - viewStart
    if (hl in slice.indices) {
        drawLine(
            color = AurumColors.Gold.copy(alpha = 0.6f),
            start = Offset(xOf(hl), topPad),
            end = Offset(xOf(hl), topPad + plotH),
            strokeWidth = 1f,
        )
    }

    // محور زمان
    val timeStyle = TextStyle(color = AurumColors.TextMuted, fontSize = 8.sp)
    val step = (slice.size / 4).coerceAtLeast(1)
    var idx = 0
    while (idx < slice.size) {
        val x = xOf(idx)
        val label = formatShortTime(slice[idx].time)
        drawText(textMeasurer, label, Offset((x - 16.dp.toPx()).coerceAtLeast(leftPad), topPad + plotH + 3.dp.toPx()), timeStyle)
        idx += step
    }
}

private data class VisibleTrade(
    val trade: BacktestReplayEngine.Trade,
    val entryIdx: Int,
    val exitIdx: Int,
)

/**
 * ابر کومو با دو رنگ: سبز جایی که سنکو A بالای B است (روند صعودی) و سرخ جایی که B بالای A.
 *
 * پیاده‌سازی: ران‌های پیوستهٔ غیر-null پیدا می‌شوند و هر ران یک‌جا به بازه‌های رنگ‌همگن تقسیم
 * می‌شود؛ برای هر بازه یک مسیر بسته (رفت روی A، برگشت روی B) کشیده می‌شود. تقسیم‌بندی در محل
 * تقاطع، همان کاری است که کتاب هوسودا «کوموی سه‌خطی» می‌نامد و در GoCharting هم دیده می‌شود.
 */
private fun DrawScope.drawKumo(
    spanA: List<Double?>,
    spanB: List<Double?>,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
) {
    val n = minOf(spanA.size, spanB.size)
    var i = 0
    while (i < n) {
        // شروع یک ران پیوسته
        if (spanA[i] == null || spanB[i] == null) { i++; continue }
        var end = i
        while (end + 1 < n && spanA[end + 1] != null && spanB[end + 1] != null) end++
        // تقسیم ران به بازه‌های هم‌علامت
        var segStart = i
        var bull = (spanA[i]!! >= spanB[i]!!)
        for (k in i..end) {
            val a = spanA[k] ?: continue
            val b = spanB[k] ?: continue
            val isBull = a >= b
            if (k > segStart && isBull != bull) {
                fillKumoSegment(spanA, spanB, segStart, k, bull, xOf, yOf)
                segStart = k
            }
            bull = isBull
        }
        fillKumoSegment(spanA, spanB, segStart, end, bull, xOf, yOf)
        i = end + 1
    }
}

private fun DrawScope.fillKumoSegment(
    spanA: List<Double?>,
    spanB: List<Double?>,
    from: Int,
    to: Int,
    bullish: Boolean,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
) {
    if (to < from) return
    val path = Path()
    var started = false
    for (k in from..to) {
        val a = spanA.getOrNull(k) ?: continue
        val x = xOf(k)
        val y = yOf(a)
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    if (!started) return
    for (k in to downTo from) {
        val b = spanB.getOrNull(k) ?: continue
        path.lineTo(xOf(k), yOf(b))
    }
    path.close()
    val color = if (bullish) AurumColors.Green.copy(alpha = 0.12f) else AurumColors.Red.copy(alpha = 0.12f)
    drawPath(path, color = color)
}

private fun DrawScope.drawSeries(
    values: List<Double?>,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
    color: Color,
) {
    val stroke = Stroke(width = 1.1f)
    var path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) {
            // شکاف: مسیر فعلی تمام می‌شود تا خط، null را با پرش پر نکند.
            if (started) { drawPath(path, color, style = stroke); path = Path(); started = false }
            return@forEachIndexed
        }
        val x = xOf(i)
        val y = yOf(v)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (started) drawPath(path, color, style = stroke)
}

internal fun formatShortTime(millis: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    return if (hour == 0 && minute == 0) {
        String.format(java.util.Locale.US, "%02d/%02d", month, day)
    } else {
        String.format(java.util.Locale.US, "%02d/%02d %02d:%02d", month, day, hour, minute)
    }
}

/** نوار بالا/پایین چارت: راهنمای تعامل و اطلاعات کندل زیر انگشت. */
@Composable
internal fun BacktestChartLegend(
    candle: Candle?,
    intervalLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(AurumColors.Cyan))
        Text(
            text = if (candle == null) {
                "کشیدن = پیمایش · دو انگشت = بزرگ‌نمایی · ضربه = انتخاب کندل · دو-ضربه = پرش به معامله"
            } else {
                "$intervalLabel · ${formatShortTime(candle.time)} · O ${formatPrice(candle.open)} " +
                    "H ${formatPrice(candle.high)} L ${formatPrice(candle.low)} C ${formatPrice(candle.close)}"
            },
            color = AurumColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 2,
        )
    }
}
