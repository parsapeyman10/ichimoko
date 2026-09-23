package com.aurum.edge.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.engine.Ichimoku
import com.aurum.edge.engine.Indicators
import com.aurum.edge.engine.SignalEngine
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native candlestick chart. Draws only real candles handed to it — no placeholder series.
 * Ichimoku / EMA200 / VWAP are overlaid for reading; the execution engine reads the
 * displacement-correct cloud and may therefore disagree with the drawn cloud on purpose.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    interval: Interval,
    signal: Signal?,
    modifier: Modifier = Modifier,
    showIchimoku: Boolean = true,
    showLevels: Boolean = true,
    showVolume: Boolean = true,
    onCrosshairChange: (Candle?) -> Unit = {},
) {
    if (candles.isEmpty()) {
        Box(modifier.background(AurumColors.ChartBg, RoundedCornerShape(10.dp)))
        return
    }

    val setting = remember(interval) { SignalEngine.ichimokuSetting(interval) }
    val ichimoku = remember(candles.size, candles.lastOrNull()?.time, interval) {
        Ichimoku.compute(candles, setting.tenkan, setting.kijun, setting.spanB, setting.kijun)
    }
    val ema200 = remember(candles.size, candles.lastOrNull()?.time) {
        Indicators.ema(candles.map { it.close }, 200)
    }
    val vwap = remember(candles.size, candles.lastOrNull()?.time) {
        Indicators.sessionVwap(candles)
    }

    var visibleCount by remember { mutableIntStateOf(if (interval == Interval.M1) 120 else 90) }
    var rightOffset by remember { mutableIntStateOf(6) }
    var crosshairX by remember { mutableFloatStateOf(-1f) }
    var crosshairY by remember { mutableFloatStateOf(-1f) }

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textSize = 26f
            typeface = Typeface.MONOSPACE
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AurumColors.ChartBg)
            .pointerInput(candles.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        visibleCount = (visibleCount / zoom).toInt().coerceIn(25, 400)
                    }
                    if (abs(pan.x) > 0.5f) {
                        val barsPerPixel = visibleCount.toFloat() / size.width
                        rightOffset = (rightOffset - (pan.x * barsPerPixel)).toInt().coerceIn(-visibleCount + 20, 400)
                    }
                }
            }
            .pointerInput(candles.size) {
                fun indexAt(x: Float): Int {
                    val lastIndex = candles.size - 1
                    val lastVisible = lastIndex + rightOffset
                    val firstVisible = lastVisible - visibleCount + 1
                    val plotWidth = (size.width - 72f).coerceAtLeast(10f)
                    return (firstVisible + (x / plotWidth) * visibleCount).toInt().coerceIn(0, lastIndex)
                }
                detectTapGestures(
                    onTap = { offset ->
                        if (crosshairX < 0f) {
                            crosshairX = offset.x; crosshairY = offset.y
                            onCrosshairChange(candles.getOrNull(indexAt(offset.x)))
                        } else {
                            crosshairX = -1f; crosshairY = -1f; onCrosshairChange(null)
                        }
                    },
                    onLongPress = { offset ->
                        crosshairX = offset.x; crosshairY = offset.y
                        onCrosshairChange(candles.getOrNull(indexAt(offset.x)))
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val priceHeight = if (showVolume) height * 0.80f else height
            val volumeTop = priceHeight + 6f
            val axisWidth = 72f
            val timeAxisHeight = 34f
            val plotWidth = (width - axisWidth).coerceAtLeast(10f)
            val plotBottom = priceHeight - timeAxisHeight
            val plotHeight = (plotBottom - 8f).coerceAtLeast(10f)

            val lastIndex = candles.size - 1
            val lastVisible = lastIndex + rightOffset
            val firstVisible = lastVisible - visibleCount + 1

            var minPrice = Double.MAX_VALUE
            var maxPrice = -Double.MAX_VALUE
            for (i in max(0, firstVisible)..min(lastIndex, lastVisible)) {
                minPrice = min(minPrice, candles[i].low)
                maxPrice = max(maxPrice, candles[i].high)
                if (showIchimoku) {
                    ichimoku.senkouA.getOrNull(i)?.let { minPrice = min(minPrice, it); maxPrice = max(maxPrice, it) }
                    ichimoku.senkouB.getOrNull(i)?.let { minPrice = min(minPrice, it); maxPrice = max(maxPrice, it) }
                }
            }
            if (signal?.stopLoss != null && showLevels) {
                minPrice = min(minPrice, signal.stopLoss)
                maxPrice = max(maxPrice, signal.stopLoss)
            }
            if (signal?.takeProfit != null && showLevels) {
                minPrice = min(minPrice, signal.takeProfit)
                maxPrice = max(maxPrice, signal.takeProfit)
            }
            if (!minPrice.isFinite() || !maxPrice.isFinite() || maxPrice <= minPrice) {
                minPrice = 0.0; maxPrice = 1.0
            }
            val pad = (maxPrice - minPrice) * 0.06
            minPrice -= pad; maxPrice += pad
            val range = (maxPrice - minPrice).coerceAtLeast(0.0001)

            fun xOf(index: Int): Float =
                ((index - firstVisible) + 0.5f) / visibleCount * plotWidth

            fun yOf(price: Double): Float =
                (plotHeight * (1.0 - (price - minPrice) / range)).toFloat() + 8f

            // ── grid + price axis ─────────────────────────────────────────
            val axisPaint = Paint().apply {
                color = 0xFF6B7280.toInt(); textSize = 24f; isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }
            val steps = 5
            for (s in 0..steps) {
                val y = 8f + plotHeight * s / steps
                drawLine(AurumColors.Grid, Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1f)
                val price = maxPrice - range * s / steps
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, "%,.2f", price), plotWidth + 6f, y + 8f, axisPaint,
                )
            }

            // ── volume pane ───────────────────────────────────────────────
            if (showVolume) {
                var maxVolume = 0.0
                for (i in max(0, firstVisible)..min(lastIndex, lastVisible)) maxVolume = max(maxVolume, candles[i].volume)
                if (maxVolume > 0.0) {
                    val volumeHeight = (height - volumeTop - 2f).coerceAtLeast(4f)
                    val barWidth = (plotWidth / visibleCount) * 0.65f
                    for (i in max(0, firstVisible)..min(lastIndex, lastVisible)) {
                        val c = candles[i]
                        val h = (volumeHeight * (c.volume / maxVolume)).toFloat()
                        val colour = if (c.close >= c.open) AurumColors.Green.copy(alpha = 0.30f) else AurumColors.Red.copy(alpha = 0.30f)
                        drawRect(
                            color = colour,
                            topLeft = Offset(xOf(i) - barWidth / 2f, volumeTop + volumeHeight - h),
                            size = Size(barWidth, h),
                        )
                    }
                }
            }

            // ── Ichimoku cloud (display alignment) ────────────────────────
            if (showIchimoku) {
                val cloudPath = Path()
                var started = false
                for (i in max(0, firstVisible)..min(lastIndex, lastVisible)) {
                    val a = ichimoku.senkouA.getOrNull(i) ?: continue
                    if (!started) {
                        cloudPath.moveTo(xOf(i), yOf(a)); started = true
                    } else cloudPath.lineTo(xOf(i), yOf(a))
                }
                for (i in min(lastIndex, lastVisible) downTo max(0, firstVisible)) {
                    val b = ichimoku.senkouB.getOrNull(i) ?: continue
                    cloudPath.lineTo(xOf(i), yOf(b))
                }
                if (started) {
                    cloudPath.close()
                    drawPath(cloudPath, color = AurumColors.Cyan.copy(alpha = 0.12f))
                }
            }

            // ── indicator lines ───────────────────────────────────────────
            fun drawSeries(values: List<Double?>, color: Color, strokeWidth: Float) {
                var previous: Offset? = null
                for (i in max(0, firstVisible - 1)..min(lastIndex, lastVisible)) {
                    val v = values.getOrNull(i)
                    if (v == null) {
                        previous = null
                        continue
                    }
                    val point = Offset(xOf(i), yOf(v))
                    previous?.let { drawLine(color, it, point, strokeWidth = strokeWidth) }
                    previous = point
                }
            }
            if (showIchimoku) {
                drawSeries(ichimoku.tenkan, AurumColors.Cyan, 2.4f)
                drawSeries(ichimoku.kijun, AurumColors.Purple, 2.4f)
            }
            if (showLevels) {
                drawSeries(vwap, AurumColors.Gold, 2.2f)
                drawSeries(ema200, AurumColors.TextSecondary, 2.0f)
            }

            // ── candles ───────────────────────────────────────────────────
            val candleWidth = (plotWidth / visibleCount) * 0.62f
            for (i in max(0, firstVisible)..min(lastIndex, lastVisible)) {
                val c = candles[i]
                val up = c.close >= c.open
                val colour = if (up) AurumColors.Green else AurumColors.Red
                val x = xOf(i)
                val bodyTop = yOf(max(c.open, c.close))
                val bodyBottom = yOf(min(c.open, c.close))
                drawLine(colour, Offset(x, yOf(c.high)), Offset(x, yOf(c.low)), strokeWidth = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawRect(
                    color = if (c.closed) colour else colour.copy(alpha = 0.55f),
                    topLeft = Offset(x - candleWidth / 2f, bodyTop),
                    size = Size(candleWidth, max(1.5f, bodyBottom - bodyTop)),
                )
            }

            // ── signal levels ─────────────────────────────────────────────
            if (showLevels && signal != null && signal.isActionable) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                fun levelLine(price: Double?, color: Color, label: String) {
                    if (price == null) return
                    val y = yOf(price)
                    drawLine(color.copy(alpha = 0.85f), Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1.6f, pathEffect = dash)
                    val labelPaint = Paint(axisPaint).apply { this.color = color.toArgbSafe() }
                    drawContext.canvas.nativeCanvas.drawText("$label ${formatPrice(price)}", 6f, y - 6f, labelPaint)
                }
                // These are a SIGNAL PLAN, not an executed paper/broker transaction.
                levelLine(signal.entry, AurumColors.Gold, "طرح ورود")
                levelLine(signal.stopLoss, AurumColors.Red, "طرح SL")
                levelLine(signal.takeProfit, AurumColors.Green, "طرح TP")
            }

            // ── time axis ─────────────────────────────────────────────────
            val labelStep = max(1, visibleCount / 5)
            var i = max(0, firstVisible)
            while (i <= min(lastIndex, lastVisible)) {
                val c = candles[i]
                val label = if (interval.minutes >= 60) {
                    dateFormat.format(Date(c.time))
                } else {
                    timeFormat.format(Date(c.time))
                }
                drawContext.canvas.nativeCanvas.drawText(label, xOf(i) - 22f, height - 10f, axisPaint)
                i += labelStep
            }

            // ── crosshair ─────────────────────────────────────────────────
            if (crosshairX >= 0f && crosshairX <= plotWidth) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                drawLine(AurumColors.TextSecondary, Offset(crosshairX, 0f), Offset(crosshairX, height), strokeWidth = 1.4f, pathEffect = dash)
                if (crosshairY in 0f..priceHeight) {
                    drawLine(AurumColors.TextSecondary, Offset(0f, crosshairY), Offset(plotWidth, crosshairY), strokeWidth = 1.4f, pathEffect = dash)
                    val price = maxPrice - (crosshairY - 8f) / plotHeight * range
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format(Locale.US, "%,.2f", price), plotWidth + 6f, crosshairY + 8f, axisPaint,
                    )
                }
            }
        }
    }
}

private fun Color.toArgbSafe(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)
