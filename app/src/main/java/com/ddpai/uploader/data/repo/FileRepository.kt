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
        logger.x("FileRepo", "reconcileListing() remoteSize=${remote.size} gateway=$gateway")
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
            val result = dao.insertIgnore(entity)
            logger.x("FileRepo", "reconcileListing: insertIgnore(${f.fileName}) returned $result")
            if (result != -1L) {
                added++
                logger.i("FileRepo", "Discovered ${f.fileName}")
            }
        }
        logger.x("FileRepo", "reconcileListing() finished, added=$added")
        return added
    }

    suspend fun pendingDownloads(): List<VideoFileEntity> {
        logger.x("FileRepo", "pendingDownloads() called")
        val res = dao.pendingDownloads()
        logger.x("FileRepo", "pendingDownloads() returned ${res.size} items")
        return res
    }

    suspend fun nextToUpload(): VideoFileEntity? {
        logger.x("FileRepo", "nextToUpload() called")
        val res = dao.nextToUpload()
        logger.x("FileRepo", "nextToUpload() returned: ${res?.fileName ?: "null"}")
        return res
    }

    suspend fun downloadedSegments(): List<VideoFileEntity> {
        logger.x("FileRepo", "downloadedSegments() called")
        val res = dao.downloadedSegments()
        logger.x("FileRepo", "downloadedSegments() returned ${res.size} items")
        return res
    }

    suspend fun relabelAsMerged(name: String) {
        logger.x("FileRepo", "relabelAsMerged() name=$name")
        dao.relabelAsMerged(name)
    }

    suspend fun commitMerge(output: VideoFileEntity, consumedNames: List<String>) {
        logger.x("FileRepo", "commitMerge() output=${output.fileName} consumedNames=$consumedNames")
        dao.commitMerge(output, consumedNames)
    }

    suspend fun get(name: String): VideoFileEntity? {
        logger.x("FileRepo", "get() name=$name")
        val res = dao.getByName(name)
        logger.x("FileRepo", "get() returned exists=${res != null}")
        return res
    }

    suspend fun update(e: VideoFileEntity) {
        logger.x("FileRepo", "update() name=${e.fileName} status=${e.status}")
        dao.update(e.copy(updatedAtEpoch = System.currentTimeMillis()))
    }

    suspend fun setStatus(name: String, s: FileStatus) {
        logger.x("FileRepo", "setStatus() name=$name status=${s.name}")
        dao.setStatus(name, s.name)
    }

    suspend fun recordRetry(name: String, retryStatus: FileStatus, error: String) {
        logger.x("FileRepo", "recordRetry() name=$name retryStatus=${retryStatus.name} error=$error")
        dao.recordRetry(name, retryStatus.name, error)
    }

    suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long) {
        logger.x("FileRepo", "setDownloadProgress() name=$name downloaded=$downloaded size=$size")
        dao.setDownloadProgress(name, downloaded, size)
    }

    suspend fun reclaimOrphans(from: FileStatus, to: FileStatus): Int {
        logger.x("FileRepo", "reclaimOrphans() from=${from.name} to=${to.name}")
        val res = dao.reclaimOrphans(from.name, to.name)
        logger.x("FileRepo", "reclaimOrphans() returned $res reclaimed rows")
        return res
    }

    fun fileFor(name: String): File {
        logger.x("FileRepo", "fileFor() name=$name")
        return File(storageDir, name)
    }

    suspend fun markCorruptAndReset(name: String, reason: String) {
        logger.x("FileRepo", "markCorruptAndReset() name=$name reason=$reason")
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
        logger.x("FileRepo", "markUploadedAndDelete() name=$name videoId=$videoId deleteLocal=$deleteLocal")
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
