package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «نمونهٔ صفر ≠ مقدار صفر»: پروفایل‌های یادگیری نباید پیش از اولین نمونه، نرخ برد یا
 * Profit Factor ساختگی نشان دهند.
 *
 * چرا جدا از `TimeStopAndLimitsTest`؟ چون `PerStrategyNeuralTuningProfile` داخل
 * `NeuralSelfLearningEngine.kt` است که به `android.content.Context` وابسته است؛ نگه‌داشتن این
 * تست‌ها در آن فایل باعث می‌شد **کل** فایل در آزمایشگاه JVM کامپایل نشود و تست‌های توقف زمانی
 * هرگز محلی اجرا نشوند. CI هر دو فایل را اجرا می‌کند.
 */
class NeuralProfileHonestyTest {

    @Test
    fun `learning profiles no longer ship fabricated win rates before any sample`() {
        val fresh = PerStrategyNeuralTuningProfile(strategyId = "BASE", strategyTitleFa = "بیس", strategyBadgeFa = "B")
        assertEquals("پیش از اولین نمونه نباید نرخ برد ساختگی نشان داده شود", 0.0, fresh.winRatePct, 1e-9)
        assertEquals(0.0, fresh.expectedProfitFactor, 1e-9)
        assertEquals(0, fresh.samplesAnalyzed)
    }

    @Test
    fun `symbol profile has no invented profit factor either`() {
        val fresh = PerSymbolNeuralTuningProfile(symbol = "BTC/USDT")
        assertEquals(0.0, fresh.expectedProfitFactor, 1e-9)
        assertEquals(0.0, fresh.winRatePct, 1e-9)
    }
}
