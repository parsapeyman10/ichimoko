package com.aurum.edge.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Conservative *scheduled* windows, not proof of a live exchange/trade or a holiday calendar.
 * Price/provider timestamps still decide freshness.
 */
object MarketHours {
    private val newYork = ZoneId.of("America/New_York") // DST-aware weekend rollover

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

    fun marketLabel(now: Long = System.currentTimeMillis()): String =
        if (forexWeekendClosed(now)) "فارکس/طلا: تعطیلی معمول پایان هفته؛ قیمت/ورود متوقف"
        else "فارکس: داخل برنامهٔ معمول؛ بازبودن واقعی فقط با فید تازه تأیید می‌شود"
}
