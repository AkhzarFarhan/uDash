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

    @Query("SELECT * FROM raw_clips WHERE dateString = :dateString ORDER BY fileName ASC")
    fun getRawClipsByDate(dateString: String): Flow<List<RawClipEntity>>

    @Query("SELECT * FROM raw_clips WHERE dateString = :dateString AND downloadStatus = 'COMPLETED' ORDER BY fileName ASC")
    suspend fun getCompletedClipsByDate(dateString: String): List<RawClipEntity>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus = :status")
    suspend fun getRawClipsByStatus(status: String): List<RawClipEntity>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus IN ('PENDING', 'FAILED')")
    suspend fun getClipsToDownload(): List<RawClipEntity>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus = :status")
    fun getRawClipsByStatusFlow(status: String): Flow<List<RawClipEntity>>

    @Query("SELECT DISTINCT dateString FROM raw_clips WHERE downloadStatus = 'COMPLETED'")
    suspend fun getDistinctCompletedDates(): List<String>

    @Query("SELECT COUNT(*) FROM raw_clips WHERE dateString = :dateString AND downloadStatus != 'COMPLETED'")
    suspend fun countNonCompletedByDate(dateString: String): Int

    @Query("UPDATE raw_clips SET downloadStatus = :status, localFilePath = :localPath, fileSize = :size WHERE fileName = :fileName")
    suspend fun updateDownloadSuccess(fileName: String, status: String, localPath: String?, size: Long)

    @Query("UPDATE raw_clips SET downloadStatus = :status WHERE fileName = :fileName")
    suspend fun updateDownloadStatus(fileName: String, status: String)

    @Query("DELETE FROM raw_clips WHERE dateString = :dateString")
    suspend fun deleteCachedClipsByDate(dateString: String)

    @Query("DELETE FROM raw_clips WHERE dateString = :dateString AND downloadStatus != 'COMPLETED'")
    suspend fun deleteNonCompletedClipsByDate(dateString: String)

    @Query("UPDATE raw_clips SET downloadStatus = 'PENDING' WHERE downloadStatus = 'DOWNLOADING'")
    suspend fun resetDownloadingClips()

    /**
     * Atomic wrapper: mark clip completed and record its local path and file size.
     * Called from ingestion service after successful streaming download.
     */
    @Transaction
    suspend fun markDownloadCompleted(fileName: String, localPath: String, size: Long) {
        updateDownloadSuccess(fileName, DownloadStatus.COMPLETED, localPath, size)
    }

    @Transaction
    suspend fun markDownloadFailed(fileName: String) {
        updateDownloadStatus(fileName, DownloadStatus.FAILED)
    }
}