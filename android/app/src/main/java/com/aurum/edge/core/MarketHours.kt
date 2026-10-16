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
    private val openTime = LocalTime.of(18, 0) // Sunday 18:00 New York; conservative for commodities
    private val fxOpenTime = LocalTime.of(17, 0) // Sunday 17:00 New York (indicative FX schedule)

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
        when (AssetClass.of(symbol)) {
            AssetClass.CRYPTO -> false
            AssetClass.FOREX -> scheduledWeekendClosed(now, fxOpenTime)
            AssetClass.COMMODITY, AssetClass.STOCK -> forexWeekendClosed(now)
        }

    /** Scheduled absolute closure. US shares/indices use DST-aware New York regular hours;
     * a holiday or halt still needs fresh independent provider evidence to be tradeable.
     */
    fun closedFor(symbol: String, now: Long = System.currentTimeMillis()): Boolean {
        if (AssetClass.of(symbol) == AssetClass.CRYPTO) return false
        if (weekendClosedFor(symbol, now)) return true
        val time = Instant.ofEpochMilli(now).atZone(newYork).toLocalTime()
        return when (AssetClass.of(symbol)) {
            AssetClass.STOCK -> time.isBefore(LocalTime.of(9, 30)) || !time.isBefore(LocalTime.of(16, 0))
            AssetClass.COMMODITY -> !time.isBefore(LocalTime.of(17, 0)) && time.isBefore(LocalTime.of(18, 0))
            AssetClass.FOREX, AssetClass.CRYPTO -> false // FX rollover may lack quotes: only a fresh provider decides.
        }
    }

    fun forexWeekendClosed(now: Long = System.currentTimeMillis()): Boolean =
        scheduledWeekendClosed(now, openTime) // Legacy gold/commodity-conservative window.

    private fun scheduledWeekendClosed(now: Long, sundayOpen: LocalTime): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(newYork)
        return when (local.dayOfWeek) {
            DayOfWeek.FRIDAY -> !local.toLocalTime().isBefore(closeTime)
            DayOfWeek.SATURDAY -> true
            DayOfWeek.SUNDAY -> local.toLocalTime().isBefore(sundayOpen)
            else -> false
        }
    }

    /**
     * Session state for a SPECIFIC instrument. Crypto trades around the clock, so showing
     * it a forex weekend-closed banner is simply wrong.
     */
    fun sessionWindowFor(symbol: String, now: Long = System.currentTimeMillis()): SessionWindow {
        if (AssetClass.of(symbol) == AssetClass.STOCK) {
            val local = Instant.ofEpochMilli(now).atZone(newYork)
            val opening = LocalTime.of(9, 30)
            val next = if (closedFor(symbol, now)) {
                var day = local.toLocalDate()
                if (!local.toLocalTime().isBefore(LocalTime.of(16, 0))) day = day.plusDays(1)
                while (day.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) day = day.plusDays(1)
                day.atTime(opening).atZone(newYork).toInstant().toEpochMilli()
            } else local.toLocalDate().atTime(LocalTime.of(16, 0)).atZone(newYork)
                .toInstant().toEpochMilli()
            return SessionWindow(closedFor(symbol, now), next,
                if (closedFor(symbol, now)) "بازشدن سشن سهام" else "بسته‌شدن سشن سهام",
                local.toLocalTime().toString(), "ساعات منظم سهام آمریکا ۰۹:۳۰–۱۶:۰۰ نیویورک؛ تعطیلات ویژه با فید معتبر سنجیده شوند")
        }
        if (AssetClass.of(symbol) == AssetClass.CRYPTO) {
            return SessionWindow(
                closed = false,
                nextChangeAt = null,
                nextChangeLabel = "بازار ۲۴ ساعته",
                newYorkTimeLabel = "کریپتو تعطیلی ندارد",
                detail = "بازار ارز دیجیتال ۲۴ ساعته و ۷ روز هفته باز است.",
            )
        }
        return sessionWindow(now, if (AssetClass.of(symbol) == AssetClass.FOREX) fxOpenTime else openTime)
    }

    fun sessionWindow(now: Long = System.currentTimeMillis()): SessionWindow = sessionWindow(now, openTime)

    private fun sessionWindow(now: Long, sundayOpen: LocalTime): SessionWindow {
        val local = Instant.ofEpochMilli(now).atZone(newYork)
        val dailyBreak = sundayOpen == openTime && local.dayOfWeek in setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY) &&
            !local.toLocalTime().isBefore(LocalTime.of(17, 0)) && local.toLocalTime().isBefore(openTime)
        val closed = scheduledWeekendClosed(now, sundayOpen) || dailyBreak
        val next = if (dailyBreak) local.with(openTime)
            else if (closed) nextAt(local, DayOfWeek.SUNDAY, sundayOpen)
            else if (sundayOpen == openTime) {
                val todayBreak = local.with(closeTime)
                if (todayBreak.isAfter(local)) todayBreak else todayBreak.plusDays(1)
            } else nextAt(local, DayOfWeek.FRIDAY, closeTime)
        return SessionWindow(
            closed = closed,
            nextChangeAt = next.toInstant().toEpochMilli(),
            nextChangeLabel = if (closed) "بازشدن بازار" else "بسته‌شدن بازار",
            newYorkTimeLabel = if (dailyBreak) "امروز 18:00 نیویورک"
                else if (closed) "یکشنبه ${sundayOpen} نیویورک"
                else if (sundayOpen == openTime) "وقفهٔ بعدی کالا 17:00–18:00 نیویورک"
                else "جمعه 17:00 نیویورک",
            detail = when {
                dailyBreak -> "وقفهٔ روزانهٔ کالا؛ زمان بازگشایی تقریبی است و فید تازه لازم است"
                closed -> "تعطیلی برنامه‌ای؛ تعطیلات رسمی/توقف نماد با فید معتبر بررسی شوند"
                else -> "داخل ساعت برنامه‌ای؛ بازبودن واقعی فقط با فید تازه تأیید می‌شود"
            },
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
