package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.ForexNewsDecisions
import com.aurum.edge.core.MarketHours
import com.aurum.edge.data.AiNewsVerdict
import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.ForexEvent
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ForexNewsDecisionTest {
    private val open = Instant.parse("2026-09-28T14:00:00Z").toEpochMilli()
    private val market = MarketState()
    private val config = AppSettings(workspaceId = "forex", autoPaperTrading = true, backgroundMonitor = true)
    private val event = ForexEvent("Core CPI", "USD", "High", open + 3_600_000L,
        forecast = "3.2%", previous = "3.0%")

    @Test fun `a Forex Factory forecast is context not a model opinion or a paper entry`() {
        val calendar = ForexCalendarState(listOf(event), open)
        val summary = ForexNewsDecisions.assess(market, config, calendar, PersianNewsState(), open)
        assertTrue(summary.context.contains("Core CPI"))
        assertTrue(summary.direction.contains("نامعلوم"))
        assertTrue(summary.paperEntry.contains("خیر"))
        assertFalse(summary.canEnterPaper)
        val fakeModel = PersianNewsState(ai = AiNewsVerdict(status = "AVAILABLE", symbol = "XAU/USD",
            direction = "BUY", confidence = 98.0, model = "unverified", checkedAt = open))
        val untrusted = ForexNewsDecisions.assess(market, config, calendar, fakeModel, open)
        assertFalse(untrusted.canEnterPaper)
        assertTrue(untrusted.modelOpinion.contains("معتبر نداریم"))
    }

    @Test fun `weekend and missing calendar stay closed even if a model string is present`() {
        val closed = Instant.parse("2026-09-26T14:00:00Z").toEpochMilli()
        assertTrue(MarketHours.forexWeekendClosed(closed))
        val summary = ForexNewsDecisions.assess(market, config,
            ForexCalendarState(), PersianNewsState(ai = AiNewsVerdict(status = "AVAILABLE",
                direction = "SELL", model = "claimed")), closed)
        assertFalse(summary.canEnterPaper)
        assertTrue(summary.paperEntry.contains("بسته"))
        assertTrue(summary.context.contains("تازه نیست"))
    }
}
