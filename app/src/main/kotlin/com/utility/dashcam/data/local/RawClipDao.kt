package com.utility.dashcam.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for raw 1-minute dashcam clips.
 * Handles CRUD and state transitions: PENDING → DOWNLOADING → COMPLETED / FAILED
 */
@Dao
interface RawClipDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawClips(clips: List<RawClipEntity>)

    @Query("SELECT * FROM raw_clips WHERE dateString = :dateString")
    fun getRawClipsByDate(dateString: String): Flow<List<RawClipEntity>>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus = :status")
    suspend fun getRawClipsByStatus(status: String): List<RawClipEntity>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus = :status")
    fun getRawClipsByStatusFlow(status: String): Flow<List<RawClipEntity>>

    @Query("SELECT DISTINCT dateString FROM raw_clips WHERE downloadStatus = 'COMPLETED'")
    suspend fun getDistinctCompletedDates(): List<String>

    @Query("SELECT COUNT(*) FROM raw_clips WHERE dateString = :dateString AND downloadStatus != 'COMPLETED'")
    suspend fun countNonCompletedByDate(dateString: String): Int

    @Query("UPDATE raw_clips SET downloadStatus = :status, localFilePath = :localPath WHERE fileName = :fileName")
    suspend fun updateDownloadStatus(fileName: String, status: String, localPath: String?)

    @Query("DELETE FROM raw_clips WHERE dateString = :dateString")
    suspend fun deleteCachedClipsByDate(dateString: String)

    /**
     * Atomic wrapper: mark clip completed and record its local path.
     * Called from ingestion service after successful streaming download.
     */
    @Transaction
    suspend fun markDownloadCompleted(fileName: String, localPath: String) {
        updateDownloadStatus(fileName, DownloadStatus.COMPLETED, localPath)
    }
}