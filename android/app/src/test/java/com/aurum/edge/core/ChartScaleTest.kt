package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * محافظ کادر قیمت چارت.
 *
 * سابقه: روی گوشی کاربر (گزارش ۳٫۱۲٫۰) بوم چارت کامل خالی شد چون یک مقدار خراب وارد محاسبهٔ
 * کادر شده بود و همهٔ قیمت‌ها به لبه چسبیدند. این تست‌ها همان سناریوها را قفل می‌کنند.
 */
class ChartScaleTest {

    private fun window(
        lows: List<Double>,
        highs: List<Double>,
        overlays: List<Double> = emptyList(),
        extras: List<Double> = emptyList(),
        fallback: Double? = null,
    ) = ChartScale.priceWindow(lows, highs, overlays, extras, fallback = fallback)

    @Test
    fun normalCandlesProduceATightWindow() {
        val w = window(listOf(100.0, 101.0, 99.5), listOf(102.0, 103.0, 101.5))
        assertEquals(99.5, w.min, 1e-9)
        assertEquals(103.0, w.max, 1e-9)
        assertTrue(w.span > 0.0 && w.span.isFinite())
    }

    @Test
    fun aWildOverlayCannotSwallowTheChart() {
        // این همان خرابی گزارش‌شده است: یک مقدار چند مرتبه بزرگ‌تر از قیمت.
        val w = window(
            lows = List(60) { 3_770.0 },
            highs = List(60) { 3_780.0 },
            overlays = listOf(3_775.0, 1.0e9, Double.NaN, Double.POSITIVE_INFINITY, 3_782.0),
        )
        assertEquals(3_770.0, w.min, 1e-9)
        assertTrue("سقف کادر نباید از قیمت‌های واقعی بگذرد، بود ${w.max}", w.max <= 3_800.0)
        assertTrue(w.max >= 3_782.0)
    }

    @Test
    fun nonFiniteCandlesAreIgnored() {
        val w = window(
            lows = listOf(100.0, Double.NaN, 101.0),
            highs = listOf(102.0, Double.POSITIVE_INFINITY, 103.0),
        )
        assertEquals(100.0, w.min, 1e-9)
        assertEquals(103.0, w.max, 1e-9)
    }

    @Test
    fun anEmptyOrBrokenSeriesFallsBackToTheLivePrice() {
        val w = window(emptyList(), emptyList(), fallback = 3_773.06)
        assertTrue(w.min.isFinite() && w.max.isFinite())
        assertTrue(w.max > w.min)
        assertTrue(3_773.06 in w.min..w.max)
    }

    @Test
    fun anOpenTradeTargetInsideTheAllowedBandWidensTheWindow() {
        val w = window(
            lows = List(30) { 3_770.0 },
            highs = List(30) { 3_780.0 },
            extras = listOf(3_774.0, 3_790.0),
        )
        assertEquals(3_790.0, w.max, 1e-9)
    }

    @Test
    fun anAbsurdTradeTargetIsClamped() {
        val w = window(
            lows = List(30) { 3_770.0 },
            highs = List(30) { 3_780.0 },
            extras = listOf(50_000.0),
        )
        assertTrue("هدف پرت نباید کادر را باز کند، بود ${w.max}", w.max <= 3_780.0 + 10.0 * 8.0)
        assertFalse(w.max.isInfinite())
    }

    @Test
    fun paddingKeepsTheWindowFiniteAndOrdered() {
        val padded = ChartScale.padded(ChartScale.Window(100.0, 200.0), 0.10)
        assertEquals(90.0, padded.min, 1e-9)
        assertEquals(210.0, padded.max, 1e-9)
        assertTrue(padded.span.isFinite() && padded.span > 0.0)
    }

    @Test
    fun degenerateWindowStillHasASpan() {
        val w = ChartScale.Window(100.0, 100.0)
        assertTrue(w.span > 0.0)
        assertTrue(w.span.isFinite())
    }
}
