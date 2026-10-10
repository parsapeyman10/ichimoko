package com.aurum.edge.engine

import com.aurum.edge.core.OilEntrySafety
import com.aurum.edge.core.PaperPortfolioPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OilEntrySafetyTest {
    @Test fun `all oil aliases fail closed for new entries`() {
        listOf("WTI/USD", "USOIL", "UKOIL", "BRENT/USD", "CL", "BZ", "CRUDEOIL")
            .forEach { symbol ->
                assertEquals(symbol, OilEntrySafety.REASON, OilEntrySafety.blocker(symbol))
                assertEquals(symbol, OilEntrySafety.REASON,
                    PaperPortfolioPolicy.blocker(emptyList(), symbol, 1000.0, 1.0))
            }
    }

    @Test fun `other markets keep their portfolio policy`() {
        listOf("XAU/USD", "XAG/USD", "NATGAS/USD", "BTCUSDT", "EUR/USD")
            .forEach { symbol ->
                assertNull(symbol, OilEntrySafety.blocker(symbol))
                assertNull(symbol, PaperPortfolioPolicy.blocker(emptyList(), symbol, 1000.0, 1.0))
            }
    }
}
