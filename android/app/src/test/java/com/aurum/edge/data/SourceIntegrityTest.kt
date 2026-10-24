package com.aurum.edge.data

import com.aurum.edge.core.*
import com.aurum.edge.core.MarketState
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
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

    @Test fun `publisher mismatch on the dedicated source fails over to the next free source`() = runBlocking {
        SourcePromotion.resetForTests()
        val now = System.currentTimeMillis()
        val requestedSources = mutableListOf<SourceRouting.HistorySource>()
        val bundle = NonCryptoRadarBundle { _, frame, source, _ ->
            requestedSources += source
            val count = HistoryPolicy.fetchSize(frame)
            val last = now / frame.millis * frame.millis - frame.millis
            val bars = (0 until count).map { i ->
                val price = 100.0
                Candle(last - (count - 1 - i) * frame.millis, price, price + 1.0, price - 1.0, price, 10.0)
            }
            // Whatever is asked, this fake answers as Dukascopy: the Yahoo attempt
            // must fail the publisher check, then the Dukascopy candidate wins.
            PublicCandleHistoryClient.Result(bars, "Dukascopy")
        }

        val snapshot = bundle.fetch("EUR/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY, snapshot.source)
        assertEquals(1, snapshot.priorFailures.size)
        assertEquals(SourceRouting.HistorySource.YAHOO_EURUSD, requestedSources.first())
        assertTrue(requestedSources.drop(1).all { it == SourceRouting.HistorySource.DUKASCOPY })
        assertEquals("DUKASCOPY", SourcePromotion.primaryId("EUR/USD", SourcePromotion.Kind.HISTORY))
    }

    @Test fun `automatic and opportunity entry gates reject fresh ticks with stale analysis revision`() {
        val gate = SourceIntegrity.shared
        val market = MarketState(symbol = symbol, lastPrice = 2000.0,
            feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = System.currentTimeMillis()))
        gate.reject(symbol, history, "independent source disagreement")
        val settings = AppSettings()
        assertEquals("independent source disagreement", PaperAutoRules.blocker(market, settings))
        assertEquals("independent source disagreement", PaperAutoRules.opportunityBlocker(market, settings))
        gate.accept(symbol, history, gate.begin(symbol, history), 2)
        assertTrue(PaperAutoRules.blocker(market, settings)!!.contains("دوباره"))
    }
}
