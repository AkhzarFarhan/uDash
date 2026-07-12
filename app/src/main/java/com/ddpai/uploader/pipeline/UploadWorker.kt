package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.DashcamNetworkResolver
import com.ddpai.uploader.youtube.QuotaClock
import com.ddpai.uploader.youtube.QuotaError
import com.ddpai.uploader.youtube.ResumeResult
import com.ddpai.uploader.youtube.UploadHttpException
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
        val pausedUntil = sl.config.getQuotaPausedUntil()
        if (System.currentTimeMillis() < pausedUntil) {
            sl.log.w("UploadWorker", "Uploads paused until $pausedUntil (quota); skipping")
            return Result.success()
        }
        if (!onValidatedNonDashcamWifi()) {
            sl.log.w("UploadWorker", "Not on validated internet Wi-Fi; deferring uploads")
            return Result.retry()
        }
        promoteToForeground()

        val uploader = YouTubeUploader(sl.auth, sl.config, sl.log)
        val deleteLocal = sl.config.config.value.deleteAfterUpload
        val maxRetries = sl.config.config.value.maxRetries

        // Reclaim rows a previous run left mid-upload when the process was killed; the persisted
        // uploadSessionUrl then drives the resumable-session resume in uploadOne.
        val reclaimed = sl.files.reclaimOrphans(FileStatus.UPLOADING, FileStatus.DOWNLOADED)
        if (reclaimed > 0) sl.log.w("UploadWorker", "Reclaimed $reclaimed orphaned UPLOADING → DOWNLOADED")

        while (true) {
            val item = sl.files.nextToUpload() ?: break
            val file = sl.files.fileFor(item.fileName)
            if (!file.exists()) {
                sl.log.w("UploadWorker", "Local file missing for ${item.fileName}; resetting", item.fileName)
                sl.files.setStatus(item.fileName, FileStatus.PENDING)
                continue
            }
            try {
                sl.files.setStatus(item.fileName, FileStatus.UPLOADING)
                val videoId = uploadOne(uploader, item.fileName, file)
                sl.files.markUploadedAndDelete(item.fileName, videoId, deleteLocal)
                sl.log.i("UploadWorker", "UPLOADED ${item.fileName} → $videoId", item.fileName)
            } catch (e: UploadHttpException) {
                if (QuotaError.isQuota(e.code, e.bodyText)) {
                    val until = QuotaClock.nextResetMillis(System.currentTimeMillis())
                    sl.config.setQuotaPausedUntil(until)
                    sl.files.setStatus(item.fileName, FileStatus.DOWNLOADED)
                    sl.log.w("UploadWorker", "Quota exceeded; pausing uploads until $until", item.fileName)
                    PipelineScheduler.enqueueMergeThenUpload(
                        applicationContext,
                        until - System.currentTimeMillis(),
                        ExistingWorkPolicy.REPLACE
                    )
                    return Result.success()
                }
                if (e.code == 401) {
                    sl.config.setNeedsReauth(true)
                    sl.log.e("UploadWorker", "401 unauthorized; re-auth required", item.fileName)
                    sl.files.setStatus(item.fileName, FileStatus.DOWNLOADED)
                    return Result.success()
                }
                handleRetryableUploadError(item.fileName, "HTTP ${e.code}", maxRetries)
                return Result.retry()
            } catch (e: Exception) {
                handleRetryableUploadError(item.fileName, e.message ?: "upload error", maxRetries)
                return Result.retry()
            }
        }
        sl.progress.clear()
        sl.log.i("UploadWorker", "Upload queue drained")
        return Result.success()
    }

    /** Runs the resumable protocol for one file; re-initiates once if the session is expired. */
    private suspend fun uploadOne(uploader: YouTubeUploader, name: String, file: java.io.File): String {
        var item = sl.files.get(name) ?: throw IllegalStateException("row vanished")
        var sessionUri = item.uploadSessionUrl
        var reinitiated = false

        while (true) {
            if (sessionUri.isNullOrBlank()) {
                sessionUri = uploader.initiate(file, com.ddpai.uploader.youtube.UploadTitle.of(item))
                sl.files.update(item.copy(uploadSessionUrl = sessionUri, status = FileStatus.UPLOADING.name))
                item = sl.files.get(name)!!
                sl.log.i("UploadWorker", "Initiated session for $name", name)
                return uploader.uploadFrom(sessionUri, file, 0L) { s, t -> sl.progress.updateUpload(name, s, t) }
            }
            when (val offset = uploader.queryOffset(sessionUri, file.length())) {
                is ResumeResult.Complete ->
                    return offset.videoId ?: throw UploadHttpException(200, "completed but no id")
                is ResumeResult.Incomplete -> {
                    val start = if (offset.nextByte < 0) 0L else offset.nextByte
                    sl.log.i("UploadWorker", "Resuming $name @ $start", name)
                    return uploader.uploadFrom(sessionUri, file, start) { s, t -> sl.progress.updateUpload(name, s, t) }
                }
                is ResumeResult.SessionExpired -> {
                    if (reinitiated) throw UploadHttpException(410, "session expired twice")
                    reinitiated = true
                    sl.log.w("UploadWorker", "Session expired for $name; re-initiating", name)
                    sl.files.update(item.copy(uploadSessionUrl = null))
                    item = sl.files.get(name)!!
                    sessionUri = null
                }
                is ResumeResult.Fatal -> throw UploadHttpException(offset.code, offset.body)
            }
        }
    }

    private suspend fun handleRetryableUploadError(name: String, msg: String, maxRetries: Int) {
        sl.log.e("UploadWorker", "Upload error $name: $msg", name)
        sl.files.recordRetry(name, FileStatus.DOWNLOADED, msg)
        val cur = sl.files.get(name)
        if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
            sl.files.setStatus(name, FileStatus.FAILED)
            sl.log.w("UploadWorker", "$name exhausted upload retries → FAILED", name)
        }
    }

    private fun onValidatedNonDashcamWifi(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        val gateway = sl.config.config.value.dashcamGateway
        return DashcamNetworkResolver(cm, applicationContext).resolve(gateway) != active
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("UploadWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
