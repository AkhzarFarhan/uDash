package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.youtube.YouTubeUploader

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.uploadForegroundInfo(applicationContext, "Uploading to YouTube…")

    override suspend fun doWork(): Result {
        if (!sl.config.isConfigured() || !sl.auth.isAuthorized()) {
            sl.log.w("UploadWorker", "Not configured/authorized; skipping")
            return Result.success()
        }
        setForeground(getForegroundInfo())
        val uploader = YouTubeUploader(sl.auth, sl.config, sl.log)
        val deleteLocal = sl.config.config.value.deleteAfterUpload

        while (true) {
            val item = sl.files.nextToUpload() ?: break
            val file = sl.files.fileFor(item.fileName)
            if (!file.exists()) {
                sl.files.setStatus(item.fileName, FileStatus.PENDING)
                continue
            }
            try {
                sl.files.setStatus(item.fileName, FileStatus.UPLOADING)
                var sessionUri = item.uploadSessionUrl
                var startByte = 0L
                if (sessionUri.isNullOrBlank()) {
                    sessionUri = uploader.initiate(file, item.fileName.removeSuffix(".mp4"))
                    sl.files.update(item.copy(uploadSessionUrl = sessionUri, status = FileStatus.UPLOADING.name))
                    sl.log.i("UploadWorker", "Initiated session for ${item.fileName}", item.fileName)
                } else {
                    startByte = uploader.queryOffset(sessionUri, file.length())
                    sl.log.i("UploadWorker", "Resuming ${item.fileName} @ $startByte", item.fileName)
                }
                val videoId = if (startByte >= file.length()) {
                    uploader.uploadFrom(sessionUri, file, 0L) { s, t ->
                        sl.progress.updateUpload(item.fileName, s, t)
                    }
                } else {
                    uploader.uploadFrom(sessionUri, file, startByte) { s, t ->
                        sl.progress.updateUpload(item.fileName, s, t)
                    }
                }
                sl.files.markUploadedAndDelete(item.fileName, videoId, deleteLocal)
            } catch (e: Exception) {
                val msg = e.message ?: "unknown"
                sl.log.e("UploadWorker", "Upload error ${item.fileName}: $msg", item.fileName)
                if (msg.contains("quota", true) || msg.contains("403")) {
                    sl.log.w("UploadWorker", "Quota likely exceeded; pausing uploads for today")
                    return Result.retry()
                }
                val cur = sl.files.get(item.fileName)
                if (cur != null && cur.retryCount >= sl.config.config.value.maxRetries) {
                    sl.files.setStatus(item.fileName, FileStatus.FAILED)
                } else {
                    sl.files.update(
                        (cur ?: item).copy(
                            status = FileStatus.DOWNLOADED.name,
                            retryCount = (cur?.retryCount ?: 0) + 1,
                            errorMessage = msg
                        )
                    )
                    return Result.retry()
                }
            }
        }
        sl.log.i("UploadWorker", "Upload queue drained")
        return Result.success()
    }
}
