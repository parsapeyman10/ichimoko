package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval

/**
 * تبدیل یک سری زمانی به تایم‌فریم‌های بالاتر و بریدن «پنجرهٔ دید» بدون نگاه به آینده.
 *
 * این کلاس قلب درستیِ بک‌تست است: هر جا کوچک‌ترین اشتباهی در مرز پنجره رخ دهد، نتیجه
 * واقعی‌تر از واقعیت می‌شود (look-ahead bias) و آمار بی‌ارزش. پس همهٔ برش‌ها صریح و
 * آزمون‌پذیر نوشته شده‌اند.
 */
object BacktestSeries {

    /**
     * تجمیع کندل‌ها به تایم‌فریم بزرگ‌تر. هر کندل خروجی فقط از کندل‌هایی ساخته می‌شود که
     * **کاملاً** داخل بازهٔ آن هستند.
     */
    fun resample(candles: List<Candle>, target: Interval): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val sizeMs = target.millis
        val out = ArrayList<Candle>(candles.size / 4 + 1)
        var bucket = Long.MIN_VALUE
        var o = 0.0
        var h = 0.0
        var l = 0.0
        var c = 0.0
        var v = 0.0
        var started = false
        for (bar in candles) {
            val bucketStart = Math.floorDiv(bar.time, sizeMs) * sizeMs
            if (!started || bucketStart != bucket) {
                if (started) out.add(Candle(bucket, o, h, l, c, v, true))
                bucket = bucketStart
                o = bar.open
                h = bar.high
                l = bar.low
                c = bar.close
                v = bar.volume
                started = true
            } else {
                if (bar.high > h) h = bar.high
                if (bar.low < l) l = bar.low
                c = bar.close
                v += bar.volume
            }
        }
        if (started) out.add(Candle(bucket, o, h, l, c, v, true))
        return out
    }

    /**
     * کندل‌های **کاملاً بسته‌شده** تا لحظهٔ `nowMs`.
     *
     * یک کندل با شروع `t` و طول `sizeMs` در لحظهٔ `t + sizeMs` بسته می‌شود؛ پس کندلی که
     * `t + sizeMs > nowMs` باشد هنوز در حال شکل‌گیری است و نباید دیده شود.
     *
     * @param limit حداکثر تعداد کندل آخر (برای سرعت؛ ۰ = بی‌نهایت).
     */
    fun closedUpTo(candles: List<Candle>, sizeMs: Long, nowMs: Long, limit: Int = 0): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        var lo = 0
        var hi = candles.size
        // اولین ایندکسی که t + sizeMs > nowMs
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (candles[mid].time + sizeMs > nowMs) hi = mid else lo = mid + 1
        }
        val end = lo
        if (end <= 0) return emptyList()
        val start = if (limit > 0 && end - limit > 0) end - limit else 0
        return candles.subList(start, end)
    }

    /**
     * کندل‌هایی که کامل داخل بازهٔ `untilMs` تمام شده‌اند (برای نمایش چارت تا لحظهٔ فعلی).
     */
    fun barsUntil(candles: List<Candle>, sizeMs: Long, untilMs: Long, count: Int): List<Candle> {
        val closed = closedUpTo(candles, sizeMs, untilMs + sizeMs, 0)
        if (count <= 0 || closed.size <= count) return closed
        return closed.subList(closed.size - count, closed.size)
    }
}
