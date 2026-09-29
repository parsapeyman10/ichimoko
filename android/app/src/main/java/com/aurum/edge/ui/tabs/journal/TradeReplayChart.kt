package com.aurum.edge.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TradeReplay
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Static picture of ONE recorded paper trade on the real candles this phone verified.
 *
 * Every bar comes from [TradeReplay.window]; nothing is generated to fill a hole. The horizontal
 * levels are read from the stored journal record (entry / SL / TP / exit), so they describe what
 * was saved on this device — not a broker fill and not a new signal. Latin marker labels are used
 * on purpose: the canvas text layer distorts Persian glyphs, so wording stays in the card around
 * the chart.
 */
@Composable
fun TradeReplayChart(
    trade: PaperTrade,
    window: TradeReplay.Window,
    modifier: Modifier = Modifier,
) {
    val bars = window.bars
    if (bars.size < 2) {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(AurumColors.ChartBg))
        return
    }
    val timeFormat = remember(trade.interval) {
        SimpleDateFormat(if (trade.interval.minutes >= 60) "MM-dd HH:mm" else "HH:mm", Locale.US)
    }

    Box(modifier.clip(RoundedCornerShape(10.dp)).background(AurumColors.ChartBg)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val axisWidth = 78f
            val timeAxisHeight = 28f
            val plotWidth = (size.width - axisWidth).coerceAtLeast(10f)
            val plotTop = 10f
            val plotBottom = (size.height - timeAxisHeight).coerceAtLeast(plotTop + 20f)
            val plotHeight = plotBottom - plotTop

            var minPrice = Double.MAX_VALUE
            var maxPrice = -Double.MAX_VALUE
            for (bar in bars) {
                minPrice = min(minPrice, bar.low)
                maxPrice = max(maxPrice, bar.high)
            }
            val levels = listOf(trade.entry, trade.stopLoss, trade.takeProfit) + listOfNotNull(trade.exitPrice)
            for (level in levels) {
                if (level.isFinite() && level > 0.0) {
                    minPrice = min(minPrice, level)
                    maxPrice = max(maxPrice, level)
                }
            }
            if (!minPrice.isFinite() || !maxPrice.isFinite() || maxPrice <= minPrice) {
                minPrice = 0.0
                maxPrice = 1.0
            }
            val padding = (maxPrice - minPrice) * 0.08
            minPrice -= padding
            maxPrice += padding
            val range = (maxPrice - minPrice).coerceAtLeast(0.0001)

            fun xOf(index: Int): Float = ((index + 0.5f) / bars.size) * plotWidth
            fun yOf(price: Double): Float = plotTop + (plotHeight * (1.0 - (price - minPrice) / range)).toFloat()

            val axisPaint = Paint().apply {
                color = 0xFF6B7280.toInt()
                textSize = 22f
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }

            // ── grid + price axis ────────────────────────────────────────
            for (step in 0..4) {
                val y = plotTop + plotHeight * step / 4
                drawLine(AurumColors.Grid, Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, "%,.2f", maxPrice - range * step / 4),
                    plotWidth + 6f, y + 7f, axisPaint,
                )
            }

            // ── real candles only ────────────────────────────────────────
            val candleWidth = (plotWidth / bars.size) * 0.62f
            bars.forEachIndexed { index, bar ->
                val colour = if (bar.close >= bar.open) AurumColors.Green else AurumColors.Red
                val x = xOf(index)
                val bodyTop = yOf(max(bar.open, bar.close))
                val bodyBottom = yOf(min(bar.open, bar.close))
                drawLine(colour, Offset(x, yOf(bar.high)), Offset(x, yOf(bar.low)), strokeWidth = 1.4f)
                drawRect(
                    color = colour,
                    topLeft = Offset(x - candleWidth / 2f, bodyTop),
                    size = Size(max(0.9f, candleWidth), max(1.4f, bodyBottom - bodyTop)),
                )
            }

            val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)

            // ── levels stored in the journal record ──────────────────────
            fun level(price: Double?, colour: Color, label: String) {
                if (price == null || !price.isFinite() || price <= 0.0) return
                val y = yOf(price)
                if (y < plotTop || y > plotBottom) return
                drawLine(colour.copy(alpha = 0.85f), Offset(0f, y), Offset(plotWidth, y),
                    strokeWidth = 1.6f, pathEffect = dash)
                val paint = Paint(axisPaint).apply { color = argbOf(colour) }
                drawContext.canvas.nativeCanvas.drawText("$label ${formatPrice(price)}", 6f, y - 5f, paint)
            }
            level(trade.entry, AurumColors.Gold, "ENTRY")
            level(trade.stopLoss, AurumColors.Red, "SL")
            level(trade.takeProfit, AurumColors.Green, "TP")
            level(trade.exitPrice, AurumColors.Cyan, "EXIT")

            // ── the bars where the record says it opened / closed ────────
            fun marker(barTime: Long?, colour: Color, label: String) {
                if (barTime == null) return
                val index = bars.indexOfFirst { it.time == barTime }
                if (index < 0) return
                val x = xOf(index)
                drawLine(colour.copy(alpha = 0.7f), Offset(x, plotTop), Offset(x, plotBottom),
                    strokeWidth = 1.2f, pathEffect = dash)
                val paint = Paint(axisPaint).apply {
                    color = argbOf(colour)
                    textSize = 20f
                }
                val textX = (x - 16f).coerceIn(0f, (plotWidth - 44f).coerceAtLeast(0f))
                drawContext.canvas.nativeCanvas.drawText(label, textX, plotTop + 16f, paint)
            }
            marker(
                window.entryBarTime,
                if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                if (trade.action == SignalAction.BUY) "IN-L" else "IN-S",
            )
            marker(window.exitBarTime, AurumColors.Cyan, "OUT")

            // ── time axis ────────────────────────────────────────────────
            val labelStep = max(1, bars.size / 4)
            var index = 0
            while (index < bars.size) {
                drawContext.canvas.nativeCanvas.drawText(
                    timeFormat.format(Date(bars[index].time)),
                    (xOf(index) - 22f).coerceAtLeast(0f), size.height - 6f, axisPaint,
                )
                index += labelStep
            }
        }
    }
}

private fun argbOf(color: Color): Int = android.graphics.Color.argb(
    (color.alpha * 255).toInt().coerceIn(0, 255),
    (color.red * 255).toInt().coerceIn(0, 255),
    (color.green * 255).toInt().coerceIn(0, 255),
    (color.blue * 255).toInt().coerceIn(0, 255),
)
