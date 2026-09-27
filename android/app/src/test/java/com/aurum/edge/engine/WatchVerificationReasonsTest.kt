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
        return Quote(code = symbol.providerCodes[sourceId]!!, label = symbol.label, price = 1.0850,
            unit = symbol.unit, sourceId = sourceId, ts = now, providerAt = time)
    }

    @Test fun `single source symbols explain that no independent second source exists`() {
        val jpy = WatchCatalog.find("USD/JPY")!! // Twelve Data only: Yahoo quotes JPY in another unit
        val only = jpy.defaultSources.single()
        val one = SourceComparison.verify(jpy, listOf(only), mapOf(only to quote(jpy.id, only)), now)
        assertEquals(VerificationStatus.UNVERIFIED, one.status)
        assertEquals("تک‌منبعی", one.badge)
        // No second source is DEFINED for this symbol: the honest message says exactly that,
        // and must not imply a second source merely sits disabled ("فقط یک منبع فعال").
        assertFalse(one.reason.contains("فقط یک منبع فعال"))
        assertTrue(one.reason.contains("منبع مستقل دومی"))
    }

    @Test fun `one source fresh still requires another independent fresh source`() {
        val eur = WatchCatalog.find("EUR/USD")!!
        val a = eur.defaultSources[0]
        val b = eur.defaultSources[1]
        val one = SourceComparison.verify(eur, listOf(a), mapOf(a to quote(eur.id, a)), now)
        assertEquals(VerificationStatus.UNVERIFIED, one.status)
        assertEquals("تک‌منبعی", one.badge)
        assertTrue(one.reason.contains("فقط یک منبع فعال"))
        val old = quote(eur.id, b, time = now - eur.maxAgeMillis - 1)
        val result = SourceComparison.verify(eur, listOf(a, b), mapOf(a to quote(eur.id, a), b to old), now)
        assertEquals("قیمت قدیمی", result.badge)
        assertEquals(1, result.freshSources)
        assertEquals(QuoteDisplayState.OLD, SourceComparison.assess(eur, b, old, now).state)
    }

    @Test fun `a spoofed source key or wrong unit cannot count as second independent quote`() {
        val eur = WatchCatalog.find("EUR/USD")!!
        val a = eur.defaultSources[0]
        val b = eur.defaultSources[1]
        val real = quote(eur.id, a)
        val forged = SourceComparison.verify(eur, listOf(a, b), mapOf(a to real, b to real), now)
        assertEquals(VerificationStatus.UNVERIFIED, forged.status)
        assertEquals(1, forged.freshSources)
        assertTrue(forged.reason.contains("شناسهٔ منبع"))
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(eur, listOf(a, b),
            mapOf(a to real, b to quote(eur.id, b).copy(unit = "تومان")), now).status)
        val cached = quote(eur.id, b).copy(stale = true, error = "HTTP 503")
        assertEquals("قطع/کش منبع", SourceComparison.verify(eur, listOf(a, b),
            mapOf(a to real, b to cached), now).badge)
        assertEquals(VerificationStatus.CONFLICT, SourceComparison.verify(eur, listOf(a, b),
            mapOf(a to real, b to quote(eur.id, b).copy(price = 1.2000)), now).status)
        assertEquals(VerificationStatus.CONFIRMED, SourceComparison.verify(eur, listOf(a, b),
            mapOf(a to real, b to quote(eur.id, b).copy(price = 1.0872)), now).status)
        val selection = WatchSelection(eur.defaultSources, a)
        val display = WatchDisplay.choose(eur, selection, mapOf(a to real, b to quote(eur.id, b)), now)
        assertEquals(a, display.sourceId)
        assertTrue(SourceComparison.assess(eur, display.sourceId, display.quote, now).readable)
        assertFalse(SourceComparison.isFresh(eur, cached, now))
    }
}
