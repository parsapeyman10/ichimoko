package com.aurum.edge.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Conservative *scheduled* windows, not proof of a live exchange/trade or a holiday calendar.
 * Price/provider timestamps still decide freshness. Crypto/Nobitex markets are 24/7 but
 * maintenance and individual symbols' halts can only be verified with venue data.
 */
object MarketHours {
    private val newYork = ZoneId.of("America/New_York") // DST-aware weekend rollover
    private val tehran = ZoneId.of("Asia/Tehran")

    fun forexWeekendClosed(now: Long = System.currentTimeMillis()): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(newYork)
        return when (local.dayOfWeek) {
            DayOfWeek.FRIDAY -> !local.toLocalTime().isBefore(LocalTime.of(17, 0))
            DayOfWeek.SATURDAY -> true
            // Gold commonly resumes later than FX; don't claim an unverified 17:00 NY open.
            DayOfWeek.SUNDAY -> local.toLocalTime().isBefore(LocalTime.of(18, 0))
            else -> false
        }
    }

    fun iranStockSessionScheduled(now: Long = System.currentTimeMillis()): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(tehran)
        if (local.dayOfWeek in setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) return false
        val time = local.toLocalTime()
        return !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(12, 30))
    }

    fun labelForWorkspace(space: String, now: Long = System.currentTimeMillis()): String = when (space) {
        "forex" -> if (forexWeekendClosed(now)) "فارکس/طلا: تعطیلی معمول پایان هفته؛ قیمت/ورود متوقف" else
            "فارکس: داخل برنامهٔ معمول؛ بازبودن واقعی فقط با فید تازه تأیید می‌شود"
        "iran_stocks" -> if (iranStockSessionScheduled(now))
            "بورس: ساعت معمول معاملات؛ تعطیلی رسمی/توقف نماد جداگانه بررسی شود" else
            "بورس ایران: خارج ساعت معمول؛ دریافت خودکار تابلو متوقف"
        "crypto", "nobitex" -> "بازار رمزارز ۲۴ساعته است؛ قطعی منبع/توقف نماد به معنی بسته‌بودن کل بازار نیست"
        else -> "زمان‌بندی بازار نامشخص"
    }
}
