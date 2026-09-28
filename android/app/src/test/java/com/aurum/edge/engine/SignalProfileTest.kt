package com.aurum.edge.engine

import com.aurum.edge.core.SignalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProfileTest {
    @Test fun `signal add ons are additive and persist as a comma list`() {
        val profile = SignalProfile(
            momentumVolume = true,
            flatSpanB = true,
            rangeChopFilter = true,
            higherTimeframeFilter = true,
            fakeBreakoutFilter = true,
            dynamicSpreadFilter = true,
            riskyTimingFilter = true,
            structureRiskFilter = true,
            cooldownFilter = true,
        )

        val restored = SignalProfile.fromName(profile.persistName())

        assertTrue(restored.momentumVolume)
        assertTrue(restored.flatSpanB)
        assertTrue(restored.rangeChopFilter)
        assertTrue(restored.higherTimeframeFilter)
        assertTrue(restored.fakeBreakoutFilter)
        assertTrue(restored.dynamicSpreadFilter)
        assertTrue(restored.riskyTimingFilter)
        assertTrue(restored.structureRiskFilter)
        assertTrue(restored.cooldownFilter)
        assertTrue(restored.activeLabels().first() == "پایه")
    }

    @Test fun `legacy single-choice names still migrate`() {
        assertTrue(SignalProfile.fromName("MOMENTUM_VOLUME").momentumVolume)
        assertTrue(SignalProfile.fromName("FLAT_SPAN_B").flatSpanB)
        assertEquals("BASE", SignalProfile.BASE.persistName())
        assertFalse(SignalProfile.fromName("BASE").momentumVolume)
    }
}
