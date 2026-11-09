package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون اتصال گیت عصبی به بک‌تست — همان باگ «با مانع و بدون مانع یکی بود».
 *
 * ## چه چیزی خراب بود
 * کلید «مانع» در بخش هوش عصبی، وضعیت گیت را عوض می‌کرد و در مسیر زنده هم اثر داشت،
 * اما `BacktestReplayEngine` هیچ‌جا گیت را صدا نمی‌زد. پس بک‌تست با مانع و بدون مانع
 * **بیت‌به‌بیت** یکی بود — نه به‌خاطر اینکه گیت بی‌فایده است، بلکه چون وصل نبود.
 *
 * این فایل همان اتصال را قفل می‌کند: یک گیتی که همه را مسدود می‌کند باید صفر معامله
 * بدهد، و یک گیت بی‌اثر باید دقیقاً همان نتیجهٔ بدون گیت را بدهد.
 */
class BacktestNeuralGateWiringTest {

    private fun series(n: Int = 2_000, seed: Long = 11, base: Double = 2400.0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var price = base
        val stepMs = 15 * 60_000L
        val phase1 = (seed * 1.7) % 6.28
        val phase2 = (seed * 3.1) % 6.28
        for (i in 0 until n) {
            val trend = i * (0.04 + (seed % 3) * 0.02)
            val wave = 6.0 * Math.sin(i / 9.0 + phase1) + 3.0 * Math.sin(i / 2.3 + phase2)
            val open = price
            val close = base + trend + wave
            out.add(
                Candle(
                    1_600_000_000_000L + i * stepMs, open,
                    maxOf(open, close) + 1.6, minOf(open, close) - 1.6, close,
                    100.0 + i % 17, true,
                ),
            )
            price = close
        }
        return out
    }

    private fun config(gate: NeuralGatePolicy.EntryGate? = null) = BacktestReplayEngine.Config(
        symbol = "XAU/USD",
        warmupBars = 120,
        runAllStrategies = true,
        enabledStrategyIds = setOf(BitcoinStrategyModel.BASE_ICHIMOKU.id),
        captureEntryReports = true,
        neuralGate = gate,
    )

    // ─────────────────────── ۱. گیت واقعاً پرسیده می‌شود ───────────────────────

    @Test
    fun `gate is consulted for every candidate entry`() {
        var calls = 0
        val countingGate = NeuralGatePolicy.EntryGate {
            calls++
            NeuralGatePolicy.GateVerdict.ALLOW
        }
        val base = BacktestReplayEngine.run(series(), config())
        val withGate = BacktestReplayEngine.run(series(), config(countingGate))

        assertTrue(
            "گیت باید برای هر ورود نامزد صدا زده شود؛ صفر بار یعنی بک‌تست هنوز به گیت وصل نیست",
            calls > 0,
        )
        assertEquals(
            "گیتی که هیچ‌چیز را مسدود نمی‌کند نباید تعداد معاملات را عوض کند",
            base.trades.size,
            withGate.trades.size,
        )
        assertEquals(0, withGate.neuralBlockedEntries)
    }

    // ─────────────────────── ۲. مسدودکردن، معامله را حذف می‌کند ───────────────────────

    @Test
    fun `a gate that blocks everything yields zero trades`() {
        val baseline = BacktestReplayEngine.run(series(), config())
        assertTrue(
            "این آزمون بدون معاملهٔ پایه بی‌معناست: «۰ = ۰» اثبات نیست",
            baseline.trades.isNotEmpty(),
        )

        val blocking = NeuralGatePolicy.EntryGate {
            NeuralGatePolicy.GateVerdict(blocks = true, reasonFa = "منطقهٔ ممنوعهٔ آزمون", summaryFa = "🛑 آزمون")
        }
        val blocked = BacktestReplayEngine.run(series(), config(blocking))

        assertEquals("گیتِ «همه را مسدود کن» باید صفر معامله بدهد", 0, blocked.trades.size)
        // وقتی ورودی مسدود می‌شود استراتژی «باز» نمی‌ماند، پس همان ستاپ در کندل بعدی
        // دوباره کاندید می‌شود و دوباره مسدود — پس تعداد مسدودی‌ها از تعداد معاملات پایه
        // بیشتر است. این عمدی و درست است: گیت هر کندل دوباره پرسیده می‌شود.
        assertTrue(
            "همهٔ ورودهای نامزد باید شمرده شوند",
            blocked.neuralBlockedEntries >= baseline.trades.size,
        )
        assertEquals(
            "همهٔ مسدودی‌ها باید با همان دلیل ثبت شوند",
            blocked.neuralBlockedEntries,
            blocked.neuralBlockReasons["منطقهٔ ممنوعهٔ آزمون"] ?: 0,
        )
    }

    // ─────────────────────── ۳. مسدودکردن جزئی، تفاوت واقعی می‌سازد ───────────────────────

    @Test
    fun `blocking only some setups changes the trade list, not just the totals`() {
        val baseline = BacktestReplayEngine.run(series(), config())
        assertTrue("برای مقایسهٔ تک‌به‌تک دست‌کم دو معامله لازم است", baseline.trades.size >= 2)

        // فقط خریدها را می‌گیرد؛ فروش‌ها رد می‌شوند.
        val half = NeuralGatePolicy.EntryGate { req ->
            if (req.isLong) {
                NeuralGatePolicy.GateVerdict(blocks = true, reasonFa = "فقط خرید ممنوع (آزمون)", summaryFa = "🛑")
            } else {
                NeuralGatePolicy.GateVerdict.ALLOW
            }
        }
        val gated = BacktestReplayEngine.run(series(), config(half))

        assertTrue(
            "با گرفتن یک جهت، فهرست معاملات باید کوتاه‌تر شود",
            gated.trades.size < baseline.trades.size,
        )
        assertEquals(
            "نباید حتی یک خرید از گیت رد شده باشد",
            0,
            gated.trades.count { it.action == com.aurum.edge.domain.SignalAction.BUY },
        )
        // هر معاملهٔ باقی‌مانده باید عیناً در اجرای پایه هم باشد (نه فقط جمع کل یکی باشد).
        val baseKeys = baseline.trades.map { "${it.strategyId}@${it.entryTime}" }.toSet()
        gated.trades.forEach { t ->
            assertTrue(
                "معاملهٔ باقی‌مانده باید همان معاملهٔ پایه باشد: ${t.strategyId}@${t.entryTime}",
                baseKeys.contains("${t.strategyId}@${t.entryTime}"),
            )
        }
    }

    // ─────────────────────── ۴. گیتِ خاموش، رفتار قبلی را حفظ می‌کند ───────────────────────

    @Test
    fun `null gate keeps the previous behaviour bit for bit`() {
        val a = BacktestReplayEngine.run(series(), config(null))
        val b = BacktestReplayEngine.run(series(), config(NeuralGatePolicy.EntryGate.ALLOW_ALL))
        assertEquals(a.trades.size, b.trades.size)
        assertEquals(
            "ترتیب و مشخصات معاملات باید یکی باشد",
            a.trades.map { "${it.strategyId}@${it.entryTime}:${it.rMultiple}" },
            b.trades.map { "${it.strategyId}@${it.entryTime}:${it.rMultiple}" },
        )
        assertEquals(0, b.neuralBlockedEntries)
        assertTrue(b.neuralBlockReasons.isEmpty())
    }
}
