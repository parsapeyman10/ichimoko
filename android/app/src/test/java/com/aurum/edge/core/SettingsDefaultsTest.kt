package com.aurum.edge.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * پیش‌فرض‌های «ترمز ریسک» در لایهٔ ذخیره‌سازی.
 *
 * چرا جدا شد؟ این تست به `com.aurum.edge.data.SettingsStore` (DataStore اندرویدی) وابسته است و
 * در محیط آزمایشگاه JVM بدون اندروید کامپایل نمی‌شود؛ تا وقتی داخل `TimeStopAndLimitsTest`
 * بود، **کل آن فایل** در محیط محلی کامپایل نمی‌شد و تست‌های توقف زمانی اجرا نمی‌شدند. حالا آن
 * فایل محلی اجرا می‌شود و این یکی در CI.
 */
class SettingsDefaultsTest {

    @Test
    fun `risk brake migration defaults are protective not wide open`() {
        assertTrue(
            "سقف ضرر روزانهٔ پیش‌فرضِ مهاجرت باید محافظتی باشد",
            com.aurum.edge.data.SettingsStore.DEFAULT_DAILY_LOSS_LIMIT_PERCENT <= 10.0,
        )
        assertTrue(
            "سقف افت سرمایهٔ پیش‌فرضِ مهاجرت باید محافظتی باشد",
            com.aurum.edge.data.SettingsStore.DEFAULT_MAX_DRAWDOWN_LIMIT_PERCENT <= 25.0,
        )
    }
}
