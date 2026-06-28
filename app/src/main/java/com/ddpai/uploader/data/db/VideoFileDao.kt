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
}
