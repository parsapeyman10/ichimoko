package com.aurum.edge.engine

import com.aurum.edge.data.Quote
import com.aurum.edge.data.QuoteDisplayState
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchDisplay
import com.aurum.edge.data.WatchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchVerificationReasonsTest {
    private val now = 1_800_000_000_000L
    private fun quote(symbolId: String, sourceId: String, time: Long? = now): Quote {
        val symbol = WatchCatalog.find(symbolId)!!
        return Quote(code = symbol.providerCodes[sourceId]!!, label = symbol.label, price = 100_000.0,
            unit = symbol.unit, sourceId = sourceId, ts = now, providerAt = time)
    }

    @Test fun `all Iranian cards explain undated web quotes old mirror and single source cases`() {
        WatchCatalog.symbols.filter { it.id.endsWith("/IRT") }.forEach { symbol ->
            val quotes = symbol.providerCodes.keys.associateWith { id ->
                quote(symbol.id, id, if (id == SourceCatalog.tgju.id) null else now - 5 * 3_600_000L)
            }
            val result = SourceComparison.verify(symbol, symbol.defaultSources, quotes, now)
            assertEquals("${symbol.id} should not claim confirmation", VerificationStatus.UNVERIFIED, result.status)
            assertEquals(0, result.freshSources)
            assertTrue(result.reason.contains("تاریخ کامل"))
            if (symbol.providerCodes.size == 1) {
                assertEquals("زمان/تک‌منبع", result.badge)
                assertTrue(result.reason.contains("منبع مستقل دومی"))
            } else {
                assertEquals("زمان/قدمت", result.badge)
                assertTrue(result.reason.contains("Navasan"))
                assertTrue(result.reason.contains("قدیمی/نامعتبر"))
            }
            val selection = WatchSelection(symbol.defaultSources, SourceCatalog.tgju.id)
            val display = WatchDisplay.choose(symbol, selection, quotes, now)
            assertEquals(SourceCatalog.tgju.id, display.sourceId)
            assertTrue(SourceComparison.assess(symbol, display.sourceId, display.quote, now).readable)
            assertFalse(SourceComparison.isFresh(symbol, display.quote!!, now))
        }
    }

    @Test fun `one source fresh still requires another independent fresh source`() {
        val btc = WatchCatalog.find("BTC/USD")!!
        val a = btc.defaultSources[0]
        val b = btc.defaultSources[1]
        val one = SourceComparison.verify(btc, listOf(a), mapOf(a to quote(btc.id, a)), now)
        assertEquals(VerificationStatus.UNVERIFIED, one.status)
        assertEquals("تک‌منبعی", one.badge)
        assertTrue(one.reason.contains("فقط یک منبع فعال"))
        val old = quote(btc.id, b, time = now - btc.maxAgeMillis - 1)
        val result = SourceComparison.verify(btc, listOf(a, b), mapOf(a to quote(btc.id, a), b to old), now)
        assertEquals("قیمت قدیمی", result.badge)
        assertEquals(1, result.freshSources)
        assertEquals(QuoteDisplayState.OLD, SourceComparison.assess(btc, b, old, now).state)
    }

    @Test fun `a spoofed source key or wrong unit cannot count as second independent quote`() {
        val btc = WatchCatalog.find("BTC/USD")!!
        val a = btc.defaultSources[0]
        val b = btc.defaultSources[1]
        val real = quote(btc.id, a)
        val forged = SourceComparison.verify(btc, listOf(a, b), mapOf(a to real, b to real), now)
        assertEquals(VerificationStatus.UNVERIFIED, forged.status)
        assertEquals(1, forged.freshSources)
        assertTrue(forged.reason.contains("شناسهٔ منبع"))
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(btc, listOf(a, b),
            mapOf(a to real, b to quote(btc.id, b).copy(unit = "تومان")), now).status)
        val cached = quote(btc.id, b).copy(stale = true, error = "HTTP 503")
        assertEquals("قطع/کش منبع", SourceComparison.verify(btc, listOf(a, b),
            mapOf(a to real, b to cached), now).badge)
        assertEquals(VerificationStatus.CONFIRMED, SourceComparison.verify(btc, listOf(a, b),
            mapOf(a to real, b to quote(btc.id, b).copy(price = 100_200.0)), now).status)
    }
}
