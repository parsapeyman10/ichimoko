package com.aurum.edge.engine

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.data.ScanRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanRankingTest {
    // Tuesday, regular US share hours and active FX; independent of when CI happens to run.
    private val now = Instant.parse("2026-10-06T15:00:00Z").toEpochMilli()
    private fun row(symbol: String, state: String = "candidate", scannedAt: Long? = now - 30_000L,
                    confidence: Double = 85.0) = PairScanStatus(symbol, state, "real closed bars",
        lastScanAt = scannedAt, price = 100.0, confidence = confidence, riskReward = 1.5,
        action = SignalAction.BUY)

    @Test fun `every category remains visible and only fresh valid observations are ranked`() {
        val rows = listOf(row("AAPL", "observed", confidence = 95.0),
            row("NVDA", confidence = 75.0), row("EUR/USD"), row("XAU/USD"),
            row("BTC/USDT"), row("ETH/USDT", "error"),
            row("SOL/USDT", scannedAt = now - 1_000_000L))
        val ranked = ScanRanking.byMarket(rows, Interval.M5, now)
        assertEquals(AssetClass.entries.toSet(), ranked.keys)
        assertEquals(listOf("NVDA", "AAPL"), ranked.getValue(AssetClass.STOCK).map { it.symbol })
        assertEquals(listOf("EUR/USD"), ranked.getValue(AssetClass.FOREX).map { it.symbol })
        assertEquals(listOf("XAU/USD"), ranked.getValue(AssetClass.COMMODITY).map { it.symbol })
        assertEquals(listOf("BTC/USDT"), ranked.getValue(AssetClass.CRYPTO).map { it.symbol })
        assertTrue(ScanRanking.rank(rows, Interval.M5, now).none { it.state == "observed" })
        assertTrue(ScanRanking.byMarket(rows, Interval.M5, now + 2_000_000L).values.all { it.isEmpty() })
    }

    @Test fun `no fabricated fourth choice if fewer than four pass`() {
        val rows = listOf(row("AAPL"), row("NVDA"))
        assertEquals(2, ScanRanking.byMarket(rows, Interval.M5, now).getValue(AssetClass.STOCK).size)
        assertTrue(ScanRanking.byMarket(emptyList(), Interval.M5, now).values.all { it.isEmpty() })
    }
}
