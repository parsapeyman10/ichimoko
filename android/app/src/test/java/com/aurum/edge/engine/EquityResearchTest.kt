package com.aurum.edge.engine

import com.aurum.edge.data.TtmResearch
import com.aurum.edge.data.parseEquityRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schemas are fixture-only; the provider's sample has a clock, not a dated price. */
class EquityResearchTest {
    private fun read(json: String) = parseEquityRows(Json.parseToJsonElement(json) as JsonArray)
    private val row = """{"time":"12:30:01","l18":"نماد","l30":"شرکت نمونه","isin":"IRO3SBAZ0001",
      "pc":1200,"pl":1210,"eps":140,"pe":8.6,"tval":20000000000,"tvol":100000,
      "Buy_I_Volume":6000,"Sell_I_Volume":3000,"Buy_CountI":10,"Sell_CountI":10,
      "pd1":1200,"po1":1201}"""

    @Test fun `brsapi clock-only row supports limited board math but not a dated execution quote`() {
        val result = read("[$row,${row.replace("IRO3SBAZ0001", "IRT3SBAZ0001")}]")
        assertEquals(1, result.size) // IRT fund excluded from shares/earnings filter
        val item = result.single()
        assertEquals("12:30:01", item.boardClock)
        assertEquals(1200L, item.closeRial)
        assertEquals(2.0, item.retailPower!!, 0.001)
        assertEquals(3000L, item.netRetailShares)
        assertTrue(item.bestSpreadPct!! < 1)
        assertTrue(item.basicValuePass && item.boardPass)
        assertNull(item.javaClass.declaredFields.firstOrNull { it.name == "tradeDate" })
    }

    @Test fun `missing metrics and corrupt clocks fail closed rather than passing a filter`() {
        val noEps = read("[${row.replace("\"eps\":140", "\"eps\":null")}]").single()
        assertFalse(noEps.basicValuePass)
        val noVolume = read("[${row.replace("\"Buy_I_Volume\":6000", "\"Buy_I_Volume\":null")}]").single()
        assertNull(noVolume.retailPower)
        assertFalse(noVolume.boardPass)
        assertTrue(runCatching { read("[${row.replace("12:30:01", "25:60:00") }]") }.isFailure)
        assertTrue(runCatching { read("[{\"message\":\"key invalid\"}]") }.isFailure)
    }

    @Test fun `manual fiscal TTM uses matching year-to-date difference and rejects unsafe inputs`() {
        assertEquals(7.0, TtmResearch.epsRial(1200.0, 400.0, 200.0, 200.0)!!, 0.001)
        assertEquals(-1.0, TtmResearch.epsRial(-400.0, 300.0, 100.0, 200.0)!!, 0.001)
        assertNull(TtmResearch.epsRial(1200.0, 400.0, 200.0, 0.0))
        assertNull(TtmResearch.epsRial(Double.NaN, 400.0, 200.0, 100.0))
    }
}
