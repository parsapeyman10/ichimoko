package com.aurum.edge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlineImpactTest {
    @Test fun `each named asset gets its own movement rather than a generic bullish label`() {
        val lines = HeadlineImpact.explain("Gold rises as dollar falls")
        assertTrue(lines.any { it.contains("XAU/USD") && it.contains("↑") })
        assertTrue(lines.any { it.contains("USD") && it.contains("↓") && it.contains("EUR/USD") })
        assertFalse(lines.any { it.contains("طلا") && it.contains("↓") })
    }

    @Test fun `CPI and a vague rally never assert which instrument will rise`() {
        assertTrue(HeadlineImpact.explain("US CPI rises above forecasts").all { it.contains("جهت") })
        assertTrue(HeadlineImpact.explain("Markets rally ahead of data").all { it.contains("مشخص") })
        assertTrue(HeadlineImpact.explain("Bitcoin falls while gold gains").any { it.contains("BTC/USDT") && it.contains("↓") })
        assertTrue(HeadlineImpact.explain("Bitcoin falls while gold gains").any { it.contains("XAU/USD") && it.contains("↑") })
    }
}
