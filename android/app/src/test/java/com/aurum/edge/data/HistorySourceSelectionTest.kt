package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import org.junit.Assert.*
import org.junit.Test

class HistorySourceSelectionTest {
    private val now = 1_800_000_010_000L
    private fun candidate(name: String, price: Double, older: Boolean = false) =
        HistorySourceSelection.Candidate(name, name, listOf(Interval.M5, Interval.M15, Interval.H1).associateWith { frame ->
            val count = CandleSourceHealth.requiredSize(frame)
            val last = now / frame.millis * frame.millis - frame.millis - if (older) frame.millis else 0L
            (0 until count).map { i ->
                Candle(last - (count - 1 - i) * frame.millis, price, price + 1, price - 1, price, 10.0)
            }
        })

    @Test fun `unique majority excludes an outlier without averaging or stitching data`() {
        val a = candidate("A", 100.0); val b = candidate("B", 100.1); val bad = candidate("C", 150.0)
        val choice = HistorySourceSelection.choose("BTC/USDT", listOf(a, b, bad), now)
        assertTrue(choice.selected.value in setOf("A", "B"))
        assertTrue(choice.note.contains("پرت"))
        assertTrue(choice.selected.frames == a.frames || choice.selected.frames == b.frames)
    }
    @Test fun `two conflicting sources and ambiguous agreement chains cannot claim truth`() {
        assertThrows(DataFeedException::class.java) {
            HistorySourceSelection.choose("XAU/USD", listOf(candidate("A",100.0),candidate("B",105.0)),now)
        }
        assertThrows(DataFeedException::class.java) {
            HistorySourceSelection.choose("XAU/USD", listOf(candidate("A",100.0),candidate("B",100.4),candidate("C",100.8)),now)
        }
    }
    @Test fun `same publisher mirrors are not independent confirmation and freshest real packet wins`() {
        val a = candidate("A", 100.0)
        assertTrue(HistorySourceSelection.choose("EUR/USD", listOf(a,a),now).note.contains("فقط یک"))
        val older = candidate("older",100.0,true)
        assertEquals("A", HistorySourceSelection.choose("EUR/USD",listOf(older,a),now).selected.value)
    }
    @Test fun `partial stale and corrupt histories cannot confirm a source`() {
        val good = candidate("good",100.0)
        val bad = candidate("bad",100.0).let { it.copy(frames=it.frames - Interval.H1) }
        assertEquals("good", HistorySourceSelection.choose("XAU/USD",listOf(bad,good),now).selected.value)
        assertThrows(DataFeedException::class.java) { HistorySourceSelection.choose("XAU/USD",listOf(bad),now) }
        assertThrows(DataFeedException::class.java) { HistorySourceSelection.choose("XAU/USD",listOf(good),now+7_200_000) }
    }
    @Test fun `gold retains source timestamp and BBO and rejects simultaneous disagreement`() {
        val swiss = PriceTick(2000.0, now,1999.0,2001.0,provider="Swissquote")
        val gold = PriceTick(2001.0,now,provider="Gold-API")
        val selected = SpotPriceSelection.gold(listOf(swiss,gold),now)
        assertEquals("Swissquote",selected.provider)
        assertEquals(swiss.price,selected.price,0.0); assertEquals(swiss.at,selected.at)
        assertEquals(swiss.bid,selected.bid)
        assertThrows(DataFeedException::class.java) { SpotPriceSelection.gold(listOf(swiss,gold.copy(price=2100.0)),now) }
        assertThrows(DataFeedException::class.java) { SpotPriceSelection.gold(listOf(swiss.copy(at=now-60_000)),now) }
        assertTrue(SpotPriceSelection.gold(listOf(swiss),now).sourceNote!!.contains("فقط یک"))
        assertTrue(SpotPriceSelection.gold(listOf(swiss,gold.copy(at=now-20_000)),now).sourceNote!!.contains("اختلاف زمان"))
    }
}
