package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object PipelineScheduler {
    const val DOWNLOAD_WORK = "ddpai_download"
    const val UPLOAD_WORK = "ddpai_upload"

    fun enqueueDownload(context: Context) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DOWNLOAD_WORK)
        wm.cancelUniqueWork(UPLOAD_WORK)
    }
}
