package com.aurum.edge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aurum.edge.core.BacktestNeuralTrainer
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BacktestTradeArchive
import com.aurum.edge.core.peyman.PeymanCryptoConfig
import com.aurum.edge.data.BacktestArchiveStore
import com.aurum.edge.data.BacktestDataStore
import com.aurum.edge.data.BacktestJob
import com.aurum.edge.data.BacktestJobResult
import com.aurum.edge.data.BacktestJobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * # اجرای بک‌تست در پس‌زمینه
 *
 * چرا `WorkManager` و نه یک `Thread` داخل ViewModel؟ چون کاربر می‌خواهد **از اپ بیرون
 * برود** و کار ادامه پیدا کند. ViewModel با بسته‌شدن صفحه می‌میرد؛ `WorkManager` نه.
 * هم‌چنین:
 * - کار به‌صورت پایدار ثبت می‌شود (حتی اگر گوشی ری‌استارت شود دوباره صف می‌شود).
 * - شرط «باتری کم نباشد / حافظه کم نباشد» رعایت می‌شود تا کار نیمه‌کاره کشته نشود.
 * - نتیجه در `BacktestJobStore` نوشته می‌شود، پس بعد از بازگشت کاربر همان‌جا است.
 *
 * کار هیچ‌وقت «حس واقعی» را تغییر نمی‌دهد: فقط می‌خوانَد، حساب می‌کند و نتیجه را
 * در آرشیو می‌نویسد. اعمال روی موجودی همچنان دستی و با تأیید کاربر است.
 */
class BacktestJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext androidx.work.ListenableWorker.Result.failure()
        val store = BacktestJobStore(applicationContext)
        val job = store.find(jobId) ?: return@withContext androidx.work.ListenableWorker.Result.failure()

        store.update(jobId) { it.copy(status = BacktestJob.STATUS_RUNNING, messageFa = "در حال اجرا…") }

        val spec = job.spec
        runCatching {
            val dataStore = BacktestDataStore(applicationContext)
            val all = dataStore.loadCandles(spec.dataSetId)
            if (all.isEmpty()) error("دادهٔ این کار روی حافظه پیدا نشد (${spec.dataSetName}).")
            val cut = if (spec.cursorIndex in 1..all.size) all.take(spec.cursorIndex) else all
            if (cut.size < 80) error("برای بک‌تست حداقل ۸۰ کندل لازم است؛ این کار ${cut.size} کندل دارد.")

            val set = dataStore.list().firstOrNull { it.id == spec.dataSetId }
            val trainer = BacktestNeuralTrainer(applicationContext)
            val config = BacktestReplayEngine.Config(
                strategyId = spec.strategyId,
                symbol = spec.symbol.ifBlank { set?.symbol ?: "XAU/USD" },
                triggerInterval = set?.interval ?: com.aurum.edge.domain.Interval.M15,
                startEquityUsd = spec.startEquityUsd,
                riskPercent = spec.riskPercent,
                runAllStrategies = spec.runAllStrategies,
                useSmartExit = spec.useSmartExit,
                usePreserveMode = spec.usePreserveMode,
                useTimeStop = spec.useTimeStop,
                optimisticSameBarFill = spec.optimisticSameBarFill,
                maxBars = spec.maxBars,
                includeHiddenCrossLayer = spec.includeHiddenCrossLayer,
                // تایم‌فریم‌های بالای واقعی فقط وقتی در دسترس‌اند که کاربر فایل داده باشد؛
                // این‌جا خالی است و موتور مثل حالت بدون فایل، از بازنمونه‌گیری استفاده می‌کند.
                macroCandlesOverride = emptyList(),
                strategyCandlesOverride = emptyList(),
                compressionExpansionEnabled = spec.compressionExpansion,
                peymanCryptoConfig = if (spec.peymanCryptoPaper) {
                    PeymanCryptoConfig.PAPER
                } else {
                    PeymanCryptoConfig()
                },
                captureEntryReports = false,
                neuralGate = trainer.entryGate(),
            )
            // همان مسیر خودکار: فایل سنگین ماه‌به‌ماه، سبک یک‌جا.
            val result = BacktestReplayEngine.runAuto(cut, config) { }
            val stats = result.stats.firstOrNull()
            val built = BacktestTradeArchive.build(
                result = result,
                dataSetId = spec.dataSetId,
                dataSetName = spec.dataSetName,
                createdAt = System.currentTimeMillis(),
                candles = cut,
            )
            val files = BacktestArchiveStore(applicationContext).save(built.archive, built)
            val head = String.format(
                Locale.US,
                "معامله=%d · برد=%.0f%% · خالص=%+.2f$ · PF=%.2f · انتظار=%+.2fR",
                result.trades.size,
                stats?.winRatePct ?: 0.0,
                stats?.netPnlUsd ?: 0.0,
                stats?.profitFactor ?: 0.0,
                stats?.expectancyR ?: 0.0,
            )
            // گزارش ماه‌به‌ماه (اگر اجرا در این حالت بوده) پایین خلاصه می‌آید تا کاربر بداند
            // هر ماه جدا چه چیزی به دست آمده است.
            val summaryFa = if (result.periodSummariesFa.isEmpty()) {
                head
            } else {
                head + "\n\nگزارش ماه‌به‌ماه:\n" + result.periodSummariesFa.joinToString("\n")
            }
            store.update(jobId) {
                it.copy(
                    status = BacktestJob.STATUS_DONE,
                    messageFa = "تمام شد.",
                    result = BacktestJobResult(
                        finishedAt = System.currentTimeMillis(),
                        trades = result.trades.size,
                        winRatePct = stats?.winRatePct ?: 0.0,
                        netPnlUsd = stats?.netPnlUsd ?: 0.0,
                        profitFactor = stats?.profitFactor ?: 0.0,
                        expectancyR = stats?.expectancyR ?: 0.0,
                        candlesUsed = result.candlesUsed,
                        elapsedMs = result.elapsedMs,
                        strategyLabel = stats?.strategyTitleFa ?: spec.strategyId,
                        archiveCsvPath = files.csv.absolutePath,
                        archiveJsonPath = files.json.absolutePath,
                        summaryFa = summaryFa,
                    ),
                )
            }
            androidx.work.ListenableWorker.Result.success()
        }.getOrElse { error ->
            store.update(jobId) {
                it.copy(
                    status = BacktestJob.STATUS_FAILED,
                    messageFa = error.message ?: "اجرا ناموفق بود.",
                )
            }
            androidx.work.ListenableWorker.Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val TAG = "backtest-job"

        /**
         * کار را صف می‌کند. `KEEP` یعنی اگر همان کار قبلاً در صف است، دوباره صف نشود.
         * شرط شبکه لازم نیست (داده روی حافظه است)، ولی باتری/حافظه چک می‌شود تا اجرای
         * سنگین وسط شرایط بحرانی کشته نشود.
         */
        fun enqueue(context: Context, jobId: String) {
            val request = OneTimeWorkRequestBuilder<BacktestJobWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .addTag(TAG)
                .addTag(tagFor(jobId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(tagFor(jobId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(tagFor(jobId))
        }

        private fun tagFor(jobId: String): String = "$TAG:$jobId"
    }
}
