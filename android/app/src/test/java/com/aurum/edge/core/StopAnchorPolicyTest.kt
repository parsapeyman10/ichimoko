package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * سیاست حد ضرر و سخت‌گیری ورودی که از **اندازه‌گیری فاز ۰** آمده است.
 *
 * عددهای این تست همان اعدادی هستند که از بازپخش ۳۰ روز معاملات واقعی بیرون آمدند؛ اگر روزی
 * کسی آن‌ها را بی‌دلیل عوض کند، این تست باید بشکند.
 */
class StopAnchorPolicyTest {

    @Test
    fun `measured defaults are wired but nothing else changes`() {
        val config = BaseStrategyConfig()
        assertTrue("لنگر فرکتال ۵ برای بیس باید روشن باشد", config.baseFractalFiveStopAnchor)
        assertEquals(0.52, config.baseFractalFiveStopBufferAtr, 1e-9)
        assertTrue("سخت‌گیری تاتل باید روشن باشد", config.tuttleStrictEntry)
        assertEquals(0.52, IchimokuStrategyEngine.BASE_FRACTAL_FIVE_BUFFER_ATR, 1e-9)
    }

    @Test
    fun `fractal anchor sits a measured buffer beyond the five-bar fractal`() {
        val stop = IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
            referenceEntry = 100.0,
            isLong = true,
            fractal5 = 99.0,
            atr = 1.0,
            bufferAtr = IchimokuStrategyEngine.BASE_FRACTAL_FIVE_BUFFER_ATR,
            floorDist = 0.20,
            ceilingDist = 3.0,
        )
        assertNotNull(stop)
        assertEquals("۹۹٫۰ − ۰٫۵۲ ATR", 98.48, stop!!, 1e-6)
    }

    @Test
    fun `fractal anchor mirrors for sells`() {
        val stop = IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
            referenceEntry = 100.0,
            isLong = false,
            fractal5 = 101.0,
            atr = 1.0,
            bufferAtr = 0.52,
            floorDist = 0.20,
            ceilingDist = 3.0,
        )
        assertNotNull(stop)
        assertEquals(101.52, stop!!, 1e-6)
    }

    @Test
    fun `noise floor and structural ceiling still guard the measured anchor`() {
        val floorBound = IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
            referenceEntry = 100.0,
            isLong = true,
            fractal5 = 99.9,
            atr = 0.1,
            bufferAtr = 0.52,
            floorDist = 0.80,
            ceilingDist = 3.0,
        )
        assertNotNull(floorBound)
        assertEquals("کف نویز حاکم است", 99.20, floorBound!!, 1e-6)

        val ceilingBound = IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
            referenceEntry = 100.0,
            isLong = true,
            fractal5 = 90.0,
            atr = 1.0,
            bufferAtr = 0.52,
            floorDist = 0.20,
            ceilingDist = 2.0,
        )
        assertNotNull(ceilingBound)
        assertEquals("سقف ساختاری حاکم است", 98.0, ceilingBound!!, 1e-6)
    }

    @Test
    fun `an impossible anchor yields nothing instead of a fabricated stop`() {
        assertNull(
            IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
                referenceEntry = 100.0,
                isLong = true,
                fractal5 = 101.0,
                atr = 1.0,
                bufferAtr = 0.52,
                floorDist = 0.20,
                ceilingDist = 3.0,
            ),
        )
        assertNull(
            IchimokuStrategyEngine.fractalFiveAnchorStopPrice(
                referenceEntry = 100.0,
                isLong = true,
                fractal5 = Double.NaN,
                atr = 1.0,
                bufferAtr = 0.52,
                floorDist = 0.20,
                ceilingDist = 3.0,
            ),
        )
    }

    @Test
    fun `tuttle tightening only ever makes the entry stricter`() {
        val loose = BaseStrategyConfig(
            maxKijunExtensionAtr = 1.80,
            minRiskReward = 1.50,
            minEfficiencyRatio = 0.20,
            minDriveBooksConsensus = 6,
        )
        val tight = IchimokuStrategyEngine.applyMeasuredTuttleTightening(
            config = loose,
            model = BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT,
            enabled = true,
        )
        assertEquals(IchimokuStrategyEngine.TUTTLE_STRICT_MAX_KIJUN_EXTENSION_ATR, tight.maxKijunExtensionAtr, 1e-9)
        assertEquals(1.80, tight.minRiskReward, 1e-9)
        assertEquals(0.26, tight.minEfficiencyRatio, 1e-9)
        assertEquals(7, tight.minDriveBooksConsensus)

        val alreadyStricter = loose.copy(
            maxKijunExtensionAtr = 1.20,
            minRiskReward = 2.40,
            minEfficiencyRatio = 0.35,
            minDriveBooksConsensus = 9,
        )
        val unchanged = IchimokuStrategyEngine.applyMeasuredTuttleTightening(
            config = alreadyStricter,
            model = BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT,
            enabled = true,
        )
        assertEquals("سخت‌گیری نباید مقدار سخت‌ترِ موجود را شل کند", alreadyStricter, unchanged)
    }

    @Test
    fun `tightening is scoped to tuttle and can be switched off`() {
        val config = BaseStrategyConfig(minRiskReward = 1.50, minDriveBooksConsensus = 6)
        assertEquals(
            config,
            IchimokuStrategyEngine.applyMeasuredTuttleTightening(config, BitcoinStrategyModel.BASE_ICHIMOKU, true),
        )
        assertEquals(
            config,
            IchimokuStrategyEngine.applyMeasuredTuttleTightening(config, BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT, false),
        )
        assertFalse(
            IchimokuStrategyEngine.applyMeasuredTuttleTightening(config, BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT, true)
                .minRiskReward == config.minRiskReward,
        )
    }
}
