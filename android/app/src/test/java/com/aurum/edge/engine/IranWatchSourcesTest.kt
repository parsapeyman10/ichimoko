package com.aurum.edge.engine

import com.aurum.edge.data.Quote
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.SymbolDef
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchDisplay
import com.aurum.edge.data.WatchSelection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IranWatchSourcesTest {
    private val now = 1_800_000_000_000L
    private val fetcher = SourceFetcher()
    private val dollar = WatchCatalog.find("USD/IRT")!!
    private val gold = WatchCatalog.find("GOLD18/IRT")!!

    @Test fun `TGJU row identity and rial to toman conversion are required`() {
        val html = """<table class="market-table"><tbody>
            <tr data-market-nameslug="price_dollar_rl" data-price="2,350,000"><th>دلار</th>
                <td>2,350,000</td><td>۹:۳۵:۰۰</td></tr>
            <tr data-market-nameslug="geram18" data-price="۲۴۰٬۰۰۰٬۰۰۰"><th>طلای ۱۸ عیار</th></tr>
            <tr data-market-nameslug="gerami" data-price="340,000,000"><th>سکهٔ یک گرمی</th></tr>
        </tbody></table>"""
        val usd = fetcher.parseHtmlQuote(SourceCatalog.tgju, SymbolDef("price_dollar_rl", "دلار"), html)
        val gram = fetcher.parseHtmlQuote(SourceCatalog.tgju, SymbolDef("geram18", "طلا"), html)
        val coin = fetcher.parseHtmlQuote(SourceCatalog.tgju, SymbolDef("gerami", "سکهٔ یک‌گرمی"), html)
        assertEquals(235_000.0, usd.price!!, 0.001)
        assertEquals(24_000_000.0, gram.price!!, 0.001)
        assertEquals(34_000_000.0, coin.price!!, 0.001)
        assertNull(usd.providerAt) // the site's row has only a time of day, no verified date
        assertFalse(SourceComparison.isFresh(dollar, usd.copy(ts = now), now))
        val missing = fetcher.parseHtmlQuote(SourceCatalog.tgju, SymbolDef("sekee", "سکه"), html)
        assertNull(missing.price)
        assertTrue(missing.error!!.contains("ردیف"))
        val invalid = fetcher.parseHtmlQuote(SourceCatalog.tgju, SymbolDef("geram18", "طلا"),
            "<tr data-market-nameslug='geram18' data-price='N/A'></tr>")
        assertNull(invalid.price)
    }

    @Test fun `Navasan supplies actual provider time and readable fallback`() {
        val root = Json.parseToJsonElement("""{"usd":{"value":235200,"date":1800000000,"change_pct":0.2},
            "18ayar":{"value":24030000,"date":1800000000,"change_pct":0.4}}""")
        val mirror = fetcher.parseJsonQuote(SourceCatalog.navasanFiat, SymbolDef("usd", "دلار"), root).copy(ts = now)
        val metal = fetcher.parseJsonQuote(SourceCatalog.navasanGold, SymbolDef("18ayar", "طلا"), root).copy(ts = now)
        assertEquals(now, mirror.providerAt)
        assertEquals(235_200.0, mirror.price!!, 0.001)
        assertTrue(SourceComparison.isFresh(dollar, mirror, now))
        assertTrue(SourceComparison.isFresh(gold, metal, now))
        val selection = WatchSelection(listOf(SourceCatalog.tgju.id, SourceCatalog.navasanFiat.id), SourceCatalog.tgju.id)
        val offline = Quote("price_dollar_rl", "دلار", price = 234_000.0, unit = "تومان",
            sourceId = SourceCatalog.tgju.id, stale = true, ts = now - 80_000L)
        val chosen = WatchDisplay.choose(dollar, selection, mapOf(
            SourceCatalog.tgju.id to offline, SourceCatalog.navasanFiat.id to mirror), now)
        assertTrue(chosen.fallback)
        assertEquals(SourceCatalog.navasanFiat.id, chosen.sourceId)
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(dollar,
            selection.enabledSources, mapOf(SourceCatalog.tgju.id to offline,
                SourceCatalog.navasanFiat.id to mirror), now).status)
        val expired = mirror.copy(providerAt = now - dollar.maxAgeMillis - 1)
        assertFalse(SourceComparison.isFresh(dollar, expired, now))
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(dollar,
            selection.enabledSources, mapOf(SourceCatalog.tgju.id to offline,
                SourceCatalog.navasanFiat.id to expired), now).status) // old prices aren't agreement
    }
}
