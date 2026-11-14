package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «حد ضرر ۵ دلار و حد سود ۱٫۵ تا ۳R» باید در **دلار واقعی** درست باشد، نه فقط در نسبت قیمتی.
 *
 * چرا این تست لازم شد: کاربر گفت «حد ضررم ۵ دلار، حدا سودم تا ۱۵ دلار». محاسبهٔ قبلی حجم را
 * فقط از فاصلهٔ حد ضرر می‌ساخت و کارمزد/اسپرد را بیرون می‌گذاشت، پس زیان واقعی از ۵ دلار بیشتر
 * و سود واقعی از R اعلام‌شده کمتر بود. این تست‌ها همان اختلاف را قید می‌کنند.
 */
class RiskDollarTargetTest {

    private fun calc(
        symbol: String,
        entry: Double,
        stopDistance: Double,
        targetDistance: Double,
        riskUsd: Double = 5.0,
    ): RiskCalculationResult {
        val r = RiskAndCalendarEngine.calculatePosition(
            symbolInput = symbol,
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = entry,
            stopLoss = entry - stopDistance,
            takeProfit = entry + targetDistance,
            preferredAction = com.aurum.edge.domain.SignalAction.BUY,
            fixedRiskUsdOverride = riskUsd,
        )
        assertNotNull("محاسبهٔ پوزیشن باید نتیجه بدهد", r)
        return r!!
    }

    @Test
    fun `net loss at stop equals the configured dollar risk`() {
        val cases = listOf(
            Triple("XAU/USD", 2_600.0, 7.8),   // طلا
            Triple("EUR/USD", 1.0850, 0.0020), // فارکس
            Triple("BTC/USDT", 60_000.0, 900.0),
        )
        cases.forEach { (symbol, entry, stopDistance) ->
            val r = calc(symbol, entry, stopDistance, stopDistance * 2.0)
            // حجم به «گام مجاز بازار» پایین گرد می‌شود (مثل قواعد واقعی سفارش)، پس زیان
            // هرگز از ۵ دلار بالاتر نمی‌زند و حداکثر یک گام پایین‌تر می‌ایستد:
            // گام طلا ۰٫۰۱ اونس ≈ ۸ سنت ریسک، فارکس ۱۰۰ واحد ≈ ۲۰ سنت، بیت‌کوین ۰٫۰۰۰۰۱ ≈ ۲ سنت.
            val stepRiskUsd = com.aurum.edge.core.VenueSpecs.of(symbol).let { spec ->
                spec.quantityStep * (stopDistance + spec.totalRoundTripFeeUsd(entry, 1.0))
            }
            assertTrue(
                "زیان خالص در SL نباید از ۵ دلار بیشتر شود · $symbol (بود: ${r.netLossAtSlUsd})",
                r.netLossAtSlUsd <= 5.0 + 0.01,
            )
            assertTrue(
                "زیان خالص در SL باید نزدیک ۵ دلار بماند · $symbol (بود: ${r.netLossAtSlUsd})",
                r.netLossAtSlUsd >= 5.0 - stepRiskUsd - 0.01,
            )
            assertTrue("کارمزد باید داخل محاسبه باشد · $symbol", r.totalRoundTripFeeUsd > 0.0)
            assertTrue(
                "زیان ناخالص باید کمتر از زیان خالص باشد (کارمزد) · $symbol",
                r.grossLossAtSlUsd < r.netLossAtSlUsd,
            )
        }
    }

    @Test
    fun `net reward keeps the planned R multiple after fees`() {
        val r = calc("XAU/USD", 2_600.0, 7.8, 7.8 * 2.0)
        // ۲R ⇒ خالص حدود ۱۰ دلار (نه ۱۰ منهای کارمزد).
        assertEquals("سود خالص ۲R", 10.0, r.netProfitAtTpUsd, 0.05)
        // با پایین‌گرد کردن حجم به گام ۰٫۰۱ اونس، زیان خالص ۴٫۹۵ دلار می‌شود نه ۵٫۰۰، پس R خالص
        // یک درصد بالاتر از ۲ می‌افتد؛ همین «ناهم‌ترازی گام» است، نه خطای محاسبه.
        assertEquals("R خالص", 2.0, r.netRiskReward, 0.05)
    }

    @Test
    fun `take profit never leaves the one point five to three R band`() {
        // هدف ساختاری ۵R خواسته شده ⇒ باید به سقف ۳R بریده شود.
        val wide = calc("XAU/USD", 2_600.0, 7.8, 7.8 * 5.0)
        assertTrue("R باید حداکثر ۳ باشد، بود: ${wide.riskReward}", wide.riskReward <= 3.0 + 1e-6)
        assertTrue("R باید دست‌کم ۱٫۵ باشد", wide.riskReward >= 1.5 - 1e-6)

        // هدف ۰٫۸R: پلن نباید «باد» شود تا داخل دالان بیفتد. عدد واقعی گزارش می‌شود و
        // مسیرهای اجرا (که `MIN_REQUIRED_RISK_REWARD` را چک می‌کنند) چنین معامله‌ای را باز نمی‌کنند.
        val narrow = calc("XAU/USD", 2_600.0, 7.8, 7.8 * 0.8)
        assertTrue(
            "پلن زیر کف باید همان‌طور که هست گزارش شود، نه مصنوعی بزرگ، بود: ${narrow.riskReward}",
            narrow.riskReward < 1.5,
        )
    }

    @Test
    fun `heavy crypto fees cap the price target at three R and report the smaller net R`() {
        // BNB: کارمزد رفت‌وبرگشت ۱٫۴۷ دلار در هر واحد است و حد ضرر ۲٫۸۶ دلار ⇒ کارمزد ۳۴٪ کل ریسک.
        // برای «۲٫۵R خالص» باید هدف قیمتی ۴٫۳R دور برود؛ چون سقف ۳R است، هدف روی ۳R می‌ماند
        // و سود خالص واقعی ۸٫۲۰ دلار (۱٫۶۴R) گزارش می‌شود — عدد دروغ نمی‌گوییم.
        val r = calc("BNB/USDT", 701.60, 2.86, 7.14)
        assertEquals(3.0, r.grossRiskReward, 1e-4)
        assertTrue("R خالص باید کمتر از سقف قیمتی باشد", r.riskReward < 3.0)
        assertTrue("R خالص نباید از کف ۱٫۵ بیرون بزند", r.riskReward >= 1.5)
        assertTrue("سود خالص باید داخل دالان ۷٫۵ تا ۱۵ دلار بماند", r.netProfitAtTpUsd in 7.5..15.0)
    }

    @Test
    fun `dollar target stays inside seven point five to fifteen for five dollar risk`() {
        listOf(1.5, 1.85, 2.3, 2.5, 3.0).forEach { rr ->
            val r = calc("XAU/USD", 2_600.0, 7.8, 7.8 * rr)
            val target = r.netProfitAtTpUsd
            assertTrue(
                "با ریسک ۵ دلار، هدف $rr R باید بین ۷٫۵ و ۱۵ دلار بماند، بود: $target",
                target in 7.5..15.0,
            )
        }
    }

    @Test
    fun `sell side mirrors the same dollar numbers`() {
        val entry = 2_600.0
        val stopDistance = 7.8
        val targetDistance = 15.6
        val r = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = entry,
            stopLoss = entry + stopDistance,
            takeProfit = entry - targetDistance,
            preferredAction = com.aurum.edge.domain.SignalAction.SELL,
            fixedRiskUsdOverride = 5.0,
        )!!
        assertTrue("زیان خالص فروش هم باید نزدیک ۵ دلار بماند", r.netLossAtSlUsd in 4.9..5.01)
        assertEquals(10.0, r.netProfitAtTpUsd, 0.05)
        assertTrue("حد سود فروش باید زیر ورود باشد", r.takeProfit < r.entry)
    }
}
