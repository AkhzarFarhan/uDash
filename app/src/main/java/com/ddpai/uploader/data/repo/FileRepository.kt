package com.ddpai.uploader.data.repo

import com.ddpai.uploader.data.db.VideoFileDao
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.DashcamFile
import com.ddpai.uploader.data.model.FileStatus
import java.io.File

class FileRepository(
    private val dao: VideoFileDao,
    private val logger: LogRepository,
    private val storageDir: File
) {
    fun observeAll() = dao.observeAll()
    fun observeCount(status: FileStatus) = dao.observeCountByStatus(status.name)

    suspend fun reconcileListing(remote: List<DashcamFile>, gateway: String): Int {
        var added = 0
        remote.forEach { f ->
            val entity = VideoFileEntity(
                fileName = f.fileName,
                remoteUrl = "http://$gateway/${f.fileName}",
                localPath = null,
                status = FileStatus.DISCOVERED.name,
                sizeBytes = f.sizeBytes,
                capturedAtEpoch = f.capturedAtEpoch
            )
            if (dao.insertIgnore(entity) != -1L) {
                added++
                logger.i("FileRepo", "Discovered ${f.fileName}")
            }
        }
        return added
    }

    suspend fun pendingDownloads() = dao.pendingDownloads()
    suspend fun nextToUpload() = dao.nextToUpload()
    suspend fun downloadedSegments() = dao.downloadedSegments()

    suspend fun relabelAsMerged(name: String) = dao.relabelAsMerged(name)

    suspend fun commitMerge(output: VideoFileEntity, consumedNames: List<String>) =
        dao.commitMerge(output, consumedNames)

    suspend fun get(name: String) = dao.getByName(name)
    suspend fun update(e: VideoFileEntity) = dao.update(e.copy(updatedAtEpoch = System.currentTimeMillis()))
    suspend fun setStatus(name: String, s: FileStatus) = dao.setStatus(name, s.name)

    suspend fun recordRetry(name: String, retryStatus: FileStatus, error: String) =
        dao.recordRetry(name, retryStatus.name, error)

    suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long) =
        dao.setDownloadProgress(name, downloaded, size)

    suspend fun reclaimOrphans(from: FileStatus, to: FileStatus) =
        dao.reclaimOrphans(from.name, to.name)

    fun fileFor(name: String) = File(storageDir, name)

    suspend fun markCorruptAndReset(name: String, reason: String) {
        val e = dao.getByName(name) ?: return
        fileFor(name).delete()
        dao.update(
            e.copy(
                status = FileStatus.PENDING.name,
                localPath = null,
                downloadedBytes = 0,
                retryCount = e.retryCount + 1,
                errorMessage = reason,
                updatedAtEpoch = System.currentTimeMillis()
            )
        )
        logger.e("FileRepo", "Corrupt $name → reset PENDING: $reason", name)
    }

    suspend fun markUploadedAndDelete(name: String, videoId: String, deleteLocal: Boolean) {
        val e = dao.getByName(name) ?: return
        if (deleteLocal) fileFor(name).delete()
        dao.update(
            e.copy(
                status = FileStatus.UPLOADED.name,
                localPath = if (deleteLocal) null else e.localPath,
                youtubeVideoId = videoId,
                errorMessage = null,
                updatedAtEpoch = System.currentTimeMillis()
            )
        )
        logger.i("FileRepo", "Uploaded $name → $videoId, localDeleted=$deleteLocal", name)
    }
}
