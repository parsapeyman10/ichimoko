package com.aurum.edge.data

import com.aurum.edge.core.Interval
import com.aurum.edge.core.TradeQuotePolicy
import org.junit.Assert.*
import org.junit.Test

/** Ten independent assertions for venue, clock, stream, pagination and closed bars. */
class BinanceSpotContractTest {
    private val now = 1_800_000_000_000L
    private fun event(id: Long, symbol: String = "BTCUSDT", time: Long = now,
                      price: String = "100.50", qty: String = "2.0") =
        """{"e":"aggTrade","s":"$symbol","a":$id,"p":"$price","q":"$qty","T":$time,"E":$time}"""
    private fun row(id: Long, time: Long = now, price: String = "100.50") =
        """{"a":$id,"p":"$price","q":"2.0","T":$time}"""
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun `1 same venue trade has publisher timestamp and real price`() {
        val trade = BinanceSpotClient.parseAggregate(event(72), "BTC/USDT", now)
        assertEquals(72L, trade.id)
        assertEquals(now, trade.tick.at)
        assertEquals(100.50, trade.tick.price, 0.0)
        assertTrue(TradeQuotePolicy.accepts(trade.tick, now))
    }
    @Test fun `2 stream rejects wrong symbol`() { fails {
        BinanceSpotClient.parseAggregate(event(72, "ETHUSDT"), "BTC/USDT", now)
    } }
    @Test fun `3 stream rejects ticker snapshots masquerading as execution`() { fails {
        BinanceSpotClient.parseAggregate(event(72).replace("aggTrade", "24hrTicker"), "BTC/USDT", now)
    } }
    @Test fun `4 future trade is never accepted`() { fails {
        BinanceSpotClient.parseAggregate(event(72, time = now + 11_000L), "BTC/USDT", now)
    } }
    @Test fun `5 negative or nonfinite price and zero volume are invalid`() {
        listOf("-1", "NaN", "Infinity").forEach { price -> fails {
            BinanceSpotClient.parseAggregate(event(72, price = price), "BTC/USDT", now)
        } }
        fails { BinanceSpotClient.parseAggregate(event(72, qty = "0"), "BTC/USDT", now) }
    }
    @Test fun `6 older real trades are valid for replay but not a live display`() {
        val older = BinanceSpotClient.parsePage("[${row(72, now - 90_000L)}]", "BTC/USDT", now)
        assertEquals(72L, older.single().id)
        assertFalse(TradeQuotePolicy.accepts(older.single().tick, now))
    }
    @Test fun `7 page rejects missing ID even when prices match`() { fails {
        BinanceSpotClient.parsePage("[${row(72)},${row(74)}]", "BTC/USDT", now)
    } }
    @Test fun `8 page rejects out of order time and duplicate IDs`() {
        fails { BinanceSpotClient.parsePage("[${row(72, now)},${row(73, now - 1)}]", "BTC/USDT", now) }
        fails { BinanceSpotClient.parsePage("[${row(72)},${row(72)}]", "BTC/USDT", now) }
    }
    @Test fun `9 seven engine intervals map to native independent exchange windows`() {
        assertEquals(listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d"),
            listOf(Interval.M1, Interval.M5, Interval.M15, Interval.M30,
                Interval.H1, Interval.H4, Interval.D1).map(BinanceSpotClient::resolution))
        assertEquals("3m", BinanceSpotClient.resolution(Interval.M3))
    }
    @Test fun `10 candle parser discards forming candle and refuses gaps`() {
        fun bar(t: Long) = """[$t,"100","102","99","101","2.1"]"""
        val step = Interval.M5.millis
        assertEquals(2, BinanceSpotClient.parseCandles("[${bar(now-2*step)},${bar(now-step)},${bar(now)}]",
            Interval.M5, now).size)
        fails { BinanceSpotClient.parseCandles("[${bar(now-3*step)},${bar(now-step)}]", Interval.M5, now) }
    }
}
