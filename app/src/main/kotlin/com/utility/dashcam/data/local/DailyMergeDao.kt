package com.utility.dashcam.data.local

import androidx.room.*

@Dao
interface DailyMergeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyMerge(merge: DailyMergeEntity)

    @Query("SELECT * FROM daily_merges WHERE dateString = :dateString")
    suspend fun getDailyMerge(dateString: String): DailyMergeEntity?

    @Query("SELECT * FROM daily_merges WHERE uploadStatus IN ('IDLE', 'FAILED')")
    suspend fun getPendingUploads(): List<DailyMergeEntity>

    @Query("SELECT * FROM daily_merges ORDER BY dateString DESC")
    fun getAllMerges(): kotlinx.coroutines.flow.Flow<List<DailyMergeEntity>>

    @Query("UPDATE daily_merges SET mergeStatus = :status WHERE dateString = :dateString")
    suspend fun updateMergeStatus(dateString: String, status: String)

    @Query("UPDATE daily_merges SET uploadStatus = :status, youtubeVideoId = :videoId, lastAttemptTimestamp = :timestamp WHERE dateString = :dateString")
    suspend fun updateUploadStatus(dateString: String, status: String, videoId: String?, timestamp: Long)

    @Query("UPDATE daily_merges SET localMergedPath = NULL WHERE dateString = :dateString")
    suspend fun clearLocalMergedPath(dateString: String)

    // Cross-table DELETE: Room allows raw SQL across tables
    @Query("DELETE FROM raw_clips WHERE dateString = :dateString")
    suspend fun deleteRawClipsByDate(dateString: String)

    @Transaction
    suspend fun completeMergeAndPurgeRaw(dateString: String, mergedPath: String, totalSize: Long) {
        val existing = getDailyMerge(dateString)
        if (existing != null) {
            insertDailyMerge(existing.copy(
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED
            ))
        } else {
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
        deleteRawClipsByDate(dateString)
    }

    @Transaction
    suspend fun markMergeFailed(dateString: String) {
        updateMergeStatus(dateString, MergeStatus.FAILED)
    }
}