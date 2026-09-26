package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.AiNewsVerdict
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsSourceStatus
import com.aurum.edge.data.PersianHeadline
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.parseWebNews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract fixtures only: no claim that a mocked LLM predicted real gold or a broker order. */
class NineWayAutoPaperTest {
    private val now = 1_800_000_000_000L
    private val barTime = now - Interval.M5.millis
    private val eight = (1..8).map { ConfluenceItem("فنی $it", true, "دادهٔ آزمون") }
    private val settings = AppSettings(symbol = "XAU/USD", interval = Interval.M5,
        backgroundMonitor = true, autoPaperTrading = true, workspaceId = "forex")
    private val headline = PersianHeadline("id1", "Gold reacts to dollar weakness", "headline fixture",
        "Publisher", "https://publisher.example/news", now - 60_000L,
        "MEDIUM", "BUY", "rule-label", "en")
    private val news = PersianNewsState(
        articles = listOf(headline), sources = listOf(NewsSourceStatus("Publisher", "online", "https://publisher.example/rss"),
            NewsSourceStatus("Forex Factory", "online", "https://nfs.faireconomy.media/ff_calendar_thisweek.json")),
        gate = NewsGate.CLEAR, lastCheckedAt = now, calendarCheckedAt = now,
        ai = AiNewsVerdict("AVAILABLE", "XAU/USD", "BUY", 91.0, "test-model",
            "fixture classification", now, listOf("id1")),
    )
    private val raw = Signal(SignalAction.BUY, 86.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = barTime, confluence = eight)

    private fun market(s: Signal, symbol: String = "XAU/USD") = MarketState(symbol = symbol, interval = Interval.M5,
        candles = IctTestBars.readyAt(barTime),
        lastPrice = 3000.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = s)

    @Test fun backendWebNewsJsonToNineWayAndAutoPolicyIsOneContract() {
        val published = Instant.ofEpochMilli(now - 60_000L)
        val received = Instant.ofEpochMilli(now)
        val payload = """{
            "status":{"configured":true,"state":"online","cached":false,
                "sources":[{"name":"Publisher","state":"online","feed":"https://publisher.example/rss"},
                    {"name":"Forex Factory","state":"online","feed":"https://nfs.faireconomy.media/ff_calendar_thisweek.json"}]},
            "calendar":{"status":"online","source":"https://nfs.faireconomy.media/ff_calendar_thisweek.json",
                "checked_at":"$received","events":[{"country":"USD"}],"guard":{"state":"CLEAR"}},
            "articles":[{"id":"id1","source":"Publisher","headline":"Gold reacts to dollar weakness",
                "published_at":"$published","url":"https://publisher.example/news","analysis":{"source":"rules"}}],
            "guard":{"state":"CLEAR","reason":"fixture"},
            "ai_confluence":{"status":"AVAILABLE","symbol":"XAU/USD","direction":"BUY",
                "confidence":91,"model":"test-model","reason":"gold macro context",
                "checked_at":"$received","evidence_ids":["id1"]},
            "checked_at":"$received"
        }"""
        val parsed = parseWebNews(Json.parseToJsonElement(payload) as JsonObject, now)
        assertEquals(NewsGate.CLEAR, parsed.gate)
        assertEquals("rules", parsed.articles.single().analysisSource) // rule label isn't AI
        val combined = NewsConfluence.apply(raw, "XAU/USD", parsed, now)!!
        assertEquals(SignalAction.BUY, combined.action)
        assertNull(PaperAutoRules.blocker(market(combined), settings, parsed, now))
        assertEquals("id1", NewsConfluence.record(parsed)!!.evidence.single().id)
        val missingAi = parseWebNews(Json.parseToJsonElement(payload.replace("ai_confluence", "not_ai")) as JsonObject, now)
        assertEquals(SignalAction.NO_TRADE, NewsConfluence.apply(raw, "XAU/USD", missingAi, now)!!.action)
        val withoutCalendar = parseWebNews(Json.parseToJsonElement(payload.replace("\"calendar\":", "\"ignoredCalendar\":")) as JsonObject, now)
        assertEquals(NewsGate.UNKNOWN, withoutCalendar.gate)
        assertEquals(SignalAction.NO_TRADE, NewsConfluence.apply(raw, "XAU/USD", withoutCalendar, now)!!.action)
        val blockedCalendar = parseWebNews(Json.parseToJsonElement(payload.replace(
            "\"events\":[{\"country\":\"USD\"}],\"guard\":{\"state\":\"CLEAR\"}",
            "\"events\":[{\"country\":\"USD\"}],\"guard\":{\"state\":\"BLOCKED\"}")) as JsonObject, now)
        assertEquals(NewsGate.BLOCKED, blockedCalendar.gate)
        assertEquals(SignalAction.NO_TRADE, NewsConfluence.apply(raw, "XAU/USD", blockedCalendar, now)!!.action)
        val agedCalendar = parseWebNews(Json.parseToJsonElement(payload) as JsonObject, now + 1_200_001L)
        assertEquals(NewsGate.UNKNOWN, agedCalendar.gate)
    }

    @Test fun everyTechnicalComponentAndNinthNewsMustPassTogether() {
        val result = NewsConfluence.apply(raw, "XAU/USD", news, now)!!
        assertEquals(SignalAction.BUY, result.action)
        assertEquals(9, result.confluence.size)
        assertEquals(NewsConfluence.NEWS_LABEL, result.confluence[8].name)
        assertEquals(ConfluenceStatus.CONFIRMED, result.confluence[8].status)
        assertNotNull(NewsConfluence.record(news))
        assertNull(PaperAutoRules.blocker(market(result), settings, news, now))
        for (failed in 0 until NewsConfluence.TECHNICAL_COUNT) {
            val input = raw.copy(confluence = eight.mapIndexed { i, item -> if (i == failed) item.copy(ok = false) else item })
            val combined = NewsConfluence.apply(input, "XAU/USD", news, now)!!
            assertEquals("technical $failed", SignalAction.NO_TRADE, combined.action)
            assertNull(combined.entry)
            assertEquals(9, combined.confluence.size)
            assertNotNull(PaperAutoRules.blocker(market(combined), settings, news, now))
        }
    }

    @Test fun eachNewsFailureBlocksEntryButKeepsTechnicalScoreInformational() {
        val cases = listOf(
            news.copy(ai = AiNewsVerdict()),
            news.copy(ai = news.ai.copy(status = "UNKNOWN")),
            news.copy(ai = news.ai.copy(direction = "SELL")),
            news.copy(ai = news.ai.copy(confidence = 79.0)),
            news.copy(ai = news.ai.copy(model = "deterministic-fallback")),
            news.copy(ai = news.ai.copy(evidenceIds = listOf("made-up"))),
            news.copy(ai = news.ai.copy(evidenceIds = listOf("id1", "id1"))),
            news.copy(gate = NewsGate.BLOCKED),
            news.copy(sources = listOf(NewsSourceStatus("Publisher", "unavailable", "https://publisher.example/rss"))),
            news.copy(loading = true),
            news.copy(lastCheckedAt = now - 180_001),
            news.copy(ai = news.ai.copy(checkedAt = now - 180_001)),
            news.copy(articles = listOf(headline.copy(publishedAt = now - 181 * 60_000L))),
            news.copy(articles = listOf(headline.copy(link = null))),
            news.copy(articles = listOf(headline.copy(link = "https://publisher.example.attacker.net/news"))),
        )
        cases.forEachIndexed { idx, input ->
            val combined = NewsConfluence.apply(raw, "XAU/USD", input, now)!!
            assertEquals("news $idx", SignalAction.NO_TRADE, combined.action)
            assertFalse("news $idx", combined.confluence[8].ok)
            assertEquals(raw.confidence, combined.confidence, 0.001)
            assertNotNull(PaperAutoRules.blocker(market(combined), settings, input, now))
        }
        val conflict = NewsConfluence.apply(raw, "XAU/USD", news.copy(ai = news.ai.copy(direction = "SELL")), now)!!
        assertEquals(ConfluenceStatus.CONFLICT, conflict.confluence[8].status)
        assertEquals(ConfluenceStatus.UNKNOWN,
            NewsConfluence.apply(raw, "XAU/USD", news.copy(ai = AiNewsVerdict()), now)!!.confluence[8].status)
    }

    @Test fun liveQuoteSettingsSymbolAndClosedBarFreshnessMustAlsoMatch() {
        val confirmed = NewsConfluence.apply(raw, "XAU/USD", news, now)!!
        val ready = market(confirmed)
        listOf(
            ready.copy(feed = FeedStatus(FeedMode.OFFLINE, lastSuccessAt = now)),
            ready.copy(showingCachedData = true),
            ready.copy(feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now - 90_001)),
            ready.copy(lastPrice = 4000.0),
            ready.copy(candles = listOf(Candle(barTime - Interval.M5.millis, 3000.0, 3002.0, 2999.0, 3000.0))),
            ready.copy(signal = confirmed.copy(barTime = now)),
        ).forEachIndexed { idx, input ->
            assertNotNull("market $idx", PaperAutoRules.blocker(input, settings, news, now))
        }
        val restOnly = ready.copy(feed = FeedStatus(FeedMode.POLLING, lastSuccessAt = now))
        assertNotNull("REST has no individually timestamped live tick for automatic paper entry",
            PaperAutoRules.blocker(restOnly, settings, news, now))
        assertNull("a verified REST candle may still trigger an educational candidate alert",
            PaperAutoRules.opportunityBlocker(restOnly, settings, news, now))
        assertNotNull(PaperAutoRules.blocker(ready.copy(signal = confirmed.copy(
            confluence = confirmed.confluence.mapIndexed { index, item ->
                if (index == 0) item.copy(status = ConfluenceStatus.UNKNOWN) else item
            })), settings, news, now))
        assertNotNull(PaperAutoRules.blocker(ready.copy(signal = confirmed.copy(
            confluence = confirmed.confluence.mapIndexed { index, item ->
                if (index == 8) item.copy(status = ConfluenceStatus.CONFLICT) else item
            })), settings, news, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings,
            news.copy(ai = news.ai.copy(model = "another-verified-model")), now))
        assertNotNull(PaperAutoRules.blocker(ready, settings.copy(autoPaperTrading = false), news, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings.copy(backgroundMonitor = false), news, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings.copy(workspaceId = "nobitex"), news, now))
        assertNotNull(PaperAutoRules.opportunityBlocker(ready, settings.copy(workspaceId = "iran_stocks"), news, now))
        assertNotNull(PaperAutoRules.blocker(ready.copy(symbol = "AAPL"), settings, news, now))
        assertNotNull(PaperAutoRules.blocker(ready, settings, news, now + 90_001))
        // A late WebSocket tick near Friday's close cannot authorize a weekend entry/alert.
        val closedAt = Instant.parse("2027-01-15T22:00:00Z").toEpochMilli()
        assertTrue(PaperAutoRules.opportunityBlocker(ready.copy(feed = FeedStatus(FeedMode.LIVE,
            lastSuccessAt = closedAt)), settings, news, closedAt)!!.contains("بسته"))
        assertNotNull(PaperAutoRules.blocker(ready.copy(feed = FeedStatus(FeedMode.MARKET_CLOSED)),
            settings, news, closedAt))
        assertEquals(ConfluenceStatus.UNKNOWN, NewsConfluence.alignment("AAPL", SignalAction.BUY, news, now).status)
    }
}
