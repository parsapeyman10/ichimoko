package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قاعدهٔ «حد ضرر متناسب با شرایط».
 *
 * چرا تست دارد؟ چون آستانهٔ ثابت ۱٫۵R برای همهٔ بازارها غلط است: در بازار پرنوسان
 * همان ۱٫۵R خیلی زود با نویز زده می‌شود و در روند قوی خیلی دیر. این تست‌ها قید می‌کنند
 * که آستانه در جهت درست حرکت کند و هیچ‌وقت از بازهٔ ایمن بیرون نزند.
 */
class AdaptiveStopRuleTest {

    private fun threshold(
        atrPercent: Double,
        spreadAtr: Double,
        cloudAtr: Double,
        interval: Interval = Interval.M15,
    ) = IchimokuStrategyEngine.adaptiveStopMoveThresholdR(atrPercent, spreadAtr, cloudAtr, interval)

    @Test
    fun `threshold always stays inside the safe band`() {
        val cases = listOf(
            Triple(0.0, 0.0, 0.0),
            Triple(5.0, 5.0, 5.0),
            Triple(-1.0, -1.0, -1.0),
            Triple(Double.NaN, Double.NaN, Double.NaN),
            Triple(0.05, 2.0, 3.0),
        )
        cases.forEach { (atrPercent, spread, cloud) ->
            listOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4).forEach { interval ->
                val r = threshold(atrPercent, spread, cloud, interval)
                assertTrue("threshold $r must stay in [1.2, 2.2]", r in 1.2..2.2)
            }
        }
    }

    @Test
    fun `high volatility demands more open profit before moving the stop`() {
        val calm = threshold(atrPercent = 0.20, spreadAtr = 0.3, cloudAtr = 0.4)
        val wild = threshold(atrPercent = 1.20, spreadAtr = 0.3, cloudAtr = 0.4)
        assertTrue("$wild must be greater than $calm", wild > calm)
        assertTrue("wild volatility must clear 1.9R, was $wild", wild >= 1.9)
    }

    @Test
    fun `strong trend with thick cloud allows an earlier structural lock`() {
        val strong = threshold(atrPercent = 0.40, spreadAtr = 0.80, cloudAtr = 1.00)
        val weak = threshold(atrPercent = 0.40, spreadAtr = 0.10, cloudAtr = 0.40)
        assertTrue("$strong must be lower than $weak", strong < weak)
        assertTrue("strong trend should lock by 1.3R, was $strong", strong <= 1.3)
    }

    @Test
    fun `lower timeframes add noise headroom`() {
        val m5 = threshold(0.30, 0.30, 0.40, Interval.M5)
        val h4 = threshold(0.30, 0.30, 0.40, Interval.H4)
        assertEquals("M5 must be exactly 0.1R above H4", m5 - 0.1, h4, 1e-9)
    }

    @Test
    fun `null or broken inputs never unlock the stop without real evidence`() {
        // ورودی خراب نباید آستانه را پایین‌تر از کف ببرد.
        val broken = threshold(Double.NaN, Double.NaN, Double.NaN, Interval.M5)
        assertTrue("broken inputs must not fall below 1.5R, was $broken", broken >= 1.5)
    }
}
