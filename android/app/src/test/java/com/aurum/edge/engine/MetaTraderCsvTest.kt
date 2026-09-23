package com.aurum.edge.engine

import com.aurum.edge.core.Interval
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.MetaTraderCsv
import com.aurum.edge.data.NewsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** CSV fixtures simulate a user's exported file, never production market data. */
class MetaTraderCsvTest {
    private val fmt = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    private fun csv(rows: Int = 230, duplicate: Boolean = false): String = buildString {
        appendLine("<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>")
        val start = LocalDateTime.of(2025, 1, 1, 12, 0)
        repeat(rows) { i ->
            val at = start.plusMinutes(5L * if (duplicate && i == 5) 4 else i.toLong())
            val (date, time) = at.format(fmt).split(' ')
            appendLine("$date\t$time\t100.0\t102.0\t99.0\t101.0\t20")
        }
    }

    @Test fun importUsesExplicitBrokerTimezoneAndNeverGeneratesMissingBars() {
        val history = MetaTraderCsv.parse(csv(), Interval.M5, "+03:30", now = 1_900_000_000_000L)
        assertEquals(230, history.totalRows)
        assertEquals(230, history.candles.size)
        assertEquals("+03:30", history.timezone)
        assertTrue(history.candles.all { it.closed && it.high >= it.close && it.low <= it.open })
        assertEquals(5L * 60_000, history.candles[1].time - history.candles[0].time)
    }

    @Test fun malformedTimezoneOrDuplicatesOrIntervalAreNotSilentlyAccepted() {
        for ((data, interval, timezone) in listOf(
            Triple(csv(230, duplicate = true), Interval.M5, "+00:00"),
            Triple(csv(), Interval.H1, "+00:00"),
            Triple(csv(), Interval.M5, "Tehran"),
        )) {
            try {
                MetaTraderCsv.parse(data, interval, timezone, now = 1_900_000_000_000L)
                throw AssertionError("Import must fail")
            } catch (_: DataFeedException) { /* expected */ }
        }
        assertEquals(null, NewsRepository.newsUrl("http://localhost:8000"))
        assertEquals("https://news.example.com/api/v1/news/fa", NewsRepository.newsUrl("https://news.example.com"))
    }
}
