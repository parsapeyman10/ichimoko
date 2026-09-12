package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure indicator math over *real* closed candles.
 * Every function returns a list aligned with the input (null where there is not enough history),
 * so a value at index i depends only on bars 0..i — no look-ahead.
 */
object Indicators {

    fun donchianMid(candles: List<Candle>, period: Int): List<Double?> {
        if (period <= 0) return List(candles.size) { null }
        val out = MutableList<Double?>(candles.size) { null }
        for (i in candles.indices) {
            if (i < period - 1) continue
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) {
                hi = max(hi, candles[j].high)
                lo = min(lo, candles[j].low)
            }
            out[i] = (hi + lo) / 2.0
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        if (values.isEmpty() || period <= 1) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        val k = 2.0 / (period + 1)
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    fun rsi(candles: List<Candle>, period: Int = 7): List<Double?> {
        val out = MutableList<Double?>(candles.size) { null }
        if (candles.size <= period) return out
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val d = candles[i].close - candles[i - 1].close
            if (d > 0) gains += d else losses -= d
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        out[period] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
        for (i in period + 1 until candles.size) {
            val d = candles[i].close - candles[i - 1].close
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
            out[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
        }
        return out
    }

    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(candles.size) { null }
        if (candles.size <= period) return out
        val tr = DoubleArray(candles.size)
        for (i in candles.indices) {
            tr[i] = if (i == 0) {
                candles[0].high - candles[0].low
            } else {
                max(
                    candles[i].high - candles[i].low,
                    max(abs(candles[i].high - candles[i - 1].close), abs(candles[i].low - candles[i - 1].close)),
                )
            }
        }
        var value = tr.take(period).average()
        out[period - 1] = value
        for (i in period until candles.size) {
            value = (value * (period - 1) + tr[i]) / period
            out[i] = value
        }
        return out
    }

    fun macdHistogram(candles: List<Candle>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<Double?> {
        val closes = candles.map { it.close }
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val line = closes.indices.map { i ->
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f == null || s == null) null else f - s
        }
        val compact = line.filterNotNull()
        val signalEma = ema(compact, signal)
        val out = MutableList<Double?>(candles.size) { null }
        var idx = 0
        for (i in line.indices) {
            val value = line[i] ?: continue
            val sig = signalEma.getOrNull(idx)
            if (sig != null) out[i] = value - sig
            idx++
        }
        return out
    }

    fun bollinger(candles: List<Candle>, period: Int = 20, mult: Double = 2.0): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val upper = MutableList<Double?>(candles.size) { null }
        val lower = MutableList<Double?>(candles.size) { null }
        val mid = MutableList<Double?>(candles.size) { null }
        for (i in candles.indices) {
            if (i < period - 1) continue
            val window = candles.subList(i - period + 1, i + 1).map { it.close }
            val mean = window.average()
            val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / period)
            mid[i] = mean
            upper[i] = mean + mult * sd
            lower[i] = mean - mult * sd
        }
        return Triple(upper, mid, lower)
    }

    fun adx(candles: List<Candle>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(candles.size) { null }
        if (candles.size <= period * 2) return out
        val plusDm = DoubleArray(candles.size)
        val minusDm = DoubleArray(candles.size)
        val tr = DoubleArray(candles.size)
        for (i in 1 until candles.size) {
            val up = candles[i].high - candles[i - 1].high
            val down = candles[i - 1].low - candles[i].low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
            tr[i] = max(
                candles[i].high - candles[i].low,
                max(abs(candles[i].high - candles[i - 1].close), abs(candles[i].low - candles[i - 1].close)),
            )
        }
        var trSum = tr.drop(1).take(period).sum()
        var plusSum = plusDm.drop(1).take(period).sum()
        var minusSum = minusDm.drop(1).take(period).sum()
        val dx = DoubleArray(candles.size)
        for (i in period + 1 until candles.size) {
            trSum = trSum - trSum / period + tr[i]
            plusSum = plusSum - plusSum / period + plusDm[i]
            minusSum = minusSum - minusSum / period + minusDm[i]
            if (trSum <= 0.0) continue
            val plusDi = 100.0 * plusSum / trSum
            val minusDi = 100.0 * minusSum / trSum
            val sum = plusDi + minusDi
            dx[i] = if (sum == 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / sum
        }
        var adxValue: Double? = null
        val firstAdxIndex = period * 2
        if (firstAdxIndex < candles.size) {
            adxValue = dx.drop(period + 1).take(period).average()
            out[firstAdxIndex] = adxValue
            for (i in firstAdxIndex + 1 until candles.size) {
                adxValue = ((adxValue ?: 0.0) * (period - 1) + dx[i]) / period
                out[i] = adxValue
            }
        }
        return out
    }

    /** Session VWAP, reset at 00:00 UTC. Falls back to a plain typical-price average when the feed reports no volume. */
    fun sessionVwap(candles: List<Candle>, sessionMillis: Long = 86_400_000L): List<Double> {
        val out = MutableList(candles.size) { 0.0 }
        var pv = 0.0
        var vol = 0.0
        var priceSum = 0.0
        var count = 0
        var sessionStart = Long.MIN_VALUE
        for (i in candles.indices) {
            val c = candles[i]
            val session = c.time / sessionMillis
            if (session != sessionStart) {
                sessionStart = session
                pv = 0.0; vol = 0.0; priceSum = 0.0; count = 0
            }
            val typical = (c.high + c.low + c.close) / 3.0
            pv += typical * c.volume
            vol += c.volume
            priceSum += typical
            count++
            out[i] = when {
                vol > 0.0 -> pv / vol
                count > 0 -> priceSum / count
                else -> c.close
            }
        }
        return out
    }

    fun relativeVolume(candles: List<Candle>, period: Int = 30): List<Double?> {
        val out = MutableList<Double?>(candles.size) { null }
        for (i in candles.indices) {
            if (i < period) continue
            val avg = candles.subList(i - period, i).map { it.volume }.average()
            out[i] = if (avg <= 0.0) null else candles[i].volume / avg
        }
        return out
    }
}

/**
 * Ichimoku 7/22/44 (1m) or 9/26/52 (5m+).
 * `senkouA/B` are stored at their computation index; the execution-safe cloud for bar i
 * is read from `cloudAt(i)`, which uses the values computed `displacement` bars earlier.
 */
data class Ichimoku(
    val tenkan: List<Double?>,
    val kijun: List<Double?>,
    val senkouA: List<Double?>,
    val senkouB: List<Double?>,
    val displacement: Int,
) {
    /** Cloud as it is actually visible at bar [index] (no look-ahead). */
    fun cloudAt(index: Int): Pair<Double, Double>? {
        val i = index - displacement
        if (i < 0) return null
        val a = senkouA.getOrNull(i) ?: return null
        val b = senkouB.getOrNull(i) ?: return null
        return a to b
    }

    companion object {
        fun compute(candles: List<Candle>, tenkanPeriod: Int, kijunPeriod: Int, spanBPeriod: Int, displacement: Int): Ichimoku {
            val tenkan = Indicators.donchianMid(candles, tenkanPeriod)
            val kijun = Indicators.donchianMid(candles, kijunPeriod)
            val spanB = Indicators.donchianMid(candles, spanBPeriod)
            val spanA = MutableList<Double?>(candles.size) { null }
            for (i in candles.indices) {
                val t = tenkan[i]
                val k = kijun[i]
                if (t != null && k != null) spanA[i] = (t + k) / 2.0
            }
            return Ichimoku(tenkan, kijun, spanA, spanB, displacement)
        }
    }
}
