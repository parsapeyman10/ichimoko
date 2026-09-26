package com.aurum.edge.engine

import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.Quote
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAndMetricsTest {
    private val now = 1_700_000_000_000L
    private val btc = WatchCatalog.find("BTC/USD")!!
    private fun quote(source: String, price: Double, at: Long? = now, cached: Boolean = false): Quote = Quote(
        code = "BTC", label = "بیت‌کوین", price = price, unit = "$", sourceId = source,
        ts = now, providerAt = at, stale = cached,
    )

    @Test fun twoIndependentFreshSourcesAgree() {
        val ids = btc.defaultSources
        val result = SourceComparison.verify(btc, ids, mapOf(
            ids[0] to quote(ids[0], 100.0), ids[1] to quote(ids[1], 100.3),
        ), now)
        assertEquals(VerificationStatus.CONFIRMED, result.status)
        assertEquals(2, result.freshSources)
    }

    @Test fun conflictCannotBeConfirmedByOldCacheOrMissingTimestamps() {
        val ids = btc.defaultSources
        assertEquals(VerificationStatus.CONFLICT, SourceComparison.verify(btc, ids, mapOf(
            ids[0] to quote(ids[0], 100.0), ids[1] to quote(ids[1], 107.0),
        ), now).status)
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(btc, ids, mapOf(
            ids[0] to quote(ids[0], 100.0), ids[1] to quote(ids[1], 100.1, cached = true),
        ), now).status)
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(btc, ids, mapOf(
            ids[0] to quote(ids[0], 100.0), ids[1] to quote(ids[1], 100.1, at = null),
        ), now).status)
        assertEquals(VerificationStatus.UNVERIFIED, SourceComparison.verify(btc, ids, mapOf(
            ids[0] to quote(ids[0], 100.0), ids[1] to quote(ids[1], 100.1, at = now - btc.maxAgeMillis - 1),
        ), now).status)
        assertEquals(VerificationStatus.NO_DATA, SourceComparison.verify(btc, ids, emptyMap(), now).status)
        assertTrue(!SourceComparison.isFresh(btc, quote(ids[0], Double.POSITIVE_INFINITY), now))
    }

    @Test fun eighteenMetricsComeOnlyFromSettledOutcomes() {
        val trades = listOf(
            ClosedOutcome(SignalAction.BUY, 0, 60_000, 20.0, 2.0),
            ClosedOutcome(SignalAction.SELL, 60_000, 180_000, 10.0, 1.0),
            ClosedOutcome(SignalAction.SELL, 180_000, 240_000, -15.0, -1.0),
            ClosedOutcome(SignalAction.BUY, 240_000, 300_000, -5.0, -0.5),
        )
        val report = PerformanceMetrics.from(trades, 100.0)
        assertEquals(4, report.total)
        assertEquals(2, report.wins)
        assertEquals(2, report.losses)
        assertEquals(2, report.longCount)
        assertEquals(2, report.shortCount)
        assertEquals(50.0, report.winRatePct!!, 0.0001)
        assertEquals(10.0, report.netPnlUsd, 0.0001)
        assertEquals(15.0, report.averageWinUsd!!, 0.0001)
        assertEquals(-10.0, report.averageLossUsd!!, 0.0001)
        assertEquals(1.5, report.profitFactor!!, 0.0001)
        assertEquals(75_000L, report.averageDurationMillis)
        assertEquals(2, report.longestWinningStreak)
        assertEquals(2, report.longestLosingStreak)
        assertEquals(1.5 / 4, report.expectancyR!!, 0.0001)
        assertTrue(report.tradeSharpe != null && report.tradeSharpe!!.isFinite())
        assertEquals(20.0 / 130.0 * 100.0, report.maxDrawdownPct!!, 0.0001)
    }

    @Test fun ratiosAreUndefinedWithoutTradesOrLosses() {
        val empty = PerformanceMetrics.from(emptyList(), 100.0)
        assertNull(empty.tradeSharpe)
        assertNull(empty.winRatePct)
        assertNull(empty.averageDurationMillis)
        assertNull(empty.maxDrawdownPct)
        val allWins = PerformanceMetrics.from(listOf(
            ClosedOutcome(SignalAction.BUY, 0, 60_000, 5.0, 1.0),
        ), 100.0)
        assertNull(allWins.profitFactor)
        assertNull(allWins.averageLossUsd)
        assertNull(allWins.tradeSharpe)
    }
}
