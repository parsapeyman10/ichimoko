package com.aurum.edge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.edge.data.DailyGithubBackup
import kotlinx.coroutines.CancellationException

class DailyGithubBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        DailyGithubBackup.send(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DailyGithubBackup.status(applicationContext, "آخرین تلاش ناموفق؛ تلاش مجدد: ${e.message ?: "خطای ناشناخته"}")
        Result.retry()
    }
}
