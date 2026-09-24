package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.TwelveDataClient
import com.aurum.edge.data.hasCurrentRestBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** REST fixtures only; malformed provider data must not reach the chart, cache or signal engine. */
class TwelveDataCandleContractTest {
    private val now = Instant.parse("2026-09-24T15:16:00Z").toEpochMilli()
    private val client = TwelveDataClient()
    private val older = """{"datetime":"2026-09-24 15:10:00","open":"3100","high":"3105","low":"3098","close":"3103","volume":"1"}"""
    private val latest = """{"datetime":"2026-09-24 15:15:00","open":"3103","high":"3106","low":"3101","close":"3104"}"""
    private fun json(meta: String = """"symbol":"XAU/USD","interval":"5min","currency":"USD","timezone":"UTC"""",
                     rows: String = "$latest,$older") = """{"meta":{$meta},"values":[$rows]}"""
    private fun parse(body: String) = client.parseTimeSeries(body, "XAU/USD", Interval.M5, now)

    @Test fun `verified identity OHLC UTC and unique times are sorted without inventing volume`() {
        val bars = parse(json())
        assertEquals(2, bars.size)
        assertEquals(Instant.parse("2026-09-24T15:10:00Z").toEpochMilli(), bars[0].time)
        assertEquals(0.0, bars.last().volume, 0.0) // missing OTC volume stays zero, not invented
        assertTrue(hasCurrentRestBar(bars, Interval.M5, now))
        assertFalse(hasCurrentRestBar(bars, Interval.M5, now + 10 * 60_000L))
        assertFalse(hasCurrentRestBar(bars, Interval.M5, bars.last().time - 1))
        assertEquals(bars.last().time, TwelveDataClient.parseTime("2026-09-24T18:45:00+03:30"))
        assertEquals(0L, TwelveDataClient.parseTime("2026-09-24")) // date-only quote is not intraday evidence
        assertEquals(0L, TwelveDataClient.parseTime("2026-09-24 15:15:00garbage"))
    }

    @Test fun `wrong symbol timeframe currency or timezone fails closed`() {
        for (bad in listOf(
            json(meta = """"symbol":"BTC/USD","interval":"5min""""),
            json(meta = """"symbol":"XAU/USD","interval":"1min""""),
            json(meta = """"symbol":"XAU/USD","interval":"5min","currency":"EUR""""),
            json(meta = """"symbol":"XAU/USD","interval":"5min","timezone":"Asia/Tehran""""),
            """{"values":[$latest]}""",
        )) assertThrows(DataFeedException::class.java) { parse(bad) }
    }

    @Test fun `one corrupt row cannot be silently dropped beside a good bar`() {
        for (bad in listOf(
            json(rows = "$older,${latest.replace("\"high\":\"3106\"", "\"high\":\"3100\"")}"),
            json(rows = "$older,${latest.replace("\"close\":\"3104\"", "\"close\":\"NaN\"")}"),
            json(rows = "$older,${latest.replace("2026-09-24 15:15:00", "2026-09-24 15:20:00")}"),
            json(rows = "$older,${latest.replace("2026-09-24 15:15:00", "2026-09-24")}"),
            json(rows = "$older,${latest.replace("2026-09-24 15:15:00", "2026-09-24 15:17:00")}"),
            json(rows = "$older,$older"),
            json(rows = "$older,${latest.replace("\"open\":\"3103\"", "\"open\":\"0\"")}"),
            json(rows = "$older,${latest.dropLast(1)},\"volume\":\"-1\"}"),
            """{"code":429,"message":"quota"}""",
        )) assertThrows(DataFeedException::class.java) { parse(bad) }
    }

    @Test fun `stale history is not a current REST market price even when download succeeded`() {
        val yesterday = Candle(now - 86_400_000L, 3100.0, 3101.0, 3099.0, 3100.0)
        assertFalse(hasCurrentRestBar(listOf(yesterday), Interval.M5, now))
    }
}
