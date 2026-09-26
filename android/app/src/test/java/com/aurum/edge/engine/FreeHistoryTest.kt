package com.aurum.edge.engine

import com.aurum.edge.data.FreeHistoryCatalog
import com.aurum.edge.data.FreeHistoryDownloader
import com.aurum.edge.data.FreeHistoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fake provider payloads only; no prices in these tests are published as real market data. */
class FreeHistoryTest {
    private val client = FreeHistoryDownloader()
    private val today = LocalDate.of(2026, 9, 23)
    private val ecb = FreeHistoryCatalog.find("eur-usd")!!
    private val stock = FreeHistoryCatalog.find("aapl-stock")!!
    private val gold = FreeHistoryCatalog.find("gold-monthly")!!

    private fun goldCsv(extra: String = "") = "Date,Price\n" +
        ((1..30).map { i -> "${today.withDayOfMonth(1).minusMonths(i.toLong())},4000.0" } +
            listOf(extra).filter { it.isNotEmpty() }).joinToString("\n") + "\n"

    private fun rates(extra: String = "") = "[" +
        ((1..35).map { i ->
            """{"date":"${today.minusDays(i.toLong())}","base":"EUR","quote":"USD","rate":1.1}"""
        } + listOf(extra).filter { it.isNotEmpty() }).joinToString(",") + "]"

    private fun ohlc(extra: String = "", symbol: String = "AAPL") = """{"meta":{"symbol":"$symbol","interval":"1day","currency":"USD"},"values":[""" +
        ((1..35).map { i ->
            """{"datetime":"${today.minusDays(i.toLong())}","open":"101","high":"105","low":"99","close":"104","volume":"200"}"""
        } + listOf(extra).filter { it.isNotEmpty() }).joinToString(",") + "]}"

    @Test fun centralBankRatesAreRealReferenceObservationsNotInventedCandles() {
        val from = today.minusDays(40)
        val before = """{"date":"${from.minusDays(1)}","base":"EUR","quote":"USD","rate":1.2}"""
        val parsed = client.parseEcb(rates(before), ecb, from, today)
        assertEquals(35, parsed.size)
        assertEquals(today.minusDays(1), parsed.last().date)
        assertEquals(1.1, parsed.last().rate, 0.0001)
        val data = FreeHistoryResult.Rates(ecb, parsed, "https://api.frankfurter.dev/v2/providers/ecb/rates", 1L)
        assertTrue(FreeHistoryDownloader.csv(data).startsWith("date,base,quote,reference_rate,source\n"))
        assertFalse(FreeHistoryDownloader.csv(data).contains("open,high,low"))
    }

    @Test fun mismatchDuplicatedOrStaleRatesAreRejected() {
        val from = today.minusDays(40)
        for (bad in listOf(
            rates("""{"date":"${today.minusDays(2)}","base":"EUR","quote":"GBP","rate":1.2}"""),
            rates("""{"date":"${today.minusDays(1)}","base":"EUR","quote":"USD","rate":1.2}"""),
            rates("""{"date":"$today","base":"EUR","quote":"USD","rate":1.2}"""),
            rates().replace("\"rate\":1.1", "\"rate\":-1.1"),
        )) {
            try {
                client.parseEcb(bad, ecb, from, today)
                throw AssertionError("Mismatched or invalid ECB rate was accepted")
            } catch (_: RuntimeException) { /* fail closed */ }
        }
    }

    @Test fun worldBankMonthlyGoldIsNotRelabeledAsSpotOrCandles() {
        val oldAnnualRepeat = "1959-12-01,35.0"
        val monthly = client.parseGoldMonthly(goldCsv(oldAnnualRepeat), today)
        assertEquals(30, monthly.size)
        assertEquals(today.withDayOfMonth(1).minusMonths(1), monthly.last().date)
        assertEquals(4000.0, monthly.last().usdPerTroyOunce, 0.001)
        val data = FreeHistoryResult.GoldMonthly(gold, monthly, gold.sourcePage, 1L)
        assertTrue(FreeHistoryDownloader.csv(data).startsWith("month,usd_per_troy_ounce,source\n"))
        assertFalse(FreeHistoryDownloader.csv(data).contains("open,high,low,close"))
        for (bad in listOf(
            goldCsv("${today.withDayOfMonth(1).minusMonths(1)},3000"), // duplicate
            goldCsv("${today.withDayOfMonth(1).plusMonths(1)},3000"), // future
            goldCsv().replace("4000.0", "-1"),
            goldCsv().replace("Date,Price", "Date,Open,High,Low,Close"),
        )) {
            try {
                client.parseGoldMonthly(bad, today)
                throw AssertionError("Invalid gold history was accepted")
            } catch (_: RuntimeException) { /* fail closed */ }
        }
    }

    @Test fun stockDailyDropsTodayAndRequiresExactAssetCurrencyAndValidOhlc() {
        val forming = """{"datetime":"$today","open":"101","high":"105","low":"99","close":"104"}"""
        val parsed = client.parseTwelveDaily(ohlc(forming), stock, today)
        assertEquals(35, parsed.size)
        assertEquals(today.minusDays(1), parsed.last().date)
        assertEquals(104.0, parsed.last().close, 0.001)
        assertEquals(200.0, parsed.last().volume!!, 0.001)
        val data = FreeHistoryResult.Ohlc(stock, parsed, stock.sourcePage, 1L)
        assertTrue(FreeHistoryDownloader.csv(data).startsWith("date,symbol,open,high,low,close,volume,source\n"))
        for (bad in listOf(
            ohlc(symbol = "MSFT"),
            ohlc("""{"datetime":"${today.minusDays(2)}","open":"101","high":"105","low":"99","close":"104"}"""),
            ohlc().replace("\"high\":\"105\"", "\"high\":\"98\""),
            ohlc().replace("\"volume\":\"200\"", "\"volume\":\"unknown\""),
            """{"code":429,"message":"rate limit"}""",
        )) {
            try {
                client.parseTwelveDaily(bad, stock, today)
                throw AssertionError("Invalid stock candles were accepted")
            } catch (_: RuntimeException) { /* fail closed */ }
        }
    }
}
