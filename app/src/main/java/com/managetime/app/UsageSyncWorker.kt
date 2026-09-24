package com.managetime.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.managetime.app.data.AppRepository
import java.util.concurrent.TimeUnit

class UsageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repository = AppRepository(applicationContext)
        if (repository.hasUsageAccess()) repository.syncUsage()
        repository.prune()
        Result.success()
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("usage-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
