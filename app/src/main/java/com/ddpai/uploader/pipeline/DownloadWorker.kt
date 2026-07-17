package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.dashcam.DashcamClient
import com.ddpai.uploader.dashcam.ListingFilter
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.integrity.IntegrityVerifier
import com.ddpai.uploader.network.DashcamNetworkResolver
import kotlinx.coroutines.launch

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.downloadForegroundInfo(applicationContext, "Scanning dashcam…")

    override suspend fun doWork(): Result {
        sl.log.x("DownloadWorker", "doWork() started")
        try {
            promoteToForeground()

            val gateway = sl.config.config.value.dashcamGateway
            sl.log.x("DownloadWorker", "Resolving dashcam gateway: $gateway")
            val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            val resolver = DashcamNetworkResolver(cm, applicationContext)
            val network = resolver.resolve(gateway) ?: sl.currentDashcamNetwork
            if (network == null) {
                sl.log.w("DownloadWorker", "Dashcam network not resolvable (attempt $runAttemptCount)")
                return if (runAttemptCount >= 5) Result.success() else Result.retry()
            }
            sl.currentDashcamNetwork = network
            sl.log.x("DownloadWorker", "Resolved network: $network")

            val client = DashcamClient(network, gateway, sl.log, sl.config.config.value.dashcamType)
            val verifier = IntegrityVerifier(sl.log)

            sl.log.x("DownloadWorker", "Fetching remote files from dashcam")
            val remote = try {
                client.listFiles()
            } catch (e: Exception) {
                sl.log.e("DownloadWorker", "Listing failed: ${e.message}")
                return Result.retry()
            }
            sl.log.x("DownloadWorker", "Total remote files found: ${remote.size}")
            val downloadable = ListingFilter.excludeNewest(remote)
            sl.log.x("DownloadWorker", "Excluding newest in-progress segment: ${downloadable.size} downloadable left")
            val added = sl.files.reconcileListing(downloadable, gateway)
            sl.log.i(
                "DownloadWorker",
                "Listing: ${remote.size} total, ${downloadable.size} downloadable, +$added new"
            )

            val maxRetries = sl.config.config.value.maxRetries
            // Reclaim rows a previous run left mid-download when the process was killed (no catch ran).
            val reclaimed = sl.files.reclaimOrphans(FileStatus.DOWNLOADING, FileStatus.PENDING)
            if (reclaimed > 0) sl.log.w("DownloadWorker", "Reclaimed $reclaimed orphaned DOWNLOADING → PENDING")
            
            sl.log.x("DownloadWorker", "Fetching pending downloads from DB")
            val pending = sl.files.pendingDownloads()
            sl.log.x("DownloadWorker", "Pending downloads count: ${pending.size}")
            for (item in pending) {
                sl.log.x("DownloadWorker", "Processing pending download: ${item.fileName}")
                if (resolver.resolve(gateway) == null) {
                    sl.log.w("DownloadWorker", "Lost dashcam AP; stopping cycle")
                    break
                }
                val target = sl.files.fileFor(item.fileName)
                val existing = if (target.exists()) target.length() else 0L
                try {
                    sl.log.x("DownloadWorker", "Setting status to DOWNLOADING for ${item.fileName}")
                    sl.files.setStatus(item.fileName, FileStatus.DOWNLOADING)
                    sl.log.i("DownloadWorker", "Downloading ${item.fileName} (resume@$existing)", item.fileName)
                    var lastWrite = 0L
                    client.download(item.fileName, target, existing) { dl, total ->
                        sl.progress.updateDownload(item.fileName, dl, total)
                        val now = System.currentTimeMillis()
                        if (now - lastWrite >= 1000L) {
                            lastWrite = now
                            sl.io.launch {
                                sl.log.x("DownloadWorker", "Updating download progress in DB for ${item.fileName}: $dl/$total")
                                sl.files.setDownloadProgress(item.fileName, dl, maxOf(total, 0L))
                            }
                        }
                    }
                    sl.log.x("DownloadWorker", "Verifying integrity for ${item.fileName}")
                    val verdict = verifier.verify(target)
                    if (!verdict.valid) {
                        sl.log.x("DownloadWorker", "Integrity check failed: ${verdict.reason}")
                        sl.files.markCorruptAndReset(item.fileName, verdict.reason)
                        val cur = sl.files.get(item.fileName)
                        if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
                            sl.files.setStatus(item.fileName, FileStatus.FAILED)
                            sl.log.w("DownloadWorker", "${item.fileName} repeatedly corrupt → FAILED", item.fileName)
                        }
                        continue
                    }
                    sl.log.x("DownloadWorker", "Setting status to DOWNLOADED for ${item.fileName}")
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
                    sl.files.recordRetry(item.fileName, FileStatus.PENDING, e.message ?: "download error")
                    val cur = sl.files.get(item.fileName)
                    if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
                        sl.files.setStatus(item.fileName, FileStatus.FAILED)
                        sl.log.w("DownloadWorker", "${item.fileName} exhausted retries → FAILED", item.fileName)
                    }
                }
            }
            sl.progress.clear()
            sl.log.i("DownloadWorker", "Download cycle complete")
            return Result.success()
        } catch (t: Throwable) {
            sl.log.e("DownloadWorker", "Fatal unhandled exception in DownloadWorker: ${t.message}\n${t.stackTraceToString()}")
            return Result.failure()
        }
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("DownloadWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
