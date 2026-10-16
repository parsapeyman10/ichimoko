package com.aurum.edge.core

import java.time.Instant
import java.time.ZoneOffset

/** Descriptive exchange-volume hours, never a buy forecast or a substitute for V1 signals.
 * Require at least five separate closed H1 bars at EVERY UTC hour within 14 days. Zero/unknown
 * volume invalidates the window: many forex feeds expose tick count or omit size altogether.
 */
object VolumeSessionStats {
    data class Hour(val utcHour: Int, val meanVolume: Double, val samples: Int)

    fun cryptoHours(symbol: String, interval: Interval, candles: List<Candle>,
                    now: Long = System.currentTimeMillis()): List<Hour> {
        if (AssetClass.of(symbol) != AssetClass.CRYPTO || interval != Interval.H1) return emptyList()
        val bars = candles.filter { it.closed && it.time in (now - 14L * 24 * Interval.H1.millis)..(now - Interval.H1.millis) }
            .distinctBy { it.time }
        if (bars.size < 5 * 24 || bars.any { !it.volume.isFinite() || it.volume <= 0.0 }) return emptyList()
        val grouped = bars.groupBy { Instant.ofEpochMilli(it.time).atZone(ZoneOffset.UTC).hour }
        if ((0..23).any { grouped[it].orEmpty().size < 5 }) return emptyList()
        return (0..23).map { hour ->
            val samples = grouped.getValue(hour)
            Hour(hour, samples.map { it.volume }.average(), samples.size)
        }.sortedWith(compareByDescending<Hour> { it.meanVolume }.thenBy { it.utcHour }).take(3)
    }
}
