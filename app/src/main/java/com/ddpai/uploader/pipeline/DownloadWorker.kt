package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.dashcam.DashcamClient
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.integrity.IntegrityVerifier

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.downloadForegroundInfo(applicationContext, "Scanning dashcam…")

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val network = sl.currentDashcamNetwork ?: run {
            sl.log.w("DownloadWorker", "No dashcam network bound")
            return Result.success()
        }

        val gateway = sl.config.config.value.dashcamGateway
        val client = DashcamClient(network, gateway, sl.log)
        val verifier = IntegrityVerifier(sl.log)

        val remote = try {
            client.listFiles()
        } catch (e: Exception) {
            sl.log.e("DownloadWorker", "Listing failed: ${e.message}")
            return Result.retry()
        }
        val added = sl.files.reconcileListing(remote, gateway)
        sl.log.i("DownloadWorker", "Listing reconciled: +$added new, ${remote.size} total")

        val pending = sl.files.pendingDownloads()
        for (item in pending) {
            if (sl.currentDashcamNetwork == null) {
                sl.log.w("DownloadWorker", "Lost AP; stopping")
                break
            }
            val target = sl.files.fileFor(item.fileName)
            val existing = if (target.exists()) target.length() else 0L
            try {
                sl.files.setStatus(item.fileName, FileStatus.DOWNLOADING)
                sl.log.i("DownloadWorker", "Downloading ${item.fileName} (resume@$existing)", item.fileName)
                client.download(item.fileName, target, existing) { dl, total ->
                    sl.progress.updateDownload(item.fileName, dl, total)
                }
                val verdict = verifier.verify(target)
                if (!verdict.valid) {
                    sl.files.markCorruptAndReset(item.fileName, verdict.reason)
                    continue
                }
                sl.files.update(
                    item.copy(
                        status = FileStatus.DOWNLOADED.name,
                        localPath = target.absolutePath,
                        sizeBytes = target.length(),
                        downloadedBytes = target.length(),
                        errorMessage = null
                    )
                )
                sl.log.i("DownloadWorker", "DOWNLOADED ${item.fileName} (${target.length()} bytes)", item.fileName)
            } catch (e: Exception) {
                sl.log.e("DownloadWorker", "Download error ${item.fileName}: ${e.message}", item.fileName)
                val cur = sl.files.get(item.fileName)
                if (cur != null && cur.retryCount >= sl.config.config.value.maxRetries) {
                    sl.files.setStatus(item.fileName, FileStatus.FAILED)
                } else {
                    sl.files.setStatus(item.fileName, FileStatus.PENDING)
                }
            }
        }
        sl.log.i("DownloadWorker", "Download cycle complete")
        return Result.success()
    }
}
