package com.aurum.edge.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «کار پس‌زمینه» که به `Context` نیاز ندارند: فقط پایداریِ قرارداد JSON و
 * منطق وضعیت. دلیل اهمیت: کاربر می‌خواهد از اپ بیرون برود، کار در پس‌زمینه تمام شود و
 * **نتیجه بعد از بستن و باز کردن اپ همان‌طور بماند**. اگر مدل سریال‌شدنی نباشد یا
 * فیلدها بی‌صدا حذف شوند، نتیجه ظاهراً ذخیره می‌شود ولی بعد از ری‌استارت می‌پرد.
 */
class BacktestJobStoreTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun spec(id: String = "job-1") = BacktestJobSpec(
        id = id,
        dataSetId = "btcusd-m15.csv",
        dataSetName = "BTC 15m",
        createdAt = 1_700_000_000_000L,
        strategyId = "base_ichimoku",
        symbol = "BTC/USDT",
        runAllStrategies = false,
        startEquityUsd = 1_000.0,
        riskPercent = 0.5,
        useSmartExit = true,
        usePreserveMode = true,
        useTimeStop = true,
        optimisticSameBarFill = false,
        maxBars = 0,
        includeHiddenCrossLayer = false,
        compressionExpansion = false,
        peymanCryptoPaper = true,
        cursorIndex = 5_000,
        noteFa = "اجرای شبانه",
    )

    @Test
    fun `job spec survives a JSON round trip with every parameter intact`() {
        val encoded = json.encodeToString(BacktestJobSpec.serializer(), spec())
        val decoded = json.decodeFromString(BacktestJobSpec.serializer(), encoded)
        assertEquals(spec(), decoded)
        // «تغییر دادن و اجرای دوباره» فقط وقتی معنا دارد که همهٔ پارامترها برگردند.
        assertEquals("peyman-crypto", decoded.peymanCryptoPaper.let { if (it) "peyman-crypto" else "disabled" })
        assertEquals(0.5, decoded.riskPercent, 1e-9)
        assertEquals(5_000, decoded.cursorIndex)
    }

    @Test
    fun `finished job keeps its result and status across a round trip`() {
        val job = BacktestJob(
            spec = spec(),
            status = BacktestJob.STATUS_DONE,
            messageFa = "تمام شد.",
            result = BacktestJobResult(
                finishedAt = 1_700_000_100_000L,
                trades = 42,
                winRatePct = 47.6,
                netPnlUsd = 123.4,
                profitFactor = 1.21,
                expectancyR = 0.08,
                candlesUsed = 5_000,
                elapsedMs = 12_000,
                strategyLabel = "ایچیموکو",
                archiveCsvPath = "/data/archive/trades.csv",
                archiveJsonPath = "/data/archive/trades.json",
                summaryFa = "معامله=42",
            ),
        )
        val decoded = json.decodeFromString(BacktestJob.serializer(), json.encodeToString(BacktestJob.serializer(), job))
        assertEquals(BacktestJob.STATUS_DONE, decoded.status)
        assertTrue(decoded.isFinished)
        assertNotNull(decoded.result)
        assertEquals(42, decoded.result?.trades)
        assertEquals(1.21, decoded.result?.profitFactor ?: 0.0, 1e-9)
    }

    @Test
    fun `queued job has no result and is not finished`() {
        val job = BacktestJob(spec = spec("job-2"))
        assertEquals(BacktestJob.STATUS_QUEUED, job.status)
        assertFalse(job.isFinished)
        assertEquals(null, job.result)
    }

    @Test
    fun `only finished statuses count as finished`() {
        listOf(
            BacktestJob.STATUS_DONE,
            BacktestJob.STATUS_FAILED,
            BacktestJob.STATUS_CANCELLED,
        ).forEach { status ->
            assertTrue(status, BacktestJob(spec = spec(), status = status).isFinished)
        }
        listOf(BacktestJob.STATUS_QUEUED, BacktestJob.STATUS_RUNNING).forEach { status ->
            assertFalse(status, BacktestJob(spec = spec(), status = status).isFinished)
        }
    }
}
