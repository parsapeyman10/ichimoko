package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching a 400-row exchange by a fragment of a name.
 *
 * The failure modes being pinned here are the ones that make a picker feel broken:
 * "bitcoin" finding nothing because the ticker is BTC, "sol" putting SOLV above SOL,
 * and a typo returning a blank screen with no hint.
 */
class SymbolSearchTest {

    private fun pair(base: String, quote: String = "USDT", volume: Double = 0.0) =
        BinanceUniverse.Pair(
            id = "$base/$quote", binance = "$base$quote",
            base = base, quote = quote, digits = 2, volume = volume,
        )

    private val universe = listOf(
        pair("BTC", volume = 9_000_000_000.0),
        pair("ETH", volume = 5_000_000_000.0),
        pair("SOL", volume = 2_000_000_000.0),
        pair("SOLV", volume = 3_000_000.0),
        pair("WBTC", volume = 20_000_000.0),
        pair("PEPE", volume = 900_000_000.0),
        pair("DOGE", volume = 800_000_000.0),
        pair("SHIB", volume = 400_000_000.0),
        pair("1INCH", volume = 10_000_000.0),
        pair("ETH", quote = "BTC", volume = 100_000_000.0),
    )

    // ---------- ranking ----------

    @Test fun `an exact ticker beats a longer one that merely contains it`() {
        val ranked = SymbolSearch.rank("sol", universe)
        assertEquals("SOL/USDT", ranked.first().id)
        assertTrue("SOLV/USDT" in ranked.map { it.id })
    }

    @Test fun `btc outranks wbtc`() {
        assertEquals("BTC/USDT", SymbolSearch.rank("btc", universe).first().id)
    }

    @Test fun `written names resolve to the ticker in english and persian`() {
        assertEquals("BTC", SymbolSearch.aliasFor("bitcoin"))
        assertEquals("BTC", SymbolSearch.aliasFor("بیت‌کوین"))
        assertEquals("BTC", SymbolSearch.aliasFor("بیت کوین"))
        assertEquals("ETH", SymbolSearch.aliasFor("اتریوم"))
        assertEquals("PEPE", SymbolSearch.aliasFor("پپه"))
        assertEquals("DOGE", SymbolSearch.aliasFor("دوج"))

        assertEquals("BTC/USDT", SymbolSearch.rank("bitcoin", universe).first().id)
        assertEquals("BTC/USDT", SymbolSearch.rank("بیت‌کوین", universe).first().id)
        assertEquals("PEPE/USDT", SymbolSearch.rank("پپه", universe).first().id)
    }

    @Test fun `arabic letter variants and the zero-width joiner are normalised`() {
        // Typed with Arabic ya/kaf rather than the Persian forms.
        assertEquals("BTC", SymbolSearch.aliasFor("بيت كوين"))
        assertEquals("ETH", SymbolSearch.aliasFor("اتريوم"))
    }

    @Test fun `ties break on real turnover, not the alphabet`() {
        // Both contain "e"; the busier market must come first.
        val ranked = SymbolSearch.rank("e", universe)
        val pepe = ranked.indexOfFirst { it.id == "PEPE/USDT" }
        val doge = ranked.indexOfFirst { it.id == "DOGE/USDT" }
        assertTrue(pepe in 0..doge)
    }

    @Test fun `an empty query returns the list untouched so the default order survives`() {
        assertEquals(universe.map { it.id }, SymbolSearch.rank("", universe).map { it.id })
        assertEquals(universe.map { it.id }, SymbolSearch.rank("   ", universe).map { it.id })
    }

    @Test fun `a query matching nothing ranks nothing`() {
        assertTrue(SymbolSearch.rank("zzzzzz", universe).isEmpty())
    }

    // ---------- suggestions ----------

    @Test fun `a typo proposes the nearest tickers instead of a blank screen`() {
        val suggestions = SymbolSearch.suggest("dogee", universe).map { it.base }
        assertTrue("DOGE expected in $suggestions", "DOGE" in suggestions)

        val pepeTypo = SymbolSearch.suggest("peoe", universe).map { it.base }
        assertTrue("PEPE expected in $pepeTypo", "PEPE" in pepeTypo)
    }

    @Test fun `suggestions stay quiet for a query too short to be a typo`() {
        assertTrue(SymbolSearch.suggest("b", universe).isEmpty())
        assertTrue(SymbolSearch.suggest("", universe).isEmpty())
    }

    @Test fun `suggestions do not repeat the same asset once per quote`() {
        val suggestions = SymbolSearch.suggest("eth", universe)
        assertEquals(suggestions.map { it.base }.distinct().size, suggestions.size)
    }

    @Test fun `edit distance is correct`() {
        assertEquals(0, SymbolSearch.distance("btc", "btc"))
        assertEquals(1, SymbolSearch.distance("btc", "btcc"))      // one insertion
        assertEquals(1, SymbolSearch.distance("doge", "dogo"))     // one substitution
        assertEquals(1, SymbolSearch.distance("pepe", "pee"))      // one deletion
        assertEquals(3, SymbolSearch.distance("kitten", "sitting"))
        assertEquals(4, SymbolSearch.distance("", "abcd"))
        assertEquals(4, SymbolSearch.distance("abcd", ""))
    }

    // ---------- volume ordering ----------

    @Test fun `24h turnover parses and drives the default order`() {
        val volumes = BinanceUniverse.parseVolumes("""
            [{"symbol":"BTCUSDT","quoteVolume":"9000000000.00"},
             {"symbol":"SOLVUSDT","quoteVolume":"3000000.00"},
             {"symbol":"BROKEN","quoteVolume":"not-a-number"}]
        """.trimIndent())
        assertEquals(9_000_000_000.0, volumes["BTCUSDT"]!!, 1.0)
        assertEquals(3_000_000.0, volumes["SOLVUSDT"]!!, 1.0)
        assertTrue("BROKEN" !in volumes)
    }

    @Test fun `a volume outage leaves the universe usable`() {
        assertTrue(BinanceUniverse.parseVolumes("nonsense").isEmpty())
        assertTrue(BinanceUniverse.parseVolumes("{}").isEmpty())
    }
}
