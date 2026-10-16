package com.aurum.edge.engine

import com.aurum.edge.data.Quote
import com.aurum.edge.data.QuoteDisplayState
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchDisplay
import com.aurum.edge.data.WatchSelection
import com.aurum.edge.data.WatchSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchVerificationReasonsTest {
    private val now = 1_800_000_000_000L
    private val dualEur = WatchSymbol(
        id = "EUR/USD",
        label = "یورو / دلار",
        unit = "$",
        providerCodes = linkedMapOf("yahoo_eurusd" to "EURUSD=X", "swissquote_gbpusd" to "EUR/USD"),
        defaultSources = listOf("yahoo_eurusd", "swissquote_gbpusd"),
        maxAgeMillis = 5 * 60_000L,
        tolerancePct = 0.5,
    )
    private fun quote(symbol: WatchSymbol, sourceId: String, time: Long? = now): Quote =
        Quote(code = symbol.providerCodes[sourceId]!!, label = symbol.label, price = 1.0850,
            unit = symbol.unit, sourceId = sourceId, ts = now, providerAt = time)

    @Test fun `single dedicated source in WatchCatalog confirms when fresh and dated`() {
        val jpy = WatchCatalog.find("USD/JPY")!!
        val only = jpy.defaultSources.single()
        val one = SourceComparison.verify(jpy, listOf(only), mapOf(only to quote(jpy, only)), now)
        assertEquals(VerificationStatus.CONFIRMED, one.status)
        assertEquals("سورس اختصاصی", one.badge)
    }

    @Test fun `multi-source fixture still requires both sources when configured`() {
        val sources = dualEur.providerCodes.keys.toList()
        val a = sources[0]
        val b = sources[1]
        val one = SourceComparison.verify(dualEur, listOf(a), mapOf(a to quote(dualEur, a)), now)
        assertEquals(VerificationStatus.UNVERIFIED, one.status)
        assertEquals("تک‌منبعی", one.badge)
        val old = quote(dualEur, b, time = now - dualEur.maxAgeMillis - 1)
        val result = SourceComparison.verify(dualEur, listOf(a, b), mapOf(a to quote(dualEur, a), b to old), now)
        assertEquals("قیمت قدیمی", result.badge)
        assertEquals(1, result.freshSources)
        assertEquals(QuoteDisplayState.OLD, SourceComparison.assess(dualEur, b, old, now).state)
    }

    @Test fun `a spoofed source key or wrong unit cannot count as valid quote`() {
        val sources = dualEur.providerCodes.keys.toList()
        val a = sources[0]
        val b = sources[1]
        val real = quote(dualEur, a)
        val forged = SourceComparison.verify(dualEur, listOf(a, b), mapOf(a to real, b to real), now)
        assertEquals(VerificationStatus.UNVERIFIED, forged.status)
        assertEquals(1, forged.freshSources)
        assertTrue(forged.reason.contains("شناسهٔ منبع"))
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(dualEur, listOf(a, b),
            mapOf(a to real, b to quote(dualEur, b).copy(unit = "تومان")), now).status)
        val cached = quote(dualEur, b).copy(stale = true, error = "HTTP 503")
        assertEquals("قطع/کش منبع", SourceComparison.verify(dualEur, listOf(a, b),
            mapOf(a to real, b to cached), now).badge)
        assertEquals(VerificationStatus.CONFLICT, SourceComparison.verify(dualEur, listOf(a, b),
            mapOf(a to real, b to quote(dualEur, b).copy(price = 1.2000)), now).status)
        assertEquals(VerificationStatus.CONFIRMED, SourceComparison.verify(dualEur, listOf(a, b),
            mapOf(a to real, b to quote(dualEur, b).copy(price = 1.0872)), now).status)
        val selection = WatchSelection(listOf(a, b), a)
        val display = WatchDisplay.choose(dualEur, selection, mapOf(a to real, b to quote(dualEur, b)), now)
        assertEquals(a, display.sourceId)
        assertTrue(SourceComparison.assess(dualEur, display.sourceId, display.quote, now).readable)
        assertFalse(SourceComparison.isFresh(dualEur, cached, now))
    }
}
