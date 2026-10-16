package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarketEntryReasonTest {
    private val weekday = Instant.parse("2026-10-13T15:00:00Z").toEpochMilli()

    @Test fun `no position does not imply closed market or missing signal`() {
        val unknown = MarketEntryReason.forClass(AssetClass.CRYPTO, PairScanState(), true, weekday)
        assertTrue(unknown.contains("انتظار"))
        assertTrue(MarketEntryReason.forClass(AssetClass.CRYPTO, PairScanState(), false, weekday)
            .contains("خاموش"))
        val noSignal = PairScanState(statuses = listOf(PairScanStatus("BTC/USDT", "no_signal",
            "شروط کامل نیست", lastScanAt = weekday)))
        assertTrue(MarketEntryReason.forClass(AssetClass.CRYPTO, noSignal, true, weekday)
            .contains("کراس"))
    }

    @Test fun `read only equities are not labelled as closed during trading hours`() {
        val equities = PairScanState(statuses = listOf(PairScanStatus("AAPL", "observed",
            "فقط مشاهده", lastScanAt = weekday)))
        assertTrue(MarketEntryReason.forClass(AssetClass.STOCK, equities, true, weekday)
            .contains("تیک هم‌نماد"))
    }
}
