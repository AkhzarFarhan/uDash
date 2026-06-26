package com.utility.dashcam.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for daily merged compilations ready for YouTube upload.
 * Handles state machine: PENDING/PROCESSING/COMPLETED/FAILED → IDLE/QUEUED/UPLOADING/SUCCESS/FAILED
 */
@Dao
interface DailyMergeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyMerge(merge: DailyMergeEntity)

    @Query("SELECT * FROM daily_merges WHERE dateString = :dateString")
    suspend fun getDailyMerge(dateString: String): DailyMergeEntity?

    @Query("SELECT * FROM daily_merges WHERE uploadStatus IN ('IDLE', 'FAILED')")
    suspend fun getPendingUploads(): List<DailyMergeEntity>

    @Query("UPDATE daily_merges SET mergeStatus = :status WHERE dateString = :dateString")
    suspend fun updateMergeStatus(dateString: String, status: String)

    @Query("UPDATE daily_merges SET uploadStatus = :status, youtubeVideoId = :videoId, lastAttemptTimestamp = :timestamp WHERE dateString = :dateString")
    suspend fun updateUploadStatus(dateString: String, status: String, videoId: String?, timestamp: Long)

    /**
     * Atomic transaction: mark merge COMPLETED with merged path/size, and purge raw clips for that date.
     * Architecture §5.2: "Atomic transactional switch"
     */
    @Transaction
    suspend fun completeMergeAndPurgeRaw(
        dateString: String,
        mergedPath: String,
        totalSize: Long,
        rawClipDao: RawClipDao
    ) {
        // Update merge entity to COMPLETED with path and size
        val existing = getDailyMerge(dateString)
        if (existing != null) {
            insertDailyMerge(existing.copy(
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED
            ))
        } else {
            // Should not happen normally, but handle gracefully
            insertDailyMerge(DailyMergeEntity(
                dateString = dateString,
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED,
                uploadStatus = UploadStatus.IDLE,
                youtubeVideoId = null,
                lastAttemptTimestamp = 0
            ))
        }
        // Purge raw clips for this date (architecture §5.2 + §6 cleanup)
        rawClipDao.deleteCachedClipsByDate(dateString)
    }

    /**
     * Mark merge as FAILED (e.g., FFmpeg concat error)
     */
    @Transaction
    suspend fun markMergeFailed(dateString: String) {
        updateMergeStatus(dateString, MergeStatus.FAILED)
    }
}