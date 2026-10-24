package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.domain.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.Signal
import com.aurum.edge.core.MarketState
import com.aurum.edge.domain.SignalAction
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract fixtures only: technical gates of the four-layer paper policy. */
class NineWayAutoPaperTest {
    private val now = 1_800_000_000_000L
    private val barTime = now - Interval.M5.millis
    private val four = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD", "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی").map { ConfluenceItem(it, true, "دادهٔ آزمون", scorePercent = 25) }
    // A pair that still belongs to the four-layer model: EUR/USD now belongs to EUR-ARES and
    // refuses a generic four-layer signal, which is covered by its own test below.
    private val settings = AppSettings(symbol = "GBP/USD", interval = Interval.M5,
        backgroundMonitor = true, autoPaperTrading = true)
    private val raw = Signal(SignalAction.BUY, 100.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = barTime, confluence = four)

    private fun market(s: Signal, symbol: String = "GBP/USD") = MarketState(symbol = symbol, interval = Interval.M5,
        candles = IctTestBars.readyAt(barTime),
        lastPrice = 3000.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = s)

    @Test fun automaticReviewRequiresFiveMinuteMarketAndSignal() {
        for (interval in listOf(Interval.M3, Interval.M15, Interval.H1)) {
            assertTrue(PaperAutoRules.blocker(market(raw).copy(interval = interval),
                settings, now).orEmpty().contains("۵ دقیقه"))
            assertTrue(PaperAutoRules.opportunityBlocker(market(raw.copy(interval = interval)),
                settings, now).orEmpty().contains("۵ دقیقه"))
        }
        assertNull(PaperAutoRules.blocker(market(raw), settings, now))
    }

    @Test fun euroDollarIsNoLongerAFourLayerPair() {
        val euro = raw.copy()
        val blocked = PaperAutoRules.blocker(market(euro, "EUR/USD"),
            settings.copy(symbol = "EUR/USD"), now).orEmpty()
        // The refusal names the owning engine instead of silently reusing the four-layer policy.
        assertTrue(blocked, blocked.contains("EUR-ARES"))
        // The same signal on a pair that still uses the four-layer model is not blocked.
        assertNull(PaperAutoRules.blocker(market(raw, "GBP/USD"), settings, now))
    }

    @Test fun everyTechnicalComponentMustPass() {
        assertEquals(SignalAction.BUY, raw.action)
        assertEquals(4, raw.confluence.size)
        assertNull(PaperAutoRules.blocker(market(raw), settings, now))
        for (failed in four.indices) {
            val input = raw.copy(confluence = four.mapIndexed { i, item -> if (i == failed) item.copy(ok = false, status = ConfluenceStatus.CONFLICT, scorePercent = 0) else item })
            assertNotNull("technical $failed", PaperAutoRules.blocker(market(input), settings, now))
        }
    }

    @Test fun liveQuoteSettingsSymbolAndClosedBarFreshnessMustAlsoMatch() {
        val confirmed = raw
        val ready = market(confirmed)
        listOf(
            ready.copy(feed = FeedStatus(FeedMode.OFFLINE, lastSuccessAt = now)),
            ready.copy(showingCachedData = true),
            ready.copy(feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now - 90_001)),
            ready.copy(lastPrice = 4000.0),
            ready.copy(candles = listOf(Candle(barTime - Interval.M5.millis, 3000.0, 3002.0, 2999.0, 3000.0))),
            ready.copy(signal = confirmed.copy(barTime = now)),
        ).forEachIndexed { idx, input ->
            assertNotNull("market $idx", PaperAutoRules.blocker(input, settings, now))
        }
        val restOnly = ready.copy(feed = FeedStatus(FeedMode.POLLING, lastSuccessAt = now))
        assertNull("a verified polling feed can trigger automatic paper entry",
            PaperAutoRules.blocker(restOnly, settings, now))
        assertNull("a verified REST candle may still trigger an educational candidate alert",
            PaperAutoRules.opportunityBlocker(restOnly, settings, now))
        assertNotNull(PaperAutoRules.blocker(ready.copy(signal = confirmed.copy(
            confluence = confirmed.confluence.mapIndexed { index, item ->
                if (index == 0) item.copy(status = ConfluenceStatus.UNKNOWN) else item
            })), settings, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings.copy(autoPaperTrading = false), now))
        assertNull(PaperAutoRules.blocker(ready, settings.copy(backgroundMonitor = false), now))
        assertNotNull(PaperAutoRules.opportunityBlocker(ready, settings.copy(backgroundMonitor = false), now))
        assertNotNull(PaperAutoRules.blocker(ready.copy(symbol = "UNKNOWN_XYZ"), settings, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings, now + 700_000L))
        // A late WebSocket tick near Friday's close cannot authorize a weekend entry/alert.
        val closedAt = Instant.parse("2027-01-15T22:00:00Z").toEpochMilli()
        assertTrue(PaperAutoRules.opportunityBlocker(ready.copy(feed = FeedStatus(FeedMode.LIVE,
            lastSuccessAt = closedAt)), settings, closedAt)!!.contains("بسته"))
        assertNotNull(PaperAutoRules.blocker(ready.copy(feed = FeedStatus(FeedMode.MARKET_CLOSED)),
            settings, closedAt))
    }
}
