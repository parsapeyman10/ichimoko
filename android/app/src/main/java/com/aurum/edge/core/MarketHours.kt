package com.aurum.edge.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Conservative *scheduled* windows, not proof of a live exchange/trade or a holiday calendar.
 * Price/provider timestamps still decide freshness.
 */
object MarketHours {
    private val newYork = ZoneId.of("America/New_York") // DST-aware weekend rollover
    private val closeTime = LocalTime.of(17, 0) // Friday 17:00 New York
    private val openTime = LocalTime.of(18, 0) // Sunday 18:00 New York; conservative for gold

    data class SessionWindow(
        val closed: Boolean,
        /** Null for a 24/7 venue: there is no next open or close. */
        val nextChangeAt: Long?,
        val nextChangeLabel: String,
        val newYorkTimeLabel: String,
        val detail: String,
    )

    /**
     * Crypto venues do not close, so the weekend gate must be asked per symbol. Treating a
     * 24/7 market as shut would mute the engine for two days every week.
     */
    fun weekendClosedFor(symbol: String, now: Long = System.currentTimeMillis()): Boolean =
        if (com.aurum.edge.data.CryptoCatalog.tradesAroundTheClock(symbol)) false
        else forexWeekendClosed(now)

    fun forexWeekendClosed(now: Long = System.currentTimeMillis()): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(newYork)
        return when (local.dayOfWeek) {
            DayOfWeek.FRIDAY -> !local.toLocalTime().isBefore(closeTime)
            DayOfWeek.SATURDAY -> true
            // Gold commonly resumes later than FX; don't claim an unverified 17:00 NY open.
            DayOfWeek.SUNDAY -> local.toLocalTime().isBefore(openTime)
            else -> false
        }
    }

    /**
     * Session state for a SPECIFIC instrument. Crypto trades around the clock, so showing
     * it a forex weekend-closed banner is simply wrong.
     */
    fun sessionWindowFor(symbol: String, now: Long = System.currentTimeMillis()): SessionWindow {
        if (com.aurum.edge.data.CryptoCatalog.tradesAroundTheClock(symbol)) {
            return SessionWindow(
                closed = false,
                nextChangeAt = null,
                nextChangeLabel = "بازار ۲۴ ساعته",
                newYorkTimeLabel = "کریپتو تعطیلی ندارد",
                detail = "بازار ارز دیجیتال ۲۴ ساعته و ۷ روز هفته باز است.",
            )
        }
        return sessionWindow(now)
    }

    fun sessionWindow(now: Long = System.currentTimeMillis()): SessionWindow {
        val local = Instant.ofEpochMilli(now).atZone(newYork)
        val closed = forexWeekendClosed(now)
        val next = if (closed) nextAt(local, DayOfWeek.SUNDAY, openTime)
            else nextAt(local, DayOfWeek.FRIDAY, closeTime)
        return SessionWindow(
            closed = closed,
            nextChangeAt = next.toInstant().toEpochMilli(),
            nextChangeLabel = if (closed) "بازشدن بازار" else "بسته‌شدن بازار",
            newYorkTimeLabel = if (closed) "یکشنبه 18:00 نیویورک" else "جمعه 17:00 نیویورک",
            detail = if (closed)
                "تعطیلی معمول پایان هفته؛ تعطیلی‌های رسمی/استثناها فقط با فید تازه تأیید می‌شوند"
            else "داخل برنامهٔ معمول بازار؛ بازبودن واقعی فقط با فید تازه تأیید می‌شود",
        )
    }

    fun marketLabel(now: Long = System.currentTimeMillis()): String =
        if (forexWeekendClosed(now)) "فارکس/طلا: تعطیلی معمول پایان هفته؛ قیمت/ورود متوقف"
        else "فارکس: داخل برنامهٔ معمول؛ بازبودن واقعی فقط با فید تازه تأیید می‌شود"

    private fun nextAt(local: ZonedDateTime, day: DayOfWeek, time: LocalTime): ZonedDateTime {
        var candidate = local.with(java.time.temporal.TemporalAdjusters.nextOrSame(day)).with(time)
        if (!candidate.isAfter(local)) candidate = candidate.plusWeeks(1)
        return candidate
    }
}
