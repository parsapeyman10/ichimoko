package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * استراتژی جامع ایچیموکو باید با نام دقیق «ichimoku» شناخته شود، بدون آن‌که شناسهٔ ذخیره‌شده
 * در تنظیمات (`BASE_ICHIMOKU`) تغییر کند — تا ژورنال و آموخته‌های عصبی قبلی از دست نروند.
 */
class IchimokuStrategyAliasTest {

    @Test
    fun `the comprehensive strategy answers to the literal name ichimoku`() {
        assertEquals(BitcoinStrategyModel.BASE_ICHIMOKU, BitcoinStrategyModel.fromId("ichimoku"))
        assertEquals(BitcoinStrategyModel.BASE_ICHIMOKU, BitcoinStrategyModel.fromId("  ICHIMOKU  "))
        assertEquals(BitcoinStrategyModel.BASE_ICHIMOKU, BitcoinStrategyModel.fromId("ichimoku_master"))
        assertEquals(
            BitcoinStrategyModel.BASE_ICHIMOKU.id,
            BitcoinStrategyModel.normalizeStrategyId("ichimoku"),
        )
        // شناسهٔ رسمی هم مثل قبل کار می‌کند.
        assertEquals(
            BitcoinStrategyModel.BASE_ICHIMOKU,
            BitcoinStrategyModel.fromId(BitcoinStrategyModel.BASE_ICHIMOKU.id),
        )
    }

    @Test
    fun `ichimoku keeps all four schools and carries its exact name in the title`() {
        val model = BitcoinStrategyModel.BASE_ICHIMOKU
        assertEquals("چهار مکتب باید فعال بمانند", 4, model.combinationCount)
        assertTrue(
            "عنوان نمایشی باید نام ichimoku را داشته باشد",
            model.titleFa.contains("ichimoku"),
        )
    }

    @Test
    fun `the alias never hijacks the single-school models`() {
        assertEquals(
            BitcoinStrategyModel.PATEL_KIJUN_PULLBACK,
            BitcoinStrategyModel.fromId("PATEL_KIJUN_PULLBACK"),
        )
        assertEquals(
            BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT,
            BitcoinStrategyModel.fromId("tuttle_kumo_breakout"),
        )
        assertEquals(
            "شناسهٔ ناشناس باید به استراتژی جامع برگردد",
            BitcoinStrategyModel.BASE_ICHIMOKU,
            BitcoinStrategyModel.fromId("something-unknown"),
        )
    }
}
