package com.aurum.edge.engine

import com.aurum.edge.core.V1Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "similar engines must never interfere" contract, asserted instead of assumed.
 *
 * Gold (ALS-5), silver (four-layer V1) and BTC (BTC-ARES) are different models with different
 * numbers and different evidence types. These tests lock the routing so a later change cannot make
 * two of them score the same symbol, or make a new engine silently own a symbol it was not given.
 */
class EngineRegistryTest {

    @Test fun `the automatic entry note names the engine that actually authorised it`() {
        val gold = EngineRegistry.autoEntryNote("XAU/USD")
        val btc = EngineRegistry.autoEntryNote("BTC/USDT")
        val silver = EngineRegistry.autoEntryNote("XAG/USD")
        val eth = EngineRegistry.autoEntryNote("ETH/USDT")
        val eur = EngineRegistry.autoEntryNote("EUR/USD")
        assertTrue(gold.contains("ALS-5"))
        assertTrue(btc.contains("BTC-ARES"))
        assertTrue(silver.contains("ALS-5S"))
        assertTrue(eth.contains("فشردگی"))
        assertTrue(eur.contains("V1"))
        assertFalse(silver.contains("V1"))
        // A gold or BTC trade must never be described to the user as a four-layer entry.
        assertFalse(btc.contains("چهارلایه"))
        assertFalse(gold.contains("چهارلایه"))
        assertFalse(btc.contains("فشردگی"))
        assertEquals(5, setOf(gold, btc, silver, eth, eur).size)
    }

    @Test fun `every symbol of the universe has exactly one engine`() {
        val assignments = EngineRegistry.assignments()
        assertEquals(V1Universe.allSymbols.toSet(), assignments.keys)
        V1Universe.allSymbols.forEach { symbol ->
            val owners = EngineRegistry.engineFor(symbol)
            assertEquals("double ownership for $symbol", assignments.getValue(symbol), owners)
        }
    }

    @Test fun `gold silver and bitcoin never share a model`() {
        assertEquals(SymbolEngine.ALS5_GOLD, EngineRegistry.engineFor("XAU/USD"))
        assertEquals(SymbolEngine.ALS5S_SILVER, EngineRegistry.engineFor("XAG/USD"))
        assertEquals(SymbolEngine.BTC_ARES, EngineRegistry.engineFor("BTC/USDT"))
        assertEquals(SymbolEngine.CRYPTO_COMPRESSION, EngineRegistry.engineFor("ETH/USDT"))
        assertEquals(SymbolEngine.V1_FOUR_LAYER, EngineRegistry.engineFor("EUR/USD"))
        assertEquals(SymbolEngine.V1_FOUR_LAYER, EngineRegistry.engineFor("DOW"))
    }

    @Test fun `btc is not a member of the gold or silver symbol sets`() {
        assertEquals(setOf("XAU/USD"), EngineRegistry.goldSymbols())
        assertEquals(setOf("BTC/USDT"), EngineRegistry.btcAresSymbols())
        assertEquals(setOf("XAG/USD"), EngineRegistry.silverAresSymbols())
        assertTrue("BTC/USDT" !in EngineRegistry.goldSymbols())
        assertTrue("XAG/USD" !in EngineRegistry.btcAresSymbols())
        assertTrue("XAU/USD" !in EngineRegistry.silverAresSymbols())
        assertTrue("ETH/USDT" !in EngineRegistry.silverAresSymbols())
    }

    @Test fun `silver aliases cannot fall back to gold or to the four layer model`() {
        assertEquals(SymbolEngine.ALS5S_SILVER, EngineRegistry.engineFor("xag/usd"))
        assertEquals(SymbolEngine.ALS5S_SILVER, EngineRegistry.engineFor("XAGUSD"))
        assertNotEquals(SymbolEngine.ALS5_GOLD, EngineRegistry.engineFor("XAGUSD"))
        assertNotEquals(SymbolEngine.V1_FOUR_LAYER, EngineRegistry.engineFor("XAGUSD"))
        // Gold must not be re-routed to silver by the alias branch.
        assertEquals(SymbolEngine.ALS5_GOLD, EngineRegistry.engineFor("XAUUSD"))
    }

    @Test fun `normalisation cannot route an alias into the wrong engine`() {
        assertEquals(SymbolEngine.ALS5_GOLD, EngineRegistry.engineFor(" xau/usd "))
        assertEquals(SymbolEngine.BTC_ARES, EngineRegistry.engineFor("btc/usdt"))
        assertEquals(SymbolEngine.V1_FOUR_LAYER, EngineRegistry.engineFor("^DJI"))
        // An unknown pair still gets ONE owner; it must never be guessed into BTC-ARES.
        assertEquals(SymbolEngine.CRYPTO_COMPRESSION, EngineRegistry.engineFor("DOGE/USDT"))
        assertTrue(EngineRegistry.engineFor("DOGE/USDT") != SymbolEngine.BTC_ARES)
        assertEquals(SymbolEngine.V1_FOUR_LAYER, EngineRegistry.engineFor("SOMETHING/NOTCRYPTO"))
        assertEquals(SymbolEngine.ALS5S_SILVER, EngineRegistry.engineFor("XAG/USD"))
    }

    @Test fun `setup keys are namespaced by symbol and engine`() {
        val gold = EngineRegistry.setupKey("XAU/USD", "ENGINE_A_TREND", "BUY", 2400.0, 1_000L)
        val btc = EngineRegistry.setupKey("BTC/USDT", "ENGINE_A_TREND", "BUY", 2400.0, 1_000L)
        val btcOtherEngine = EngineRegistry.setupKey("BTC/USDT", "ENGINE_C_COMPRESSION", "BUY", 2400.0, 1_000L)
        assertNotEquals(gold, btc)
        assertNotEquals(btc, btcOtherEngine)
        assertTrue(btc.startsWith("BTC/USDT|ENGINE_A_TREND|"))
    }
}
