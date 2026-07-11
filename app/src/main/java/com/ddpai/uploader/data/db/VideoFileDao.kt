package com.ddpai.uploader.data.db

import androidx.room.*
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoFileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(file: VideoFileEntity): Long

    @Update
    suspend fun update(file: VideoFileEntity)

    @Query("SELECT * FROM video_files WHERE fileName = :name LIMIT 1")
    suspend fun getByName(name: String): VideoFileEntity?

    @Query("SELECT fileName FROM video_files")
    suspend fun getAllKnownFileNames(): List<String>

    @Query("SELECT * FROM video_files WHERE status IN (:statuses) ORDER BY capturedAtEpoch ASC")
    suspend fun getByStatuses(statuses: List<String>): List<VideoFileEntity>

    @Query("SELECT * FROM video_files WHERE status = 'DOWNLOADED' ORDER BY capturedAtEpoch ASC LIMIT 1")
    suspend fun nextToUpload(): VideoFileEntity?

    @Query("SELECT * FROM video_files WHERE status IN ('DISCOVERED','PENDING') ORDER BY capturedAtEpoch ASC")
    suspend fun pendingDownloads(): List<VideoFileEntity>

    @Query("SELECT * FROM video_files ORDER BY capturedAtEpoch DESC")
    fun observeAll(): Flow<List<VideoFileEntity>>

    @Query("SELECT COUNT(*) FROM video_files WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("UPDATE video_files SET status = :status, updatedAtEpoch = :ts WHERE fileName = :name")
    suspend fun setStatus(name: String, status: String, ts: Long = System.currentTimeMillis())

    @Query(
        "UPDATE video_files SET retryCount = retryCount + 1, status = :retryStatus, " +
            "errorMessage = :error, updatedAtEpoch = :ts WHERE fileName = :name"
    )
    suspend fun recordRetry(name: String, retryStatus: String, error: String, ts: Long = System.currentTimeMillis())

    @Query(
        "UPDATE video_files SET downloadedBytes = :downloaded, sizeBytes = :size, " +
            "updatedAtEpoch = :ts WHERE fileName = :name"
    )
    suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long, ts: Long = System.currentTimeMillis())

    /**
     * Reset rows stuck in a transient state after a process kill (no catch block ran). Called once
     * at worker start; safe because each pipeline worker is unique (KEEP), so no live worker holds
     * a row in [fromStatus]. Returns the number of rows reclaimed.
     */
    @Query("UPDATE video_files SET status = :toStatus, updatedAtEpoch = :ts WHERE status = :fromStatus")
    suspend fun reclaimOrphans(fromStatus: String, toStatus: String, ts: Long = System.currentTimeMillis()): Int
}
