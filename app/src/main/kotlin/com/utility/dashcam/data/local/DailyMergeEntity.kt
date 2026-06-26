package com.utility.dashcam.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks state transitions of concatenated daily compilations intended for YouTube deployment.
 */
@Entity(
    tableName = "daily_merges",
    indices = [Index(value = ["uploadStatus"])]
)
data class DailyMergeEntity(
    @PrimaryKey val dateString: String,    // Format: "YYYY-MM-DD"
    val localMergedPath: String?,          // Path to final concatenated file
    val totalSize: Long,                   // Aggregated byte length
    val mergeStatus: String,               // Enums: PENDING, PROCESSING, COMPLETED, FAILED
    val uploadStatus: String,              // Enums: IDLE, QUEUED, UPLOADING, SUCCESS, FAILED
    val youtubeVideoId: String?,           // String returned from Google API upon 200 OK
    val lastAttemptTimestamp: Long         // Epoch timestamp for backoff constraints
)

object MergeStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

object UploadStatus {
    const val IDLE = "IDLE"
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
}
