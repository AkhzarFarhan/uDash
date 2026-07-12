package com.ddpai.uploader.data.repo

import com.ddpai.uploader.data.db.VideoFileDao
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.DashcamFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileRepositoryTest {

    private val mockLog = LogRepository(object : com.ddpai.uploader.data.db.LogDao {
        override suspend fun insert(log: com.ddpai.uploader.data.db.entity.LogEntity) {}
        override fun observeRecent(limit: Int): Flow<List<com.ddpai.uploader.data.db.entity.LogEntity>> = emptyFlow()
        override suspend fun trimTo(keep: Int) {}
        override suspend fun clear() {}
    })

    @Test
    fun testReconcileListingIdempotency() = runBlocking {
        val insertedFiles = mutableListOf<VideoFileEntity>()

        val mockDao = object : VideoFileDao {
            override suspend fun insertIgnore(file: VideoFileEntity): Long {
                return if (insertedFiles.any { it.fileName == file.fileName }) {
                    -1L
                } else {
                    insertedFiles.add(file)
                    1L
                }
            }

            override suspend fun update(file: VideoFileEntity) {}
            override suspend fun getByName(name: String): VideoFileEntity? = null
            override suspend fun getAllKnownFileNames(): List<String> = emptyList()
            override suspend fun getByStatuses(statuses: List<String>): List<VideoFileEntity> = emptyList()
            override suspend fun nextToUpload(): VideoFileEntity? = null
            override suspend fun downloadedSegments(): List<VideoFileEntity> = emptyList()
            override suspend fun relabelAsMerged(name: String, ts: Long) {}
            override suspend fun insertMerged(entity: VideoFileEntity) {}
            override suspend fun markSegmentMerged(name: String, output: String, ts: Long) {}
            override suspend fun pendingDownloads(): List<VideoFileEntity> = emptyList()
            override fun observeAll(): Flow<List<VideoFileEntity>> = emptyFlow()
            override fun observeCountByStatus(status: String): Flow<Int> = emptyFlow()
            override suspend fun setStatus(name: String, status: String, ts: Long) {}
            override suspend fun recordRetry(name: String, retryStatus: String, error: String, ts: Long) {}
            override suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long, ts: Long) {}
            override suspend fun reclaimOrphans(fromStatus: String, toStatus: String, ts: Long): Int = 0
        }

        val repository = FileRepository(mockDao, mockLog, File("."))

        val remoteList = listOf(
            DashcamFile("20260626180905_0060.mp4", 1024L, 123456L),
            DashcamFile("20260626181005_F.mp4", 2048L, 123457L)
        )

        val addedFirst = repository.reconcileListing(remoteList, "193.168.0.1")
        assertEquals(2, addedFirst)
        assertEquals(2, insertedFiles.size)

        val addedSecond = repository.reconcileListing(remoteList, "193.168.0.1")
        assertEquals(0, addedSecond)
        assertEquals(2, insertedFiles.size)
    }
}
