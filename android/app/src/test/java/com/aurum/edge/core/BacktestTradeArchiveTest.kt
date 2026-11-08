package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * آزمون‌های «آرشیو معاملاتی بک‌تست» — قرارداد ۳.۹.۵۸.
 *
 * آنچه این‌جا قفل می‌شود:
 * ۱. هر اجرا آرشیو با فهرست کامل معامله‌ها می‌دهد (چیزی جا نمی‌افتد).
 * ۲. سود به **روز بسته‌شدن** نسبت داده می‌شود، نه روز ورود.
 * ۳. شناسهٔ محتوایی برای اجرای یکسان یکی است (پس دوباره اعمال نمی‌شود) و با تغییر داده/تنظیم
 *    عوض می‌شود (پس اعمال تازه مجاز است).
 * ۴. CSV و JSON هر دو از همان ردیف‌ها ساخته می‌شوند.
 */
class BacktestTradeArchiveTest {

    private fun series(bars: Int = 900, seed: Long = 21L): List<Candle> {
        var s = seed
        fun r(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
        val out = ArrayList<Candle>(bars)
        var price = 2400.0
        var drift = 0.0
        var vol = 0.0018
        var regimeLeft = 0
        val m15 = Interval.M15.millis
        for (i in 0 until bars) {
            if (regimeLeft-- <= 0) {
                regimeLeft = 120 + (abs(r()) * 200).toInt()
                drift = when ((abs(r()) * 4).toInt()) {
                    0 -> 0.00045
                    1 -> 0.0
                    2 -> -0.00040
                    else -> 0.00018
                }
                vol = 0.0012 + abs(r()) * 0.0014
            }
            val open = price
            val close = open * (1.0 + drift + r() * vol)
            val hi = maxOf(open, close) * (1.0 + abs(r()) * vol * 0.7)
            val lo = minOf(open, close) * (1.0 - abs(r()) * vol * 0.7)
            out += Candle(i * m15, open, hi, lo, close, 1000.0, true)
            price = close
        }
        return out
    }

    private fun runOn(candles: List<Candle>, risk: Double = 0.5): BacktestReplayEngine.Result =
        BacktestReplayEngine.run(
            candles,
            BacktestReplayEngine.Config(
                strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
                symbol = "XAU/USD",
                startEquityUsd = 1_000.0,
                riskPercent = risk,
                warmupBars = 120,
                includeHiddenCrossLayer = false,
            ),
        )

    @Test
    fun `every closed trade appears in the archive rows and in the csv`() {
        val res = runOn(series())
        val built = BacktestTradeArchive.build(res, "ds-1", "xauusd-m15.csv")
        assertEquals("همهٔ معامله‌ها باید در آرشیو باشند", res.trades.size, built.archive.rows.size)
        val csvLines = built.csv.trim().lines()
        assertTrue("CSV باید سرستون داشته باشد", csvLines.first().contains("قیمت ورود"))
        // هر ردیف معامله یک خط CSV است (به‌علاوهٔ چند خط خلاصه در پایان).
        assertTrue("ردیف‌های CSV کمتر از معامله‌هاست", csvLines.size >= built.archive.rows.size)
        if (res.trades.isNotEmpty()) {
            assertTrue(built.csv.contains(built.archive.contentHash))
            assertTrue(built.json.contains("\"trades\""))
        }
    }

    @Test
    fun `daily buckets follow the exit day not the entry day`() {
        val res = runOn(series())
        val trades = res.trades.filter { !it.isOpen && it.exitTime != null }
        val buckets = BacktestTradeArchive.dailyBuckets(res.trades)
        assertEquals(
            "تعداد روزها باید همان تعداد روزهای بسته‌شدن متمایز باشد",
            trades.map { BacktestTradeArchive.dayOf(it.exitTime!!) }.distinct().size,
            buckets.size,
        )
        // مجموع سود روزها باید دقیقاً همان سود خالص کل باشد (بدون گم‌شدن سنت).
        val sumDays = buckets.sumOf { it.netUsd }
        val sumTrades = trades.sumOf { it.netPnlUsd }
        assertTrue("مجموع روزانه باید سود کل را پوشش دهد", abs(sumDays - sumTrades) < 0.05)
        // هیچ روزی نباید صفر معامله داشته باشد.
        assertTrue(buckets.all { it.trades > 0 })
    }

    @Test
    fun `same run gives the same content hash`() {
        val candles = series()
        val a = BacktestTradeArchive.build(runOn(candles), "ds-1", "f.csv", candles = candles)
        val b = BacktestTradeArchive.build(runOn(candles), "ds-1", "f.csv", candles = candles)
        assertEquals(
            "دو اجرای یکسان باید همان شناسه را بدهند تا دوباره اعمال نشوند",
            a.archive.contentHash,
            b.archive.contentHash,
        )
    }

    @Test
    fun `different data or risk percent changes the content hash`() {
        val s1 = series()
        val s2 = series(seed = 99L)
        val base = BacktestTradeArchive.build(runOn(s1), "ds-1", "f.csv", candles = s1).archive.contentHash
        val otherData = BacktestTradeArchive.build(
            runOn(s2), "ds-1", "f.csv", candles = s2,
        ).archive.contentHash
        val otherRisk = BacktestTradeArchive.build(
            runOn(s1, risk = 1.0), "ds-1", "f.csv", candles = s1,
        ).archive.contentHash
        assertNotEquals("دادهٔ متفاوت = اجرای متفاوت", base, otherData)
        assertNotEquals("ریسک متفاوت = اجرای متفاوت", base, otherRisk)
    }

    @Test
    fun `archive with no trades still builds and reports zero`() {
        // سری کاملاً تخت: هیچ ورودی معتبری ساخته نمی‌شود.
        val flat = (0 until 400).map { i ->
            Candle(i * Interval.M15.millis, 2400.0, 2400.05, 2399.95, 2400.0, 10.0, true)
        }
        val res = runOn(flat)
        val built = BacktestTradeArchive.build(res, "ds-flat", "flat.csv", candles = flat)
        assertEquals(0, built.archive.metrics.trades)
        assertEquals(0.0, built.archive.metrics.netUsd, 1e-9)
        assertTrue(built.archive.days.isEmpty())
        assertTrue(built.archive.contentHash.isNotBlank())
        assertFalse(built.csv.isBlank())
    }

    @Test
    fun `two datasets without any trade never share a content hash`() {
        // این حالت واقعی است: دو فایل مختلف که هر دو هیچ ورودی ندارند. اگر شناسه از فهرست
        // (خالی) معامله‌ها ساخته شود، یکی «تکراری» اعلام می‌شود و دومی هرگز اعمال نمی‌شود.
        val a = series(seed = 21L)
        val b = series(seed = 77L)
        val ha = BacktestTradeArchive.build(runOn(a), "ds", "a.csv", candles = a).archive.contentHash
        val hb = BacktestTradeArchive.build(runOn(b), "ds", "b.csv", candles = b).archive.contentHash
        assertNotEquals("دادهٔ متفاوت نباید شناسهٔ یکسان بگیرد", ha, hb)
    }

    @Test
    fun `series fingerprint is cheap and changes with the data`() {
        val a = series(seed = 5L)
        val b = series(seed = 6L)
        assertNotEquals(
            BacktestTradeArchive.seriesFingerprint(a),
            BacktestTradeArchive.seriesFingerprint(b),
        )
        val t0 = System.nanoTime()
        repeat(50) { BacktestTradeArchive.seriesFingerprint(a) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("اثر انگشت داده باید ارزان باشد، نه یک هش کامل: ${ms}ms", ms < 1_500)
    }

    @Test
    fun `equity after each trade compounds along the curve`() {
        val res = runOn(series())
        val closed = res.trades.filter { !it.isOpen }
        if (closed.size >= 2) {
            // اولین معامله از سرمایهٔ شروع، و هر بعدی از سرمایهٔ قبلی + سود خودش.
            val first = closed.first()
            assertTrue("سرمایهٔ بعد از اولین معامله نباید صفر بماند", first.equityAfterUsd > 0.0)
            assertEquals(1000.0 + first.netPnlUsd, first.equityAfterUsd, 0.05)
            for (i in 1 until closed.size) {
                val prev = closed[i - 1]
                val cur = closed[i]
                assertEquals(
                    "سرمایهٔ هر معامله = سرمایهٔ قبلی + سود خودش",
                    prev.equityAfterUsd + cur.netPnlUsd,
                    cur.equityAfterUsd,
                    0.06,
                )
            }
            assertEquals(res.finalEquityUsd, closed.last().equityAfterUsd, 0.06)
        }
    }

    @Test
    fun `trade numbers are sequential so the archive has addressable rows`() {
        val res = runOn(series())
        if (res.trades.size >= 2) {
            assertEquals(1, res.trades.first().index)
            assertEquals(res.trades.size, res.trades.last().index)
            val nums = res.trades.map { it.index }
            assertEquals(nums.distinct().size, nums.size)
        }
    }
}
