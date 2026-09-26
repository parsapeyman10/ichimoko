package com.aurum.edge.engine

import com.aurum.edge.core.AlertCheckKind
import com.aurum.edge.core.AlertDiagnostics
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDiagnosticsTest {
    private val now = 1_800_000_000_000L

    @Test fun `no key no server no foreground monitor reports independent reasons`() {
        val checks = AlertDiagnostics.checks(MarketState(), AppSettings(), PersianNewsState(),
            monitorRunning = false, androidNotificationsReady = false, trades = emptyList(), mtf = null, now = now)
        for (kind in listOf(AlertCheckKind.KEY, AlertCheckKind.MARKET, AlertCheckKind.HISTORY,
            AlertCheckKind.MONITOR, AlertCheckKind.ANDROID_ALERT, AlertCheckKind.AI_NEWS, AlertCheckKind.NINE_WAY)) {
            assertFalse(checks.single { it.kind == kind }.ready)
        }
        assertTrue(checks.single { it.kind == AlertCheckKind.AI_NEWS }.detail.contains("RSS"))
        assertTrue(checks.single { it.kind == AlertCheckKind.APP_ALERT }.ready) // opt-in, but not deliverable yet
    }

    @Test fun `recent REST bars allow educational prerequisites but no AI signal or automatic entry`() {
        val bars = (0 until 210).map { i ->
            Candle(now - (210L - i) * Interval.M5.millis, 3100.0, 3101.0, 3099.0, 3100.0, closed = true)
        }
        val market = MarketState(candles = bars, feed = FeedStatus(FeedMode.POLLING, lastSuccessAt = now - 4_000L))
        val settings = AppSettings(apiKey = "synthetic-only", backgroundMonitor = true,
            notifyOnSignal = true, newsBaseUrl = "https://news.example.org")
        fun report(state: MarketState, running: Boolean = true, error: String? = null) =
            AlertDiagnostics.checks(state, settings, PersianNewsState(gate = NewsGate.UNKNOWN),
                monitorRunning = running, androidNotificationsReady = true, trades = emptyList(),
                mtf = null, opportunityError = error, now = now)
        val checks = report(market)
        assertTrue(checks.single { it.kind == AlertCheckKind.MARKET }.ready)
        assertTrue(checks.single { it.kind == AlertCheckKind.HISTORY }.ready)
        assertTrue(checks.single { it.kind == AlertCheckKind.MONITOR }.ready)
        assertFalse(checks.single { it.kind == AlertCheckKind.AI_NEWS }.ready)
        assertFalse(checks.single { it.kind == AlertCheckKind.NINE_WAY }.ready)
        assertFalse(report(market, running = false).single { it.kind == AlertCheckKind.MONITOR }.ready)
        assertFalse(report(market.copy(showingCachedData = true)).single { it.kind == AlertCheckKind.MARKET }.ready)
        assertFalse(report(market, error = "local file unreadable").single { it.kind == AlertCheckKind.STORAGE }.ready)
    }
}
