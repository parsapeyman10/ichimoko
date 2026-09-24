package com.aurum.edge.engine

import com.aurum.edge.core.Interval
import com.aurum.edge.data.HistDataCsv
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
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

    @Test fun rawAsciiAndMtCsvAreBidOnlyResearchWithFixedEstAndLastFiveThousand() {
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
        assertEquals(name, good.second)
        assertEquals(230, HistDataCsv.parse(good.first, good.second, now).totalRows)
        listOf(
            zip("../$name" to csv()),
            zip("DAT_ASCII_XAUUSD_M1_202607.csv" to csv()),
            zip(name to csv(), name to csv()),
        ).forEach { bytes -> assertTrue(runCatching { HistDataCsv.unzip(bytes, nameZip) }.isFailure) }
    }

    @Test fun corruptedSymbolPeriodDuplicatesAndTickFilesFailClosed() {
        assertTrue(runCatching { HistDataCsv.parse(csv(), "DAT_ASCII_EURUSD_M1_202608.csv", now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv(), "DAT_ASCII_XAUUSD_M1_202607.csv", now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv(duplicate = true), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv().replaceFirst("20260804 100000", "20260804 100030"), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse(csv().replaceFirst(";2501.00;", ";2498.00;"), name, now) }.isFailure)
        assertTrue(runCatching { HistDataCsv.parse("20260804 100001660,2500.0,2500.5,0\n".repeat(230), name, now) }.isFailure)
    }
}
