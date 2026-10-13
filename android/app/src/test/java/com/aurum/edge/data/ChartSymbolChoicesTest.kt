package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSymbolChoicesTest {
    @Test fun `visual chart picker offers mapped markets without gold substitution`() {
        val eur = ChartSymbolChoices.search("EUR/USD").single { it.id == "EUR/USD" }
        assertEquals("OANDA:EURUSD", eur.ticker)
        assertEquals("OANDA:EURUSD", TradingViewSymbols.find(eur.id))
        assertTrue(TradingViewSymbols.find("ETH/USDT") in ChartSymbolChoices.search("eth").map { it.ticker })
        assertTrue(ChartSymbolChoices.search("NASDAQ:AAPL").isEmpty())
        assertTrue(ChartSymbolChoices.search("NOT-A-REAL-SYMBOL").isEmpty())
        assertFalse(ChartSymbolChoices.search("EUR/USD").any { it.ticker == "OANDA:XAUUSD" })
    }

    @Test fun `every offered symbol is an exact TradingView mapping`() {
        ChartSymbolChoices.all.forEach { choice ->
            assertEquals(choice.id, ChartSymbolChoices.display(choice.ticker))
            assertEquals(TradingViewSymbols.find(choice.id), choice.ticker)
        }
        assertEquals("UNKNOWN:ABC", ChartSymbolChoices.display("UNKNOWN:ABC"))
    }
}
