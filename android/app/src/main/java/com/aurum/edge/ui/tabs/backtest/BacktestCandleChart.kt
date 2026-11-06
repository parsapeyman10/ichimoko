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
 * شد» و باید با پیمایش همان دادهٔ CSV هم‌گام باشد؛ ویجت آماده چنین چیزی نمی‌دهد. این
 * چارت فقط `List<Candle>` می‌کشد: شمع، ابر کومو، تنکان/کیجون و نشانگر ورود/خروج.
 *
 * تعامل: کشیدن افقی = پیمایش زمان، دو-انگشتی = بزرگ‌نمایی، تک‌ضربه = انتخاب کندل.
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
    val slice = candles.subList(startIndex, safeEnd)

    val ichimoku = remember(slice, showIchimoku) {
        if (!showIchimoku || slice.size < 60) emptyList()
        else buildIchimokuLines(slice)
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
            .pointerInput(slice) {
                detectTapGestures { offset ->
                    val barWidth = size.width / safeCount.toFloat()
                    val idx = (offset.x / barWidth).toInt().coerceIn(0, slice.lastIndex)
                    selected = startIndex + idx
                    onScrub(startIndex + idx + 1)
                }
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
            ichimoku = ichimoku,
            trades = trades,
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
)

/** خطوط ایچیموکو برای همان بازهٔ دید؛ دوره‌ها از تنظیمات پیش‌فرض موتور گرفته می‌شوند. */
private fun buildIchimokuLines(bars: List<Candle>): IchimokuLines {
    val tenkanP = BasePeriods.TENKAN
    val kijunP = BasePeriods.KIJUN
    val spanBP = BasePeriods.SPAN_B
    val disp = BasePeriods.DISPLACEMENT
    fun mid(from: Int, to: Int): Double {
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (i in from..to) {
            if (bars[i].high > hi) hi = bars[i].high
            if (bars[i].low < lo) lo = bars[i].low
        }
        return (hi + lo) / 2.0
    }
    val tenkan = MutableList<Double?>(bars.size) { null }
    val kijun = MutableList<Double?>(bars.size) { null }
    val spanA = MutableList<Double?>(bars.size) { null }
    val spanB = MutableList<Double?>(bars.size) { null }
    for (i in bars.indices) {
        if (i >= tenkanP - 1) tenkan[i] = mid(i - tenkanP + 1, i)
        if (i >= kijunP - 1) kijun[i] = mid(i - kijunP + 1, i)
        val target = i - disp
        if (target >= 0 && tenkan[target] != null && kijun[target] != null) {
            spanA[i] = (tenkan[target]!! + kijun[target]!!) / 2.0
        }
        if (target >= spanBP - 1) spanB[i] = mid(target - spanBP + 1, target)
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
    ichimoku: List<IchimokuLines>,
    trades: List<BacktestReplayEngine.Trade>,
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

    // محدودهٔ قیمت: کندل‌ها + ابر آینده تا از کادر بیرون نزند.
    var minPrice = slice.minOf { it.low }
    var maxPrice = slice.maxOf { it.high }
    ichimoku.firstOrNull()?.let { lines ->
        lines.spanA.forEach { v -> if (v != null) { if (v < minPrice) minPrice = v; if (v > maxPrice) maxPrice = v } }
        lines.spanB.forEach { v -> if (v != null) { if (v < minPrice) minPrice = v; if (v > maxPrice) maxPrice = v } }
    }
    trades.filter { trade -> trade.entryTime >= slice.first().time && trade.entryTime <= slice.last().time }
        .forEach { trade ->
            if (trade.stopLoss < minPrice) minPrice = trade.stopLoss
            if (trade.stopLoss > maxPrice) maxPrice = trade.stopLoss
            if (trade.takeProfit < minPrice) minPrice = trade.takeProfit
            if (trade.takeProfit > maxPrice) maxPrice = trade.takeProfit
        }
    val pad = (maxPrice - minPrice) * 0.06
    minPrice -= pad
    maxPrice += pad
    val priceSpan = (maxPrice - minPrice).coerceAtLeast(1e-9)
    val barW = plotW / slice.size
    val bodyW = (barW * 0.62f).coerceAtLeast(1.2f)

    fun xOf(index: Int): Float = leftPad + index * barW + barW / 2f
    fun yOf(price: Double): Float = topPad + ((maxPrice - price) / priceSpan * plotH).toFloat()

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

    // ابر کومو
    ichimoku.firstOrNull()?.let { lines ->
        val cloudPath = Path()
        var started = false
        for (i in slice.indices) {
            val a = lines.spanA.getOrNull(i) ?: continue
            val b = lines.spanB.getOrNull(i) ?: continue
            val x = xOf(i)
            if (!started) {
                cloudPath.moveTo(x, yOf(a))
                started = true
            } else {
                cloudPath.lineTo(x, yOf(a))
            }
        }
        for (i in slice.indices.reversed()) {
            val a = lines.spanA.getOrNull(i) ?: continue
            val b = lines.spanB.getOrNull(i) ?: continue
            cloudPath.lineTo(xOf(i), yOf(b))
        }
        if (started) {
            cloudPath.close()
            drawPath(cloudPath, color = AurumColors.Cyan.copy(alpha = 0.08f))
        }
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

    // نشانگر ورود/خروج معامله‌ها
    slice.forEachIndexed { i, bar ->
        val absIndex = startIndex + i
        trades.filter { trade ->
            trade.entryTime == bar.time || trade.exitTime == bar.time
        }.forEach { trade ->
            val isEntry = trade.entryTime == bar.time
            val price = if (isEntry) trade.entryPrice else (trade.exitPrice ?: bar.close)
            val y = yOf(price)
            val markerColor = when {
                !isEntry -> AurumColors.TextSecondary
                trade.action == SignalAction.BUY -> AurumColors.Green
                else -> AurumColors.Red
            }
            val r = 3.2.dp.toPx()
            drawCircle(markerColor, r, Offset(xOf(i), y))
            if (isEntry) {
                val dir = if (trade.action == SignalAction.BUY) 1f else -1f
                val tri = Path().apply {
                    moveTo(xOf(i), y + dir * 8.dp.toPx())
                    lineTo(xOf(i) - 4.dp.toPx(), y + dir * 2.dp.toPx())
                    lineTo(xOf(i) + 4.dp.toPx(), y + dir * 2.dp.toPx())
                    close()
                }
                drawPath(tri, markerColor)
            }
            // خطوط حد ضرر/حد سود تا نقطهٔ خروج
            val exitIdx = slice.indexOfFirst { it.time == trade.exitTime }
            if (isEntry && exitIdx >= 0) {
                drawLine(
                    color = AurumColors.Red.copy(alpha = 0.25f),
                    start = Offset(xOf(i), yOf(trade.stopLoss)),
                    end = Offset(xOf(exitIdx), yOf(trade.stopLoss)),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = AurumColors.Green.copy(alpha = 0.25f),
                    start = Offset(xOf(i), yOf(trade.takeProfit)),
                    end = Offset(xOf(exitIdx), yOf(trade.takeProfit)),
                    strokeWidth = 1f,
                )
            }
            if (absIndex == highlightIndex) {
                drawLine(AurumColors.Gold.copy(alpha = 0.6f), Offset(xOf(i), topPad), Offset(xOf(i), topPad + plotH), strokeWidth = 1f)
            }
        }
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

private fun DrawScope.drawSeries(
    values: List<Double?>,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
    color: Color,
) {
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x = xOf(i)
        val y = yOf(v)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (started) drawPath(path, color, style = Stroke(width = 1.1f))
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
                "کشیدن = پیمایش · دو انگشت = بزرگ‌نمایی · ضربه = انتخاب کندل"
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
