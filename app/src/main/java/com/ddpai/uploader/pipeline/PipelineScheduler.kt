package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object PipelineScheduler {
    const val DOWNLOAD_WORK = "ddpai_download"
    const val UPLOAD_WORK = "ddpai_upload"
    const val MERGE_UPLOAD_WORK = "ddpai_merge_upload"

    fun enqueueDownload(context: Context) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueUpload(
        context: Context,
        initialDelayMillis: Long = 0L,
        existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, existingPolicy, req)
    }

    fun enqueueMergeThenUpload(
        context: Context,
        initialDelayMillis: Long = 0L,
        existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val unmetered = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()
        val merge = OneTimeWorkRequestBuilder<MergeWorker>()
            .setConstraints(unmetered)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        val upload = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(unmetered)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .beginUniqueWork(MERGE_UPLOAD_WORK, existingPolicy, merge)
            .then(upload)
            .enqueue()
    }

    const val PERIODIC_CHECK_WORK = "ddpai_periodic_check"

    fun enablePeriodicChecks(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun disablePeriodicChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_CHECK_WORK)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DOWNLOAD_WORK)
        wm.cancelUniqueWork(UPLOAD_WORK)
        wm.cancelUniqueWork(MERGE_UPLOAD_WORK)
    }
}
