package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.di.ServiceLocator

/** Battery-saver mode: WorkManager wakes this every ~15 min to classify the network and enqueue work. */
class SyncCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sl = ServiceLocator.get(applicationContext)
        SyncController(applicationContext, sl).classifyAndEnqueue()
        sl.log.d("SyncCheckWorker", "Periodic sync check ran")
        return Result.success()
    }
}
