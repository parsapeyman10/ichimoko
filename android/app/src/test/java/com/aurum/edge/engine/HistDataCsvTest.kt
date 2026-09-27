package com.aurum.edge.engine

import com.aurum.edge.core.Interval
import com.aurum.edge.data.HistDataCsv
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HistData's published M1 line specification as fixture; values are TEST values, not prices. */
class HistDataCsvTest {
    private val name = "DAT_ASCII_XAUUSD_M1_202608.csv"
    private val now = LocalDateTime.of(2026, 9, 24, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val start = LocalDateTime.of(2026, 8, 4, 10, 0)
    private val ascii = DateTimeFormatter.ofPattern("uuuuMMdd HHmmss")
    private val mt = DateTimeFormatter.ofPattern("uuuu.MM.dd,HH:mm")

    private fun csv(type: String = "ASCII", duplicate: Boolean = false) = buildString {
        repeat(230) { i ->
            val date = start.plusMinutes((if (duplicate && i == 10) 9 else i).toLong())
            val sep = if (type == "ASCII") ';' else ','
            append(date.format(if (type == "ASCII") ascii else mt))
            append("${sep}2500.00${sep}2501.00${sep}2499.00${sep}2500.50${sep}0\n")
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { stream ->
            entries.forEach { (path, text) ->
                stream.putNextEntry(ZipEntry(path))
                stream.write(text.toByteArray())
                stream.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun rawAsciiAndMtCsvAreBidOnlyResearchWithFixedEst() {
        val result = HistDataCsv.parse(csv(), name, now)
        assertEquals(230, result.totalRows)
        assertEquals("-05:00", result.timezone)
        assertEquals(start.toInstant(ZoneOffset.ofHours(-5)).toEpochMilli(), result.candles.first().time)
        assertEquals(Interval.M1.millis, result.candles[1].time - result.candles[0].time)
        assertEquals(0.0, result.candles.first().volume, 0.001) // HistData M1 can have zero volume
        val mtResult = HistDataCsv.parse(csv("MT"), "DAT_MT_XAUUSD_M1_202608.csv", now)
        assertEquals(result.candles, mtResult.candles)
    }

    @Test fun zipRequiresMatchingInnerFileAndRejectsPathTraversalOrDuplicateCsv() {
        val nameZip = "HISTDATA_COM_ASCII_XAUUSD_M1_202608.zip"
        val good = HistDataCsv.unzip(zip(name to csv()), nameZip)
        assertEquals(listOf(name), good.map { it.second })
        assertEquals(230, HistDataCsv.parse(good.single().first, good.single().second, now).totalRows)
        listOf(
            zip("../$name" to csv()),
            zip("DAT_ASCII_XAUUSD_M1_202607.csv" to csv()),
            zip(name to csv(), name.lowercase() to csv()),
        ).forEach { bytes -> assertTrue(runCatching { HistDataCsv.unzip(bytes, nameZip) }.isFailure) }
    }

    @Test fun corruptedSymbolPeriodDuplicatesAndTickFilesFailClosed() {
        assertTrue(runCatching { HistDataCsv.parse(csv(), "DAT_ASCII_USDEUR_M1_202608.csv", now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv(), "DAT_ASCII_XAUUSD_M1_202607.csv", now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv(duplicate = true), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv().replaceFirst("20260804 100000", "20260804 100030"), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv().replaceFirst(";2501.00;", ";2498.00;"), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse("20260804 100001660,2500.0,2500.5,0\n".repeat(230), name, now) }.isFailure)
    }

    @Test fun catalogMajorsParseAndMapToAppSymbols() {
        val eur = HistDataCsv.parse(csv(), "DAT_ASCII_EURUSD_M1_202608.csv", now)
        assertEquals(230, eur.candles.size)
        val month = HistDataCsv.parseMonth(csv(), "DAT_ASCII_EURUSD_M1_202608.csv", now)
        assertEquals("EUR/USD", month.symbol)
        assertEquals(YearMonth.of(2026, 8), month.period)
    }

    @Test fun yearlyZipHoldsAllItsMonthsAndRejectsForeignOnes() {
        val july = csv().replace("202608", "202607")
        val yearly = "HISTDATA_COM_ASCII_XAUUSD_M1_2026.zip"
        val out = HistDataCsv.unzip(zip("DAT_ASCII_XAUUSD_M1_202607.csv" to july, name to csv(),
            "HISTDATA_COM_ASCII_XAUUSD_M1_2026.txt" to "info"), yearly)
        assertEquals(2, out.size)
        // a monthly CSV of another year inside a yearly ZIP must fail closed
        assertTrue(runCatching {
            HistDataCsv.unzip(zip("DAT_ASCII_XAUUSD_M1_202507.csv" to july), yearly) }.isFailure)
    }

    /** 230 consecutive M1 bars starting at a whole hour, volume 1 each (usable for any month). */
    private fun monthCsv(start: LocalDateTime) = buildString {
        repeat(230) { i ->
            append(start.plusMinutes(i.toLong()).format(ascii))
            append(";2500.00;2501.00;2499.00;2500.50;1\n") } }

    @Test fun mergedMonthsAggregateFromRealM1BarsAscending() {
        val files = listOf(
            "DAT_ASCII_XAUUSD_M1_202608.csv" to monthCsv(LocalDateTime.of(2026, 8, 4, 10, 0)),
            "DAT_ASCII_XAUUSD_M1_202607.csv" to monthCsv(LocalDateTime.of(2026, 7, 4, 10, 0)),
        )
        val m5 = HistDataCsv.parseMerged(files, Interval.M5, now)
        assertEquals("XAU/USD", m5.symbol)
        assertEquals(2, m5.months)
        assertEquals(460, m5.totalRows)
        assertEquals(92, m5.candles.size) // 230 M1 bars -> 46 five-minute buckets per month
        // every bucket is an epoch-aligned 5m bar built from exactly five real M1 bars
        m5.candles.forEach { bar ->
            assertEquals(0L, bar.time % Interval.M5.millis)
            assertEquals(5.0, bar.volume, 0.001)
        }
        // only the archive's NEWEST bucket stays open (one honest bar); everything before closed
        assertTrue(m5.candles.dropLast(1).all { it.closed })
        assertFalse(m5.candles.last().closed)
        assertTrue(m5.candles.zipWithNext().all { (a, b) -> b.time > a.time })
        val m30 = HistDataCsv.parseMerged(files, Interval.M30, now)
        m30.candles.forEach { assertEquals(0L, it.time % Interval.M30.millis) }
        // raw M1 target keeps the bars untouched
        val m1 = HistDataCsv.parseMerged(files, Interval.M1, now)
        assertEquals(460, m1.candles.size)
    }

    @Test fun mergedResearchFailsClosedOnDuplicatesSymbolsAndCap() {
        val files = listOf(name to csv(), name to csv())
        assertTrue(runCatching { HistDataCsv.parseMerged(files, Interval.M5, now) }.isFailure)
        val mixed = listOf(name to csv(),
            "DAT_ASCII_EURUSD_M1_202607.csv" to monthCsv(LocalDateTime.of(2026, 7, 4, 10, 0)))
        assertTrue(runCatching { HistDataCsv.parseMerged(mixed, Interval.M5, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parseMerged(emptyList(), Interval.M5, now) }.isFailure)
    }
}
