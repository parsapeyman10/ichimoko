package com.aurum.edge.data

import com.aurum.edge.core.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SourceIntegrityTest {
    private val symbol = "XAU/USD"
    private val history = SourceIntegrity.Channel.HISTORY
    private val quote = SourceIntegrity.Channel.GOLD_QUOTE
    @Before fun reset() { SourceIntegrity.shared.resetForTests() }
    @After fun cleanup() { SourceIntegrity.shared.resetForTests() }

    @Test fun `fresh tick time route toggle and single source cannot clear a known disagreement`() {
        val gate = SourceIntegrity()
        val old = gate.revision(symbol)
        gate.reject(symbol, history, "history disagreement")
        val token = gate.begin(symbol, history)
        assertNull(gate.accept(symbol, history, token, 1))
        assertEquals("history disagreement", gate.blocker(symbol, old))
        assertEquals("history disagreement", gate.blocker(symbol, gate.revision(symbol)))
        assertNull(gate.blocker("EUR/USD", 0))
    }

    @Test fun `old concurrent comparison cannot clear a newer fault and channels recover independently`() {
        val gate = SourceIntegrity()
        val beforeRequest = gate.begin(symbol, history)
        gate.reject(symbol, history, "history disagreement")
        assertNull(gate.accept(symbol, history, beforeRequest, 3))
        gate.reject(symbol, quote, "quote disagreement")
        val afterHistory = gate.accept(symbol, history, gate.begin(symbol, history), 2)!!
        assertEquals("quote disagreement", gate.blocker(symbol, afterHistory))
        val recovered = gate.accept(symbol, quote, gate.begin(symbol, quote), 2)!!
        assertNull(gate.blocker(symbol, recovered))
        assertNotNull(gate.blocker(symbol, 0)) // old preview still rejected after recovery
    }

    @Test fun `every fault recovery invalidates previews without invalidating every normal comparison`() {
        val gate = SourceIntegrity()
        assertEquals(0L, gate.accept(symbol, history, gate.begin(symbol, history), 1))
        assertEquals(0L, gate.accept(symbol, history, gate.begin(symbol, history), 2))
        gate.reject(symbol, history, "conflict")
        val firstRecovery = gate.accept(symbol, history, gate.begin(symbol, history), 2)!!
        assertNotNull(gate.blocker(symbol, 0))
        assertNull(gate.blocker(symbol, firstRecovery))
        gate.reject(symbol, history, "new conflict")
        val secondRecovery = gate.accept(symbol, history, gate.begin(symbol, history), 2)!!
        assertTrue(secondRecovery > firstRecovery)
        assertNotNull(gate.blocker(symbol, firstRecovery))
    }

    @Test fun `another reader conflict invalidates preferred venue and only fresh comparison can recover`() = runBlocking {
        val gate = SourceIntegrity.shared
        val now = System.currentTimeMillis()
        var phase = 0
        fun bundle() = NonCryptoRadarBundle { _, frame, source, _ ->
            val price = if (phase == 1) 100.0 + source.ordinal * 10.0 else 100.0
            if (phase == 2 && source != SourceRouting.HistorySource.YAHOO) throw DataFeedException("offline")
            val last = now / frame.millis * frame.millis - frame.millis
            val bars = (0 until 320).map { i -> Candle(last - (319-i)*frame.millis, price, price+1, price-1, price, 10.0) }
            PublicCandleHistoryClient.Result(bars, when (source) {
                SourceRouting.HistorySource.YAHOO -> "Yahoo Finance"
                SourceRouting.HistorySource.DUKASCOPY -> "Dukascopy"
                SourceRouting.HistorySource.TWELVE_DATA -> "Twelve Data"
            })
        }
        val original = bundle()
        val initial = original.fetch("EUR/USD", "synthetic", now)
        assertEquals(0L, initial.integrityRevision)
        phase = 1
        assertTrue(runCatching { bundle().fetch("EUR/USD", "synthetic", now) }.isFailure)
        assertNotNull(gate.blocker("EUR/USD", 0))
        phase = 2 // a healthy cached primary alone must not clear a previously known conflict
        assertTrue(runCatching { original.fetch("EUR/USD", "synthetic", now) }.isFailure)
        phase = 0
        val recovered = original.fetch("EUR/USD", "synthetic", now)
        assertTrue(recovered.integrityRevision > initial.integrityRevision)
        assertNull(gate.blocker("EUR/USD", recovered.integrityRevision))
        assertNotNull(gate.blocker("EUR/USD", initial.integrityRevision))
    }

    @Test fun `automatic and opportunity entry gates reject fresh ticks with stale analysis revision`() {
        val gate = SourceIntegrity.shared
        val market = MarketState(symbol = symbol, lastPrice = 2000.0,
            feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = System.currentTimeMillis()))
        gate.reject(symbol, history, "independent source disagreement")
        val settings = AppSettings()
        val news = PersianNewsState()
        assertEquals("independent source disagreement", PaperAutoRules.blocker(market, settings, news))
        assertEquals("independent source disagreement", PaperAutoRules.opportunityBlocker(market, settings, news))
        gate.accept(symbol, history, gate.begin(symbol, history), 2)
        assertTrue(PaperAutoRules.blocker(market, settings, news)!!.contains("دوباره"))
    }
}
