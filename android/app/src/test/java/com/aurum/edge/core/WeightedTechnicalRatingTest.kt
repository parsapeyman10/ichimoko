package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class WeightedTechnicalRatingTest {
    @Test fun exitNeedsProfitFreshPriceAndOpenMarket() {
        assertTrue(WeightedTechnicalRating.shouldExit(-0.5, 7.5, 5.0, true, true))
        assertFalse(WeightedTechnicalRating.shouldExit(-0.5, 7.49, 5.0, true, true))
        assertFalse(WeightedTechnicalRating.shouldExit(-0.49, 10.0, 5.0, true, true))
        assertFalse(WeightedTechnicalRating.shouldExit(-1.0, 10.0, 5.0, false, true))
        assertFalse(WeightedTechnicalRating.shouldExit(-1.0, 10.0, 5.0, true, false))
        assertFalse(WeightedTechnicalRating.shouldExit(null, 10.0, 5.0, true, true))
    }
    @Test fun weightingAndMissing() {
        val up = TechnicalRating(26, 0, 0)
        val down = TechnicalRating(0, 0, 26)
        assertEquals(-0.2, WeightedTechnicalRating.score(SignalAction.BUY, up, down)!!, 1e-9)
        assertEquals(0.2, WeightedTechnicalRating.score(SignalAction.SELL, up, down)!!, 1e-9)
        assertNull(WeightedTechnicalRating.score(SignalAction.BUY, null, down))
    }
}
