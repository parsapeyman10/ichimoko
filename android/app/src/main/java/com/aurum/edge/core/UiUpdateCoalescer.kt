package com.aurum.edge.core

/**
 * کنترل نرخ انتشار وضعیت به لایهٔ UI (State Coalescing).
 *
 * **مشکل واقعی:** در رادار ۱۵ نمادی، هر تیک قیمت `StateFlow` را عوض می‌کند و هر تغییر، کل صفحه
 * (کارت استراتژی، کارنامه، لیست معاملات) را دوباره می‌سازد. با چند نماد هم‌زمان، این یعنی ده‌ها
 * بازسازی در دقیقه و ریسک لگ/ANR روی گوشی‌های ضعیف.
 *
 * **راه‌حل استاندارد (Compose best practice):** ورودی‌های پرتکرار را «هم‌گرا» (coalesce) کنید تا
 * حداکثر با یک نرخ مشخص به UI برسند، در حالی که منطق معاملاتی همچنان روی **هر** تیک اجرا می‌شود.
 * این کلاس فقط تصمیم «حالا منتشر کنم یا نه؟» را می‌گیرد تا قابل تست باشد و هیچ وابستگی اندرویدی
 * نداشته باشد.
 */
class UiUpdateCoalescer(
    private val minIntervalMs: Long = 250L,
) {
    private val lastPublishedAt = mutableMapOf<String, Long>()

    /** آیا مقدار این کلید را حالا به UI منتشر کنیم؟ (اولین بار همیشه بله) */
    @Synchronized
    fun shouldPublish(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastPublishedAt[key]
        if (last == null) {
            lastPublishedAt[key] = now
            return true
        }
        if (now - last < minIntervalMs) return false
        lastPublishedAt[key] = now
        return true
    }

    /** انتشار اجباری (مثلاً وقتی مقدار به‌شدت تغییر کرده یا معامله بسته شده). */
    @Synchronized
    fun forcePublish(key: String, now: Long = System.currentTimeMillis()) {
        lastPublishedAt[key] = now
    }

    @Synchronized
    fun reset() {
        lastPublishedAt.clear()
    }

    @Synchronized
    fun trackedKeys(): Int = lastPublishedAt.size
}
