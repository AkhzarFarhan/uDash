package com.utility.dashcam.data.local

import androidx.room.*

@Dao
interface DailyMergeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyMerge(merge: DailyMergeEntity)

    @Query("SELECT * FROM daily_merges WHERE mergeId = :mergeId")
    suspend fun getDailyMerge(mergeId: String): DailyMergeEntity?

    @Query("SELECT * FROM daily_merges WHERE dateString = :dateString")
    suspend fun getDailyMergesForDate(dateString: String): List<DailyMergeEntity>

    @Query("SELECT * FROM daily_merges WHERE uploadStatus IN ('IDLE', 'FAILED') AND mergeStatus = 'COMPLETED' AND localMergedPath IS NOT NULL")
    suspend fun getPendingUploads(): List<DailyMergeEntity>

    @Query("SELECT * FROM daily_merges ORDER BY dateString DESC")
    fun getAllMerges(): kotlinx.coroutines.flow.Flow<List<DailyMergeEntity>>

    @Query("UPDATE daily_merges SET mergeStatus = :status WHERE mergeId = :mergeId")
    suspend fun updateMergeStatus(mergeId: String, status: String)

    @Query("UPDATE daily_merges SET uploadStatus = :status, youtubeVideoId = :videoId, lastAttemptTimestamp = :timestamp WHERE mergeId = :mergeId")
    suspend fun updateUploadStatus(mergeId: String, status: String, videoId: String?, timestamp: Long)

    @Query("UPDATE daily_merges SET localMergedPath = NULL WHERE mergeId = :mergeId")
    suspend fun clearLocalMergedPath(mergeId: String)

    // Cross-table DELETE: Room allows raw SQL across tables
    @Query("DELETE FROM raw_clips WHERE dateString = :dateString")
    suspend fun deleteRawClipsByDate(dateString: String)

    @Transaction
    suspend fun completeMergeAndPurgeRaw(mergeId: String, dateString: String, mergedPath: String, totalSize: Long) {
        val existing = getDailyMerge(mergeId)
        if (existing != null) {
            insertDailyMerge(existing.copy(
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED
            ))
        } else {
            insertDailyMerge(DailyMergeEntity(
                mergeId = mergeId,
                dateString = dateString,
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED,
                uploadStatus = UploadStatus.IDLE,
                youtubeVideoId = null,
                lastAttemptTimestamp = 0
            ))
        }
    }

    @Transaction
    suspend fun markMergeFailed(mergeId: String) {
        updateMergeStatus(mergeId, MergeStatus.FAILED)
    }

    @Query("UPDATE daily_merges SET mergeStatus = 'PENDING' WHERE mergeStatus = 'PROCESSING'")
    suspend fun resetProcessingMerges()
}