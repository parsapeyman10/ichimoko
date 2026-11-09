package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون استراتژی پارسا — «چرخش خط ۵۲» با دروازه‌بندی سه‌لایه.
 *
 * ## چه چیزی خراب بود
 * نُه شرط با `AND` خالص روی هم سوار بودند. روی ۲۳۰۰ کندل: **۰ معامله**، و هیچ‌وقت بیش
 * از ۸ شرط از ۹. یک شرط هم بدتر بود: «حرکت شارپ» به حجم ≥۱٫۱۰ برابر نیاز داشت و در
 * سری آزمون (حجم ثابت) هرگز پاس نمی‌شد — یعنی فیکسچر هم توان تولید آن رویداد را نداشت،
 * و «۰ = ۰» اثبات نیست.
 *
 * ## چه چیزی حالا قفل است
 * لایهٔ ۱ (هویت روش: فلت ۱۸+ کندلی SSB، چرخش Span A، کراس TK در ۸ کندل) وتوی سخت است؛
 * لایهٔ ۲ (پنج تأیید کیفیتی) باید دست‌کم امتیاز [ParsaLineTurnEngine.MIN_QUALITY_SCORE]
 * را بیاورد. گشودن لایهٔ ۱ یا شمردن همان «همه‌چیز با هم» دوباره استراتژی را می‌کشد.
 */
class ParsaLineTurnRulesTest {

    private fun spec(
        flatBars: Int = 20,
        spanAPrev: Double = 0.0,
        spanANow: Double = 0.10,
        crossAge: Int = 3,
        slopeOk: Boolean = true,
        spacingOk: Boolean = true,
        chikouOpen: Boolean = true,
        cloudSideOk: Boolean = true,
        sharpOk: Boolean = true,
        isLong: Boolean = true,
    ) = ParsaLineTurnEngine.spec(
        flatBars = flatBars,
        spanAPrevSlopeAtr = spanAPrev,
        spanASlopeAtr = spanANow,
        crossAgeBars = crossAge,
        slopeOk = slopeOk,
        lineSpacingOk = spacingOk,
        chikouOpen = chikouOpen,
        cloudSideOk = cloudSideOk,
        sharpMoveOk = sharpOk,
        isLong = isLong,
    )

    // ─────────────────────── لایهٔ ۱: هویت روش ───────────────────────

    @Test
    fun `healthy setup passes with all confirmations`() {
        val s = spec()
        assertTrue("با ستاپ سالم روش باید کامل باشد", s.passed)
        assertTrue(s.corePassed)
        assertEquals(5, s.qualityScore)
        assertEquals("هشت شرط روش + شرط نهم پلن در گزارش", 8, s.rules.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), s.rules.map { it.index })
    }

    @Test
    fun `flat periods shorter than 18 bars are rejected`() {
        val s = spec(flatBars = 17)
        assertFalse("۱۷ کندل فلت، ۱۸ کندل نیست", s.passed)
        assertFalse(s.corePassed)
        assertFalse(s.rules[0].passed)
    }

    @Test
    fun `span A must actually turn, not merely be positive`() {
        assertFalse(
            "Span A که از قبل صعودی بوده «چرخش» نیست",
            spec(spanAPrev = 0.05, spanANow = 0.06).rules[1].passed,
        )
        assertFalse(
            "شیب کمتر از حد چرخش کافی نیست",
            spec(spanAPrev = 0.0, spanANow = 0.01).rules[1].passed,
        )
        assertTrue(spec(spanAPrev = -0.02, spanANow = 0.08).rules[1].passed)
    }

    @Test
    fun `stale cross is rejected`() {
        assertFalse(spec(crossAge = 9).rules[2].passed)
        assertTrue(spec(crossAge = 8).rules[2].passed)
        assertFalse("کراس پیدا نشده یعنی تازه نیست", spec(crossAge = Int.MAX_VALUE).rules[2].passed)
    }

    // ─────────────────────── لایهٔ ۲: امتیاز کیفیت ───────────────────────

    @Test
    fun `two quality confirmations are not enough but three are`() {
        val two = spec(slopeOk = false, spacingOk = false, sharpOk = false)
        assertEquals(2, two.qualityScore)
        assertTrue("هویت روش در این حالت سالم است", two.corePassed)
        assertFalse("دو تأیید از پنج، کافی نیست", two.passed)

        val three = spec(slopeOk = false, spacingOk = false)
        assertEquals(3, three.qualityScore)
        assertTrue("سه تأیید از پنج، کافی است", three.passed)
    }

    @Test
    fun `quality score can never override a broken core`() {
        val broken = spec(flatBars = 5, crossAge = 40) // همهٔ کیفیت‌ها پاس
        assertEquals(5, broken.qualityScore)
        assertFalse("امتیاز کیفیت نباید نقض هویت روش را جبران کند", broken.passed)
        assertFalse(broken.corePassed)
        assertTrue(broken.summaryFa.contains("هویت روش"))
    }

    @Test
    fun `all nine rules stay visible for the report`() {
        // اگر کسی لایهٔ ۲ را حذف یا ادغام کند، جدول گزارش کاربر عوض می‌شود.
        val titles = spec().rules.map { it.titleFa }
        assertTrue(titles[0].contains("Senkou Span B"))
        assertTrue(titles[1].contains("Senkou Span A"))
        assertTrue(titles[2].contains("کراس"))
        assertTrue(titles[5].contains("Chikou"))
        assertTrue(titles[6].contains("حرکت شارپ"))
        assertTrue(titles[7].contains("تایم‌فریم بالاتر"))
    }

    // ─────────────────────── خروجی واقعی روی سری واقعی‌نما ───────────────────────

    private fun series(n: Int, seed: Long): List<Candle> {
        val rnd = java.util.Random(seed * 7919 + 13)
        val out = ArrayList<Candle>(n)
        var price = 2400.0
        val stepMs = 15 * 60_000L
        var vol = 1.0
        var drift = 0.0
        for (i in 0 until n) {
            if (i % 90 == 0) {
                vol = 0.45 + rnd.nextDouble() * 1.9
                drift = (rnd.nextDouble() - 0.45) * 0.09
            }
            val pull = if (i % 47 < 8) -drift * 2.4 else 0.0
            val open = price
            val close = open + (drift + pull + (rnd.nextDouble() - 0.5) * 2.2) * vol
            val wick = (0.15 + rnd.nextDouble() * 0.9) * vol
            val high = maxOf(open, close) + wick
            val low = minOf(open, close) - wick
            val volume = (40.0 + (high - low) * 260.0) * (0.55 + rnd.nextDouble() * 1.1)
            out.add(Candle(1_600_000_000_000L + i * stepMs, open, high, low, close, volume, true))
            price = close
        }
        return out
    }

    @Test
    fun `parsa produces trades on a series where every condition can occur`() {
        // این آزمون عمداً روی سری‌ای اجرا می‌شود که در آن شرط «حرکت شارپ» هم واقعاً
        // پاس می‌شود؛ وگرنه «۰ معامله» ممکن بود از فیکسچر بیاید، نه از کد.
        val result = BacktestReplayEngine.run(
            series(2_500, seed = 5),
            BacktestReplayEngine.Config(
                strategyId = BitcoinStrategyModel.PARSA_52_LINE_TURN.id,
                symbol = "XAU/USD",
                warmupBars = 120,
                enabledStrategyIds = setOf(BitcoinStrategyModel.PARSA_52_LINE_TURN.id),
            ),
        )
        assertTrue(
            "پارسا باید معامله بسازد؛ صفر یعنی دوباره به AND خالص همهٔ شرط‌ها برگشته است",
            result.trades.isNotEmpty(),
        )
        assertTrue("حداقل یک بار شرط نهم (پلن) هم باید پاس شود", result.maxConditionsPassed >= 8)
    }

    @Test
    fun `report exposes nine parsa conditions`() {
        val bars = series(800, seed = 3)
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.PARSA_52_LINE_TURN,
            config = BaseStrategyConfig(),
            macroCandles = bars,
            strategyCandles = bars,
            triggerCandles = bars,
            symbolInput = "XAU/USD",
        )
        assertEquals("گزارش کاربر باید همان نُه شرط را نشان دهد", 9, report.conditions.size)
        assertEquals((1..9).toList(), report.conditions.map { it.index })
        assertTrue(report.conditions.first().titleFa.contains("Senkou Span B"))
        assertTrue(report.conditions.last().titleFa.contains("R:R"))
    }
}
