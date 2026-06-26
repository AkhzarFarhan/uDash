package com.utility.dashcam.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DashcamDao {

    // --- Raw Clips ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawClips(clips: List<RawClipEntity>)

    @Query("SELECT * FROM raw_clips WHERE dateString = :dateString")
    fun getRawClipsByDate(dateString: String): Flow<List<RawClipEntity>>

    @Query("SELECT * FROM raw_clips WHERE downloadStatus = :status")
    suspend fun getRawClipsByStatus(status: String): List<RawClipEntity>

    @Query("UPDATE raw_clips SET downloadStatus = :status, localFilePath = :localPath WHERE fileName = :fileName")
    suspend fun updateDownloadStatus(fileName: String, status: String, localPath: String?)

    @Query("DELETE FROM raw_clips WHERE dateString = :dateString")
    suspend fun deleteCachedClipsByDate(dateString: String)

    // --- Daily Merges ---

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

    // --- Transactions ---

    @Transaction
    suspend fun completeMerge(dateString: String, mergedPath: String, totalSize: Long) {
        updateMergeStatus(dateString, MergeStatus.COMPLETED)
        // Note: The specification says: database.dailyMergeDao().updateMergeStatus(dateStr, "COMPLETED")
        // but we also need to store the path and size.
        val existing = getDailyMerge(dateString)
        if (existing != null) {
            insertDailyMerge(existing.copy(
                localMergedPath = mergedPath,
                totalSize = totalSize,
                mergeStatus = MergeStatus.COMPLETED
            ))
        }
        deleteCachedClipsByDate(dateString)
    }
}
